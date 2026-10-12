package dev.eiriksb.stad.war;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.Movement;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wars between players' towns (PLAN.md §6.7), only between towns that are both open to war. Declaring one starts a
 * warning period; then one raid, inside the defenders' window and with a defender online: the attackers try to hold
 * the ground by the town hall long enough to take its banner. Winners carry off a capped share of the losers' stock,
 * and unhappy citizens may defect. Either way it ends in a truce, and the damage is repaired. Server thread; times
 * are real-world milliseconds.
 */
public final class Wars {
    private Wars() {}

    public record Result(War war, String problem) {}

    /** a raid going on now */
    static final class PvpRaid {
        final War war;
        Snapshot snapshot;
        int capture;
        long lastAttackerSeen;
        /** the guards who marched with the raid, and the player they follow */
        final Map<UUID, UUID> marchers = new LinkedHashMap<>();

        PvpRaid(War war) {
            this.war = war;
        }
    }

    private static final Map<Integer, PvpRaid> raids = new ConcurrentHashMap<>();
    /** how close to the town hall the banner is held */
    public static final double BANNER_REACH = 5;
    /** defenders this close stop it being taken */
    public static final double DEFEND_REACH = 10;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, Wars::onDeath); // before the town forgets the dead
    }

    // ---- looking things up ------------------------------------------------------------------------------------------

    /** The war on between two towns, if any. */
    public static Optional<War> between(MinecraftServer server, int a, int b) {
        return ColonyManager.get(server).wars().stream().filter(w -> w.on() && w.involves(a) && w.involves(b)).findFirst();
    }

    /** The wars a town is in. */
    public static List<War> of(MinecraftServer server, int colony) {
        return ColonyManager.get(server).wars().stream().filter(w -> w.on() && w.involves(colony)).toList();
    }

    public static boolean atWar(MinecraftServer server, int colony) {
        return !of(server, colony).isEmpty();
    }

    /** The raid a town is in now (attacking or defending), if any. */
    public static Optional<War> raidOf(int colony) {
        return raids.values().stream().map(r -> r.war).filter(w -> w.involves(colony)).findFirst();
    }

    /** The towns a town is fighting a raid with right now. */
    public static Set<Integer> raidEnemies(int colony) {
        Set<Integer> out = new HashSet<>();
        raids.values().forEach(r -> {
            if (r.war.involves(colony)) {
                out.add(r.war.other(colony));
            }
        });
        return out;
    }

    /** The player a marching guard follows, if they're marching. */
    public static Optional<UUID> leaderOf(UUID guard) {
        return raids.values().stream().map(r -> r.marchers.get(guard)).filter(java.util.Objects::nonNull).findFirst();
    }

    /** "2 days and 3 hours", "45 minutes" */
    public static String duration(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        long days = minutes / 1440, hours = minutes % 1440 / 60, mins = minutes % 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + (days == 1 ? " day" : " days"));
        }
        if (hours > 0) {
            parts.add(hours + (hours == 1 ? " hour" : " hours"));
        }
        if (days == 0 && mins > 0) {
            parts.add(mins + (mins == 1 ? " minute" : " minutes"));
        }
        return dev.eiriksb.stad.planner.Design.join(parts);
    }

    private static Optional<Colony> colony(MinecraftServer server, int id) {
        return ColonyManager.get(server).byId(id);
    }

    // ---- declaring ----------------------------------------------------------------------------------------------

    /** {@code player}, an officer of {@code from}, declares war on {@code to}. */
    public static Result declare(ServerPlayer player, Colony from, Colony to, long now) {
        MinecraftServer server = player.server;
        if (StadConfig.PVP_RAIDS.get() == StadConfig.PvpRaids.OFF) {
            return new Result(null, "Wars between towns are switched off on this server.");
        }
        if (!from.rank(player.getUUID()).atLeast(OrderKind.DECLARE_WAR.minRank)) {
            return new Result(null, "Only officers of " + from.name() + " can declare war.");
        }
        if (from.id == to.id) {
            return new Result(null, "A town can't go to war with itself.");
        }
        if (!from.politics.open()) {
            return new Result(null, from.name() + " is peaceful. Its founder can open it to war at the town hall first.");
        }
        if (!to.politics.open()) {
            return new Result(null, to.name() + " is peaceful: it can't be attacked.");
        }
        if (between(server, from.id, to.id).isPresent()) {
            return new Result(null, from.name() + " is already at war with " + to.name() + ".");
        }
        Optional<War> last = ColonyManager.get(server).wars().stream().filter(w -> !w.on() && w.involves(from.id) && w.involves(to.id))
                .reduce((a, b) -> b);
        long truceEnds = last.map(w -> w.ended + StadConfig.TRUCE_MINUTES.get() * 60_000L).orElse(0L);
        if (now < truceEnds) {
            return new Result(null, "There's a truce with " + to.name() + " for another " + duration(truceEnds - now) + ".");
        }
        String who = player.getGameProfile().getName();
        War war = ColonyManager.get(server).addWar(from.id, to.id, now, player.getUUID(), who);
        long gameTime = server.overworld().getGameTime();
        int mood = mood(server, from, to);
        if (mood != 0) {
            from.changeStanding(player.getUUID(), who, mood, mood < 0 ? "you started a war with " + to.name() + " the town didn't want"
                    : "you declared war on " + to.name() + " (some here were spoiling for a fight)", gameTime);
        }
        remember(from, "Our leader " + who + " declared war on " + to.name() + ".", 6);
        remember(to, who + " of " + from.name() + " declared war on us.", 7);
        long warning = StadConfig.WAR_WARNING_MINUTES.get() * 60_000L;
        Component msg = Component.literal(who + " of " + from.name() + " has declared war on " + to.name() + "! A raid can come "
                + (warning > 0 ? "in " + duration(warning) + ", " : "") + "within " + to.name() + "'s raid window (" + to.politics.window() + ").")
                .withStyle(ChatFormatting.RED);
        Announce.members(server, from, msg);
        Announce.members(server, to, msg);
        return new Result(war, null);
    }

    /** What the town makes of going to war: bold sorts like it, peaceful ones don't, family in the other town hate it. */
    static int mood(MinecraftServer server, Colony from, Colony to) {
        double sum = 0;
        Set<UUID> theirs = new HashSet<>();
        to.citizens().forEach(r -> theirs.add(r.uuid));
        for (CitizenRecord r : from.citizens()) {
            String p = TheyWillTalkApi.get().profile(r.uuid).map(VillagerProfile::persona).orElse("");
            sum += switch (p) {
                case "boastful", "dramatic", "grumpy" -> 1;
                case "peaceful", "friendly", "sensitive", "shy", "cheerful", "wise" -> -1;
                default -> 0;
            };
            if ((r.spouse != null && theirs.contains(r.spouse)) || r.parents.stream().anyMatch(theirs::contains)) {
                sum -= 3;
            }
        }
        for (CitizenRecord r : to.citizens()) {
            if (r.parents.stream().anyMatch(id -> from.hasCitizen(id))) {
                sum -= 3; // their children live there
            }
        }
        return (int) Math.max(-10, Math.min(4, Math.round(sum * 1.5)));
    }

    // ---- raiding ------------------------------------------------------------------------------------------------

    private static boolean officerOnline(MinecraftServer server, Colony c) {
        return server.getPlayerList().getPlayers().stream().anyMatch(p -> c.rank(p.getUUID()).atLeast(Rank.OFFICER));
    }

    /** Why the attackers can't raid now, or null. */
    public static String cantRaid(MinecraftServer server, War w, long now) {
        Colony defender = colony(server, w.defender).orElse(null);
        if (defender == null || !w.on()) {
            return "That war is over.";
        }
        if (w.state == War.State.RAID) {
            return "The raid is already on.";
        }
        long ready = w.declared + StadConfig.WAR_WARNING_MINUTES.get() * 60_000L;
        if (now < ready) {
            return "The warning period isn't over: " + duration(ready - now) + " to go.";
        }
        if (!defender.politics.inWindow(now)) {
            return defender.name() + " can only be raided " + defender.politics.window() + " (server time).";
        }
        if (!StadConfig.OFFLINE_RAIDS.get() && !officerOnline(server, defender)) {
            return "None of " + defender.name() + "'s officers are online, and towns aren't raided while nobody can defend them.";
        }
        if (Raids.of(defender).isPresent()) {
            return defender.name() + " is fighting off raiders already.";
        }
        return null;
    }

    /** {@code player} leads the raid, standing on the defenders' land. */
    public static Result startRaid(ServerPlayer player, War w, long now) {
        MinecraftServer server = player.server;
        Colony attacker = colony(server, w.attacker).orElse(null), defender = colony(server, w.defender).orElse(null);
        if (attacker == null || defender == null) {
            return new Result(null, "That war is over.");
        }
        if (!attacker.rank(player.getUUID()).atLeast(OrderKind.DECLARE_WAR.minRank)) {
            return new Result(null, "Only officers of " + attacker.name() + " can lead a raid.");
        }
        if (!defender.contains(player.level().dimension(), player.blockPosition())) {
            return new Result(null, "Lead the raid from " + defender.name() + "'s land.");
        }
        String problem = cantRaid(server, w, now);
        if (problem != null) {
            return new Result(null, problem);
        }
        ServerLevel level = player.serverLevel();
        w.state = War.State.RAID;
        w.raidStarted = now;
        PvpRaid raid = new PvpRaid(w);
        raid.snapshot = Snapshot.take(level, defender);
        raid.lastAttackerSeen = now;
        raids.put(w.id, raid);
        ColonyManager.get(server).setDirty();
        // the attackers' guards are ordered to march (a resented leader's guards may refuse)
        List<String> refused = new ArrayList<>();
        ServerLevel home = server.getLevel(attacker.dimension);
        if (home != null && home == level) {
            for (CitizenRecord r : attacker.citizens()) {
                if (level.getEntity(r.uuid) instanceof CitizenEntity c && Combat.guard(c)) {
                    Order.Result o = OrderService.issue(new Order(player, OrderKind.MARCH, c, defender.name()));
                    if (o.done()) {
                        Movement.teleportNear(c, player.blockPosition());
                        raid.marchers.put(c.getUUID(), player.getUUID());
                    } else {
                        refused.add(c.getName().getString());
                    }
                }
            }
        }
        for (CitizenRecord r : defender.citizens()) {
            if (level.getEntity(r.uuid) instanceof CitizenEntity c && Combat.guard(c) && c.isSleeping()) {
                c.stopSleeping();
            }
        }
        level.playSound(null, defender.center, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64f, 1.1f);
        String who = player.getGameProfile().getName();
        int n = raid.marchers.size();
        Component msg = Component.literal(who + " is leading " + attacker.name() + "'s raid on " + defender.name()
                + (n > 0 ? ", with " + n + (n == 1 ? " guard" : " guards") : "") + "! Hold the town hall for "
                + StadConfig.CAPTURE_SECONDS.get() + " seconds with no defenders near to take its banner.").withStyle(ChatFormatting.RED);
        Announce.members(server, attacker, msg);
        Announce.members(server, defender, msg);
        if (!refused.isEmpty()) {
            player.sendSystemMessage(Component.literal(dev.eiriksb.stad.planner.Design.join(refused) + (refused.size() == 1 ? " wouldn't" : " wouldn't")
                    + " march.").withStyle(ChatFormatting.YELLOW));
        }
        return new Result(w, null);
    }

    /** Every second: the banner, the clock, and whether the attackers are still there. */
    public static void tick(MinecraftServer server, long now) {
        for (War w : ColonyManager.get(server).wars()) {
            if (w.state == War.State.RAID && !raids.containsKey(w.id)) {
                end(server, w, War.Outcome.DEFENDERS, now); // the server restarted mid-raid
            }
        }
        for (PvpRaid raid : List.copyOf(raids.values())) {
            War w = raid.war;
            Colony attacker = colony(server, w.attacker).orElse(null), defender = colony(server, w.defender).orElse(null);
            ServerLevel level = defender == null ? null : server.getLevel(defender.dimension);
            if (attacker == null || level == null) {
                raids.remove(w.id);
                continue;
            }
            int attackersNear = 0, defendersNear = 0;
            boolean attackersThere = false;
            for (ServerPlayer p : level.players()) {
                if (!p.isAlive() || p.isSpectator()) {
                    continue;
                }
                double d = Math.sqrt(p.blockPosition().distSqr(defender.center));
                if (attacker.rank(p.getUUID()).atLeast(Rank.MEMBER) && defender.contains(level.dimension(), p.blockPosition())) {
                    attackersThere = true;
                    if (d <= BANNER_REACH) {
                        attackersNear++;
                    }
                } else if (defender.rank(p.getUUID()).atLeast(Rank.MEMBER) && d <= DEFEND_REACH) {
                    defendersNear++;
                }
            }
            for (CitizenEntity c : level.getEntitiesOfClass(CitizenEntity.class, new net.minecraft.world.phys.AABB(defender.center).inflate(DEFEND_REACH),
                    c -> c.isAlive() && defender.hasCitizen(c.getUUID()) && Combat.fighter(c))) {
                defendersNear++;
            }
            if (attackersThere) {
                raid.lastAttackerSeen = now;
            }
            int need = StadConfig.CAPTURE_SECONDS.get();
            if (attackersNear > 0 && defendersNear == 0) {
                raid.capture++;
            } else if (defendersNear > 0) {
                raid.capture = Math.max(0, raid.capture - 2);
            }
            Component bar = Component.literal("Banner of " + defender.name() + ": " + raid.capture + "/" + need + "s"
                    + (defendersNear > 0 && attackersNear > 0 ? " (contested)" : "")).withStyle(ChatFormatting.GOLD);
            for (ServerPlayer p : level.players()) {
                if (defender.contains(level.dimension(), p.blockPosition())
                        && (attacker.rank(p.getUUID()).atLeast(Rank.MEMBER) || defender.rank(p.getUUID()).atLeast(Rank.MEMBER))) {
                    p.displayClientMessage(bar, true);
                }
            }
            if (raid.capture >= need) {
                end(server, w, War.Outcome.ATTACKERS, now);
            } else if (now - w.raidStarted >= StadConfig.RAID_MINUTES.get() * 60_000L) {
                end(server, w, War.Outcome.DEFENDERS, now);
            } else if (now - raid.lastAttackerSeen > 60_000L) {
                end(server, w, War.Outcome.RETREAT, now);
            }
        }
    }

    /** For tests and admins: how far the banner has been taken. */
    public static int capture(War w) {
        PvpRaid r = raids.get(w.id);
        return r == null ? 0 : r.capture;
    }

    // ---- ending -------------------------------------------------------------------------------------------------

    /** The war ends (a raid decided, peace made, or a retreat), and the truce begins. */
    public static void end(MinecraftServer server, War w, War.Outcome outcome, long now) {
        if (!w.on()) {
            return;
        }
        PvpRaid raid = raids.remove(w.id);
        w.state = War.State.OVER;
        w.ended = now;
        w.outcome = outcome;
        ColonyManager.get(server).setDirty();
        Colony attacker = colony(server, w.attacker).orElse(null), defender = colony(server, w.defender).orElse(null);
        if (attacker == null || defender == null) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        ServerLevel defLevel = server.getLevel(defender.dimension), attLevel = server.getLevel(attacker.dimension);
        String loot = "";
        List<String> defected = new ArrayList<>();
        switch (outcome) {
            case ATTACKERS -> {
                if (defLevel != null && attLevel != null) {
                    loot = loot(defLevel, defender, attLevel, attacker);
                    defected = defect(defLevel, defender, attLevel, attacker);
                }
                attacker.changeLeadersStanding(4, "you won the raid on " + defender.name(), gameTime);
                defender.changeLeadersStanding(-4, attacker.name() + " took our banner", gameTime);
            }
            case DEFENDERS, RETREAT -> {
                defender.changeLeadersStanding(5, "you held off " + attacker.name() + "'s raid", gameTime);
                attacker.changeLeadersStanding(-3, "the raid on " + defender.name() + " failed", gameTime);
            }
            case PEACE -> {
                attacker.changeLeadersStanding(1, "you made peace with " + defender.name(), gameTime);
                defender.changeLeadersStanding(1, "you made peace with " + attacker.name(), gameTime);
            }
        }
        if (raid != null) {
            for (UUID id : raid.marchers.keySet()) {
                if (attLevel != null && attLevel.getEntity(id) instanceof CitizenEntity c) {
                    Movement.teleportNear(c, attacker.center); // home again
                }
            }
            if (raid.snapshot != null && defLevel != null) {
                List<Building> repairs = raid.snapshot.repair(defLevel, defender);
                if (!repairs.isEmpty()) {
                    Announce.members(server, defender, Component.literal("The builders will repair what the raid broke.").withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        String story = switch (outcome) {
            case PEACE -> attacker.name() + " and " + defender.name() + " made peace.";
            case ATTACKERS -> attacker.name() + " raided " + defender.name() + " and took its banner" + (loot.isEmpty() ? "" : ", carrying off " + loot) + "."
                    + (defected.isEmpty() ? "" : " " + dev.eiriksb.stad.planner.Design.join(defected) + " went over to " + attacker.name() + ".");
            case DEFENDERS -> defender.name() + " held off a raid by " + attacker.name() + ".";
            case RETREAT -> attacker.name() + "'s raid on " + defender.name() + " fell back.";
        };
        remember(attacker, story, 7);
        remember(defender, story, 7);
        long truce = StadConfig.TRUCE_MINUTES.get() * 60_000L;
        Component msg = Component.literal("The war between " + attacker.name() + " and " + defender.name() + " is over: " + story
                + (truce > 0 ? " There's a truce for " + duration(truce) + "." : "")).withStyle(ChatFormatting.GOLD);
        Announce.members(server, attacker, msg);
        Announce.members(server, defender, msg);
        Envoys.warOver(server, w.id);
    }

    /** The winners carry off a share of the losers' warehouse stock (the town hall's, when they have no warehouse). */
    static String loot(ServerLevel from, Colony loser, ServerLevel to, Colony winner) {
        ColonyStorage stock = ColonyStorage.warehouses(from, loser);
        if (stock.isEmpty()) {
            stock = ColonyStorage.deliveries(from, loser);
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ColonyStorage.Box b : stock.boxes()) {
            for (int i = 0; i < b.container().getContainerSize(); i++) {
                ItemStack s = b.container().getItem(i);
                if (!s.isEmpty()) {
                    counts.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            }
        }
        int left = StadConfig.LOOT_MAX_ITEMS.get();
        int pct = StadConfig.LOOT_PERCENT.get();
        ColonyStorage dest = ColonyStorage.deliveries(to, winner);
        int total = 0;
        List<Map.Entry<Item, Integer>> biggest = new ArrayList<>(counts.entrySet());
        biggest.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> taken = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : biggest) {
            int n = Math.min(left, e.getValue() * pct / 100);
            if (n <= 0) {
                continue;
            }
            Item item = e.getKey();
            for (ItemStack s : stock.takeUpTo(st -> st.is(item), n)) {
                ItemStack rest = dest.put(s);
                if (!rest.isEmpty()) {
                    to.addFreshEntity(new ItemEntity(to, winner.center.getX() + 0.5, winner.center.getY() + 1, winner.center.getZ() + 0.5, rest));
                }
                taken.merge(item, s.getCount(), Integer::sum);
                total += s.getCount();
                left -= s.getCount();
            }
            if (left <= 0) {
                break;
            }
        }
        return total == 0 ? "" : dev.eiriksb.stad.building.Construction.describe(taken, 3);
    }

    /** Unhappy citizens of the losing town may go over to the winners. */
    static List<String> defect(ServerLevel from, Colony loser, ServerLevel to, Colony winner) {
        List<String> out = new ArrayList<>();
        ColonyManager m = ColonyManager.get(from.getServer());
        for (CitizenRecord r : List.copyOf(loser.citizens())) {
            if (r.happiness < 40 && from.random.nextBoolean() && from.getEntity(r.uuid) instanceof CitizenEntity c && !c.isBaby()) {
                m.join(winner, c);
                if (from == to) {
                    Movement.teleportNear(c, winner.center);
                }
                out.add(r.name);
            }
        }
        return out;
    }

    // ---- peace ----------------------------------------------------------------------------------------------------

    /** An officer of {@code colony} offers peace; when both sides have, the war is over. Returns what happened, or why not. */
    public static Result offerPeace(ServerPlayer player, War w, Colony colony, long now) {
        if (!w.on() || !w.involves(colony.id)) {
            return new Result(null, "That war is over.");
        }
        if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return new Result(null, "Only officers of " + colony.name() + " can make peace.");
        }
        w.peaceOffers.add(colony.id);
        ColonyManager.get(player.server).setDirty();
        if (w.peaceOffers.contains(w.other(colony.id))) {
            end(player.server, w, War.Outcome.PEACE, now);
            return new Result(w, null);
        }
        colony(player.server, w.other(colony.id)).ifPresent(o -> Announce.members(player.server, o, Component.literal(
                player.getGameProfile().getName() + " of " + colony.name() + " offers peace. An officer can accept with /stad war peace "
                        + colony.name() + ".").withStyle(ChatFormatting.AQUA)));
        return new Result(w, null);
    }

    // ---- what happens in a raid --------------------------------------------------------------------------------------

    /** Killing someone who wasn't fighting, in a raid, is remembered on both sides and costs a lot of standing. */
    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity c) || !(event.getSource().getEntity() instanceof ServerPlayer p) || c.level().isClientSide) {
            return;
        }
        Colony victims = c.colony().orElse(null);
        if (victims == null || Combat.fighter(c)) {
            return;
        }
        for (PvpRaid raid : raids.values()) {
            if (!raid.war.involves(victims.id)) {
                continue;
            }
            Colony killers = colony(p.server, raid.war.other(victims.id)).orElse(null);
            if (killers == null || !killers.rank(p.getUUID()).atLeast(Rank.MEMBER)) {
                continue;
            }
            long t = c.level().getGameTime();
            String who = p.getGameProfile().getName(), name = c.getName().getString();
            killers.changeStanding(p.getUUID(), who, -10, "you killed " + name + " of " + victims.name() + ", who wasn't fighting", t);
            // (their own town's standing for the killer drops by the usual -30 for killing a citizen)
            remember(victims, who + " of " + killers.name() + " killed " + name + " in the raid, and " + name + " wasn't even fighting.", 9);
            remember(killers, who + " killed " + name + " of " + victims.name() + " in the raid, someone who wasn't fighting.", 7);
        }
    }

    static void remember(Colony colony, String text, int importance) {
        for (CitizenRecord r : colony.citizens()) {
            TheyWillTalkApi.get().memory().remember(NewMemory.of(r.uuid, Subject.NONE, text).withKind("event").withImportance(importance));
        }
    }

    public static void clearAll() {
        raids.clear();
    }
}
