package dev.eiriksb.stad.war;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.twt.StadConversations;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob raids (PLAN.md §6.7): now and then at dusk raiders come for a town that's big enough to be worth it. Someone
 * sees them coming and warns everyone (a horn sounds), the town's buildings are snapshotted, then the raiders arrive
 * from that side. Guards and brave citizens fight; everyone else shelters. Afterwards the town remembers it, the
 * leaders' standing rises or falls with how it went, and the builders get repair orders. Server thread; raids aren't
 * saved (raiders still about after a restart just stay hostile mobs).
 */
public final class Raids {
    private Raids() {}

    public enum Phase { WARNING, FIGHT }

    public static final String TAG = "stad_raider";
    /** how long the warning lasts before they arrive */
    public static final int WARNING_TICKS = 1200;
    /** the longest a raid lasts before the raiders give up */
    public static final int FIGHT_TICKS = 6000;

    public static final class MobRaid {
        public final int colony;
        public Phase phase = Phase.WARNING;
        public final long started;
        public long fightAt;
        public final Direction from;
        public final Set<UUID> raiders = new HashSet<>();
        public int spawned;
        public int killed;
        public final Map<String, Integer> heroes = new LinkedHashMap<>();
        public final List<String> dead = new ArrayList<>();
        public boolean leaderThere;
        Snapshot snapshot;

        MobRaid(int colony, long started, Direction from) {
            this.colony = colony;
            this.started = started;
            this.from = from;
        }
    }

    private static final Map<Integer, MobRaid> raids = new ConcurrentHashMap<>();

