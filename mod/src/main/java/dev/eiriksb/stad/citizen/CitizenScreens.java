package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.CitizenLinePayload;
import dev.eiriksb.stad.net.CitizenScreenPayload;
import dev.eiriksb.theywilltalk.api.ChatLine;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The chat screen's server side (PLAN.md §7): right-clicking a citizen opens it with who they are, how they're doing,
 * what they think of you, the last things you said to each other and the orders you may give. Lines typed there go
 * into They Will Talk's conversation like typed chat; replies (and lines spoken aloud) show up in it as they come.
 */
public final class CitizenScreens {
    private CitizenScreens() {}

    /** how far away a citizen can be talked to from the screen */
    public static final double REACH = 16;
    private static final int HISTORY = 30;
    private static final int GREEN = 0x55FF55, AQUA = 0x55FFFF, RED = 0xFF5555, GOLD = 0xFFAA00, YELLOW = 0xFFFF55;

    /** Whether the player's client has Stad (and so the screen); otherwise they get the chat card. */
    public static boolean canShow(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(CitizenScreenPayload.TYPE);
    }

    /** Opens the screen (after a quick look at what They Will Talk remembers). Server thread. */
    public static void open(ServerPlayer player, CitizenEntity c) {
        MinecraftServer server = player.server;
        TheyWillTalkApi twt = TheyWillTalkApi.get();
        CompletableFuture<Integer> affinity = twt.running() ? twt.memory().affinity(c.getUUID(), player.getUUID()).exceptionally(t -> 0)
                : CompletableFuture.completedFuture(0);
        CompletableFuture<List<ChatLine>> history = twt.running() ? twt.history(c.getUUID(), player.getUUID(), HISTORY).exceptionally(t -> List.of())
                : CompletableFuture.completedFuture(List.of());
        affinity.thenCombine(history, (a, h) -> new Object[]{a, h})
                .completeOnTimeout(new Object[]{0, List.of()}, 600, TimeUnit.MILLISECONDS)
                .thenAccept(r -> server.execute(() -> {
                    @SuppressWarnings("unchecked")
                    List<ChatLine> lines = (List<ChatLine>) r[1];
                    if (c.isAlive() && !player.hasDisconnected()) {
                        PacketDistributor.sendToPlayer(player, payload(player, c, (Integer) r[0], lines));
                    }
                }));
    }

