package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.war.Envoys;
import dev.eiriksb.theywilltalk.api.BeforeReply;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Talking to an envoy (PLAN.md §6.7): they make the case for peace in character; an officer of the town they came to
 * can say yes ("we accept, peace it is") or send them home ("no peace, go home").
 */
public final class EnvoyTalk {
    private EnvoyTalk() {}

    static final Pattern ACCEPT = Pattern.compile("\\b(we accept|i accept|accepted|agreed|i agree|we agree|peace it is|it's a deal|deal|"
            + "let's make peace|we'll make peace|let there be peace|yes,? (to )?peace|we want peace too)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern REFUSE = Pattern.compile("\\b(no peace|never|we refuse|i refuse|refused|go home|get out|begone|reject|no deal|"
            + "not interested)\\b", Pattern.CASE_INSENSITIVE);

    /** The context for the reply, or null when the citizen isn't an envoy. Background thread. */
    public static BeforeReply beforeReply(TurnInfo turn) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || turn.kind() != Stad.CITIZEN || turn.player() == null || !Envoys.isEnvoy(turn.villager())) {
            return null;
        }
        try {
            List<String> lines = server.submit(() -> handle(server, turn)).get(2, TimeUnit.SECONDS);
            return lines == null ? null : BeforeReply.context(lines);
        } catch (Exception e) {
            Stad.LOGGER.debug("EnvoyTalk: {}", e.toString());
            return null;
        }
    }

    private static List<String> handle(MinecraftServer server, TurnInfo turn) {
        Envoys.Envoy e = Envoys.of(turn.villager()).orElse(null);
        ServerPlayer player = server.getPlayerList().getPlayer(turn.player());
        if (e == null || player == null) {
            return null;
        }
        ColonyManager m = ColonyManager.get(server);
        Colony from = m.byId(e.from()).orElse(null), to = m.byId(e.to()).orElse(null);
        if (from == null || to == null) {
            return null;
        }
        String who = player.getGameProfile().getName();
        String role = "You're an envoy from " + from.name() + ", sent by " + e.sentBy() + " to " + to.name() + "'s town hall to make peace.";
        if (!to.rank(player.getUUID()).atLeast(Rank.MEMBER)) {
            return List.of(role, who + " isn't one of " + to.name() + "'s people; you're waiting for one of its leaders.");
        }
        boolean officer = to.rank(player.getUUID()).atLeast(Rank.OFFICER);
        if (dev.eiriksb.stad.war.Wars.between(server, from.id, to.id).isEmpty()) {
            return List.of("You came from " + from.name() + " as an envoy, and there's peace now; you'll head home shortly. Say goodbye kindly.");
        }
        long now = System.currentTimeMillis();
        if (officer && ACCEPT.matcher(turn.text()).find() && !REFUSE.matcher(turn.text()).find()) {
            String problem = Envoys.answer(player, e, true, now);
            return List.of(role, problem == null ? who + " just agreed to peace between " + to.name() + " and " + from.name()
                    + ". The war is over. Thank them, warmly and with relief, in a sentence or two."
                    : "You couldn't settle it: " + problem);
        }
        if (officer && REFUSE.matcher(turn.text()).find()) {
            Envoys.answer(player, e, false, now);
            return List.of(role, who + " refused peace and sent you home. Say so with dignity, in a sentence, before you leave.");
        }
        return List.of(role, who + " is " + to.rank(player.getUUID()).describe() + " of " + to.name() + (officer
                ? ". Make the case for peace in your own words; they can accept it or send you home." : ", not one of its leaders,"
                + " so they can't agree to peace; ask for their leaders."));
    }
}