    public static void register() {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, Raids::onDeath); // before the town forgets the dead
    }

    public static Optional<MobRaid> of(Colony colony) {
        return Optional.ofNullable(raids.get(colony.id));
    }

    public static boolean isRaider(Entity e, int colony) {
        return e.getTags().contains(TAG) && e.getPersistentData().getInt("StadRaid") == colony;
    }

    /** How many raiders come: more for bigger, richer towns, and on harder settings. */
    public static int strength(Colony colony) {
        long built = colony.buildings().stream().filter(Building::done).count();
        double n = 2 + colony.population() / 3.0 + built / 4.0;
        n *= switch (StadConfig.MOB_RAIDS.get()) {
            case EASY -> 0.6;
            case HARD -> 1.5;
            default -> 1.0;
        };
        return (int) Math.max(2, Math.min(StadConfig.MAX_RAIDERS.get(), Math.round(n)));
    }

    private static int days() {
        int d = StadConfig.MOB_RAID_DAYS.get();
        return switch (StadConfig.MOB_RAIDS.get()) {
            case EASY -> d * 2;
            case HARD -> Math.max(1, d / 2);
            default -> d;
        };
    }

    /** Every few seconds, for each town. */
    public static void tick(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        MobRaid raid = raids.get(colony.id);
        if (raid == null) {
            if (colony.politics.lastMobRaidDay == 0) {
                colony.politics.lastMobRaidDay = day; // a new town gets a few quiet days first
                colony.changed();
            }
            long t = level.getDayTime() % 24000;
            if (StadConfig.MOB_RAIDS.get() != StadConfig.MobRaids.OFF && t >= 12000 && t < 12500
                    && colony.population() >= StadConfig.MOB_RAID_MIN_PEOPLE.get() && day - colony.politics.lastMobRaidDay >= days()
                    && level.random.nextInt(3) == 0 && Wars.raidOf(colony.id).isEmpty()) {
                start(level, colony, WARNING_TICKS);
            }
            return;
        }
        long now = level.getGameTime();
        raid.leaderThere |= leaderNear(level, colony);
        if (raid.phase == Phase.WARNING) {
            if (now >= raid.fightAt) {
                fight(level, colony, raid);
            }
            return;
        }
        raid.raiders.removeIf(id -> !(level.getEntity(id) instanceof LivingEntity e) || !e.isAlive());
        if (raid.raiders.isEmpty()) {
            end(level, colony, raid, true);
        } else if (now - raid.fightAt > FIGHT_TICKS || (level.getDayTime() % 24000 > 23000 && now - raid.fightAt > 1200)) {
            end(level, colony, raid, false);
        }
    }

    private static boolean leaderNear(ServerLevel level, Colony colony) {
        for (ServerPlayer p : level.players()) {
            if (colony.rank(p.getUUID()).atLeast(Rank.OFFICER) && colony.contains(level.dimension(), p.blockPosition())) {
                return true;
            }
        }
        return false;
    }

    private static String dir(Direction d) {
        return d.getName();
    }

    /** Raiders are seen coming: warns the town; they arrive after {@code warningTicks}. */
    public static MobRaid start(ServerLevel level, Colony colony, int warningTicks) {
        Direction from = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
        MobRaid raid = new MobRaid(colony.id, level.getGameTime(), from);
        raid.fightAt = level.getGameTime() + warningTicks;
        raids.put(colony.id, raid);
        level.playSound(null, colony.center, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64f, 1f);
        CitizenEntity lookout = level.getEntitiesOfClass(CitizenEntity.class, new net.minecraft.world.phys.AABB(colony.center).inflate(colony.radius),
                c -> colony.hasCitizen(c.getUUID()) && !c.isBaby()).stream().findAny().orElse(null);
        String who = lookout == null ? "A lookout" : lookout.getName().getString();
        Announce.members(level.getServer(), colony, Component.literal("[" + colony.name() + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(who + ": Raiders! They're coming from the " + dir(from) + "! Guards to the " + dir(from)
                        + ", everyone else get inside!").withStyle(ChatFormatting.RED)));
        if (lookout != null) {
            StadConversations.speakUp(lookout, 48, "(You just saw raiders coming toward " + colony.name() + " from the " + dir(from)
                    + ". Warn {player} urgently, in a sentence.)");
        }
        // guards wake up and get ready
        for (CitizenEntity c : citizens(level, colony)) {
            if (Combat.guard(c)) {
                if (c.isSleeping()) {
                    c.stopSleeping();
                }
                Combat.arm(c, level, colony);
            }
        }
        return raid;
    }

    private static List<CitizenEntity> citizens(ServerLevel level, Colony colony) {
        List<CitizenEntity> out = new ArrayList<>();
        for (CitizenRecord r : colony.citizens()) {
            if (level.getEntity(r.uuid) instanceof CitizenEntity c) {
                out.add(c);
            }
        }
        return out;
    }

    /** The raiders arrive. */
    static void fight(ServerLevel level, Colony colony, MobRaid raid) {
        raid.phase = Phase.FIGHT;
        raid.fightAt = level.getGameTime();
        raid.snapshot = Snapshot.take(level, colony);
        int n = strength(colony);
        int dist = Math.max(10, colony.radius - 6);
        BlockPos base = colony.center.relative(raid.from, dist);
        for (int tries = 0; tries < 4 && !level.hasChunkAt(base); tries++) {
            dist /= 2;
            base = colony.center.relative(raid.from, Math.max(8, dist));
        }
        boolean tough = StadConfig.MOB_RAIDS.get() == StadConfig.MobRaids.HARD; // vindicators cut citizens down: hard only
        for (int i = 0; i < n; i++) {
            EntityType<? extends Mob> type = tough && i % 3 == 2 ? EntityType.VINDICATOR : EntityType.PILLAGER;
            Mob m = type.create(level);
            if (m == null) {
                continue;
            }
            BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    base.offset(level.random.nextInt(7) - 3, 0, level.random.nextInt(7) - 3));
            m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360, 0);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
            prepare(m, colony);
            level.addFreshEntity(m);
            raid.raiders.add(m.getUUID());
        }
        raid.spawned = raid.raiders.size();
        level.playSound(null, base, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64f, 0.8f);
        Announce.members(level.getServer(), colony, Component.literal(raid.spawned + " raiders are attacking " + colony.name() + " from the "
                + dir(raid.from) + "!").withStyle(ChatFormatting.RED));
    }

    /** Makes a mob one of this town's raiders: it stays, goes for the town hall, and attacks citizens. */
    public static void prepare(Mob m, Colony colony) {
        m.setPersistenceRequired();
        m.addTag(TAG);
        m.getPersistentData().putInt("StadRaid", colony.id);
        // they go for whoever fights them, and for anyone else only when they come close (a citizen indoors is safe)
        m.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(m, CitizenEntity.class, 10, true, false,
                e -> e instanceof CitizenEntity c && (Combat.fighter(c) || c.distanceToSqr(m) < 36)));
        m.goalSelector.addGoal(5, new MarchGoal(m, colony.center));
    }

    /** Raiders head for the town hall when they've nobody to fight. */
    static final class MarchGoal extends Goal {
        private final Mob mob;
        private final BlockPos to;

        MarchGoal(Mob mob, BlockPos to) {
            this.mob = mob;
            this.to = to;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getTarget() == null && mob.blockPosition().distSqr(to) > 36;
        }

        private int repath;

        @Override
        public void tick() {
            if (--repath <= 0 && mob.getNavigation().isDone()) {
                repath = 20;
                mob.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
            }
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide) {
            return;
        }
        for (MobRaid raid : raids.values()) {
            if (raid.raiders.remove(dead.getUUID())) {
                raid.killed++;
                if (event.getSource().getEntity() instanceof CitizenEntity hero) {
                    raid.heroes.merge(hero.getName().getString(), 1, Integer::sum);
                } else if (event.getSource().getEntity() instanceof ServerPlayer p) {
                    raid.heroes.merge(p.getGameProfile().getName(), 1, Integer::sum);
                }
            } else if (dead instanceof CitizenEntity c && raid.phase == Phase.FIGHT && c.colony().map(x -> x.id == raid.colony).orElse(false)) {
                raid.dead.add(c.getName().getString());
            }
        }
    }

    /** The raid is over: beaten off, or they gave up and left. */
    static void end(ServerLevel level, Colony colony, MobRaid raid, boolean beaten) {
        raids.remove(colony.id);
        for (UUID id : raid.raiders) {
            if (level.getEntity(id) instanceof Mob m) {
                m.discard(); // they slip away
            }
        }
        long now = level.getGameTime();
        colony.politics.lastMobRaidDay = level.getDayTime() / 24000;
        long t = level.getDayTime() % 24000;
        StringBuilder story = new StringBuilder("Raiders attacked " + colony.name() + (t >= 12000 || t < 1000 ? " at night" : "") + ", from the "
                + dir(raid.from) + ".");
        List<String> heroes = new ArrayList<>();
        raid.heroes.forEach((who, n) -> heroes.add(who + " killed " + (n == 1 ? "one" : n) + " of them"));
        if (!heroes.isEmpty()) {
            story.append(' ').append(Design.join(heroes)).append('.');
        }
        if (!raid.dead.isEmpty()) {
            story.append(' ').append(Design.join(raid.dead)).append(raid.dead.size() == 1 ? " was" : " were").append(" killed.");
        }
        story.append(beaten ? " We beat them off." : " The ones left got away.");
        if (beaten && raid.dead.isEmpty()) {
            colony.changeLeadersStanding(5, "you saw the town through a raid", now);
        }
        if (!raid.leaderThere) {
            colony.changeLeadersStanding(-3, "you weren't there when the raiders came", now);
        }
        for (CitizenRecord r : colony.citizens()) {
            TheyWillTalkApi.get().memory().remember(NewMemory.of(r.uuid, Subject.NONE, story.toString()).withKind("event").withImportance(7));
        }
        List<Building> repairs = raid.snapshot == null ? List.of() : raid.snapshot.repair(level, colony);
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal((beaten ? "The raid on " + colony.name() + " is over: " : "The raiders have left "
                + colony.name() + ". ") + story.substring(story.indexOf(".") + 1).strip()
                + (repairs.isEmpty() ? "" : " The builders will repair the damage.")).withStyle(beaten ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        ColonyManager.get(level.getServer()).setDirty();
    }

    /** Ends a raid at once (admins); the raiders vanish. */
    public static boolean stop(ServerLevel level, Colony colony) {
        MobRaid raid = raids.get(colony.id);
        if (raid == null) {
            return false;
        }
        end(level, colony, raid, raid.raiders.isEmpty());
        return true;
    }

    public static void clearAll() {
        raids.clear();
    }
}