    /** What the screen shows this player about this citizen. */
    public static CitizenScreenPayload payload(ServerPlayer player, CitizenEntity c, int affinity, List<ChatLine> history) {
        ColonyManager colonies = ColonyManager.get(player.server);
        Optional<Colony> home = c.colony();
        List<String> about = new ArrayList<>();
        List<CitizenScreenPayload.Action> actions = new ArrayList<>();
        int age = CitizenVoice.age(c);
        String job = c.isBaby() ? (age < 13 ? "child" : "youngster") : c.job().orElse(null);
        if (home.isPresent()) {
            Colony t = home.get();
            about.add((job == null ? "No job" : capitalize(job)) + " of " + t.name() + " · " + who(c.isFemale(), age) + ", " + age);
            c.home().ifPresentOrElse(bed -> about.add("Sleeps in " + Homes.houseOf(t, bed).map(b -> "the " + b.name).orElse("a bed of their own")),
                    () -> about.add("Homeless: has no bed"));
            Rank rank = t.rank(player.getUUID());
            if (rank.atLeast(Rank.MEMBER)) {
                var s = t.standing(player.getUUID());
                about.add("The town sees you as " + s.word() + " (" + (s.score() > 0 ? "+" : "") + s.score() + ")");
            }
            c.record().filter(r -> r.condition != null).ifPresent(r -> about.add("Waiting until "
                    + dev.eiriksb.stad.agent.Leadership.conditionText(r.condition) + " to be the " + r.pendingJob));
            if (c.grudging()) {
                about.add("Working grudgingly today");
            }
            c.record().filter(r -> !r.plan.isEmpty()).ifPresent(r -> about.add("Today: " + r.plan.getFirst()));
            dev.eiriksb.stad.war.Envoys.of(c.getUUID()).ifPresent(e -> about.add("An envoy, here to talk peace"));
            if (!c.isBaby() && rank.atLeast(OrderKind.ASSIGN_JOB.minRank)) {
                if (job == null) {
                    for (String j : Jobs.all()) {
                        actions.add(new CitizenScreenPayload.Action(capitalize(j), "stad citizen job " + c.getUUID() + " " + j, false, AQUA));
                    }
                } else {
                    actions.add(new CitizenScreenPayload.Action("Stop working", "stad citizen job " + c.getUUID() + " none", false, YELLOW));
                }
            }
            if (rank.atLeast(OrderKind.ASSIGN_HOME.minRank)) {
                actions.add(new CitizenScreenPayload.Action("Give them the house I'm in", "stad adopt give " + c.getUUID(), false, GREEN));
            }
            if (rank.atLeast(OrderKind.DISMISS.minRank)) {
                actions.add(new CitizenScreenPayload.Action("Ask to leave town", "stad citizen dismiss " + c.getUUID(), false, RED));
            }
            Optional<Colony> mine = colonies.all().stream().filter(x -> x.rank(player.getUUID()).atLeast(OrderKind.INVITE.minRank)).findFirst();
            if (!c.isBaby() && !rank.atLeast(Rank.MEMBER) && mine.isPresent() && t.politics.poaching) {
                actions.add(new CitizenScreenPayload.Action("Invite to " + mine.get().name(), "stad citizen invite " + c.getUUID(), false, GOLD));
            }
        } else {
            about.add("A newcomer, looking for a town · " + who(c.isFemale(), age) + ", " + age);
            Optional<Colony> here = colonies.at(c.level().dimension(), c.blockPosition());
            if (here.isPresent() && here.get().rank(player.getUUID()).atLeast(OrderKind.INVITE.minRank)) {
                actions.add(new CitizenScreenPayload.Action("Invite to " + here.get().name(), "stad citizen invite " + c.getUUID(), false, GREEN));
            }
        }
        about.add(feeling(affinity));
        boolean builder = Jobs.BUILDER.equals(c.job().orElse(null));
        if (builder) {
            actions.add(0, new CitizenScreenPayload.Action("Plan a building", "Could you design ", true, GOLD));
        }
        List<CitizenScreenPayload.Line> lines = new ArrayList<>();
        for (ChatLine l : history) {
            lines.add(new CitizenScreenPayload.Line(l.fromPlayer(), l.fromPlayer() ? player.getGameProfile().getName() : c.getName().getString(), l.text()));
        }
        boolean canTalk = TheyWillTalkApi.get().canTalk(c) && TheyWillTalkApi.get().llm().ready();
        return new CitizenScreenPayload(c.getUUID(), c.getName().getString(), about, c.happiness(), Math.round(c.hunger()), Math.round(c.rest()),
                actions, lines, canTalk, builder ? "Tell " + c.getName().getString().split(" ")[0] + " what you'd like built..." : "");
    }

    /** What they think of the player, in words (from They Will Talk's affinity, -100 to 100). */
    static String feeling(int affinity) {
        if (affinity >= 50) {
            return "They're very fond of you";
        }
        if (affinity >= 20) {
            return "They like you";
        }
        if (affinity > -20) {
            return "They don't know what to make of you yet";
        }
        return affinity > -50 ? "They don't like you much" : "They can't stand you";
    }

    /** The player typed something in the screen: it's said to the citizen. */
    public static void say(ServerPlayer player, CitizenLinePayload p) {
        String text = p.text().strip();
        if (text.isEmpty() || text.length() > 256) {
            return;
        }
        if (!(player.serverLevel().getEntity(p.citizen()) instanceof CitizenEntity c) || c.distanceTo(player) > REACH) {
            player.displayClientMessage(Component.literal("They're too far away to hear you.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!TheyWillTalkApi.get().talk(c, player, text)) {
            player.displayClientMessage(Component.literal(c.getName().getString() + " can't talk right now.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** After a reply: it goes to the player's screen (and their own words too, when they spoke aloud). Any thread. */
    public static void reply(MinecraftServer server, TurnInfo turn, String reply) {
        server.execute(() -> {
            ServerPlayer player = turn.player() == null ? null : server.getPlayerList().getPlayer(turn.player());
            if (player == null || !canShow(player)) {
                return;
            }
            if ("voice".equals(turn.channel())) {
                PacketDistributor.sendToPlayer(player, new CitizenLinePayload(turn.villager(), true, turn.playerName(), turn.text()));
            }
            if (reply != null && !reply.isBlank()) {
                PacketDistributor.sendToPlayer(player, new CitizenLinePayload(turn.villager(), false, turn.villagerName(), reply));
            }
        });
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String who(boolean female, int age) {
        return age < 18 ? (female ? "girl" : "boy") : (female ? "woman" : "man");
    }
}
