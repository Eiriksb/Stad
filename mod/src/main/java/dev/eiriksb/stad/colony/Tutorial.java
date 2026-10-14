package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.net.GuidePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The getting-started guide in the corner of the screen: each member is shown the next of their town's steps
 * ({@link Guide#steps}), the town they're in or else the one they own, and told when they've done one. Server thread.
 */
public final class Tutorial {
    private Tutorial() {}

    /** what each player was last sent */
    private static final Map<UUID, Sent> sent = new HashMap<>();

    private record Sent(int colony, int next, GuidePayload payload) {}

    /** The town whose guide a player sees: the one they're in, if they're one of its members, else the one they lead. */
    static Colony townOf(MinecraftServer server, ServerPlayer player) {
        ColonyManager m = ColonyManager.get(server);
        Colony here = m.at(player.level().dimension(), player.blockPosition()).orElse(null);
        if (here != null && here.rank(player.getUUID()).atLeast(Rank.MEMBER)) {
            return here;
        }
        Colony best = null;
        for (Colony c : m.all()) {
            Rank r = c.rank(player.getUUID());
            if (r.atLeast(Rank.MEMBER) && (best == null || r.atLeast(Rank.OWNER) && !best.rank(player.getUUID()).atLeast(Rank.OWNER))) {
                best = c;
            }
        }
        return best;
    }

    public static void update(MinecraftServer server) {
        sent.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.connection == null || !p.connection.hasChannel(GuidePayload.TYPE)) {
                continue; // a client without Stad (or a test's stand-in)
            }
            Colony c = townOf(server, p);
            ServerLevel level = c == null ? null : server.getLevel(c.dimension);
            Sent last = sent.get(p.getUUID());
            Sent now;
            if (level == null) {
                now = new Sent(0, -1, GuidePayload.NONE);
            } else {
                List<Guide.Step> steps = Guide.steps(level, c);
                int next = 0;
                while (next < steps.size() && steps.get(next).done()) {
                    next++;
                }
                int done = (int) steps.stream().filter(Guide.Step::done).count();
                String just = last != null && last.colony == c.id && last.next >= 0 && last.next < next ? steps.get(last.next).text() : "";
                now = new Sent(c.id, next, new GuidePayload(c.name(), done, steps.size(), next < steps.size() ? steps.get(next).text() : "", just));
            }
            if (last == null || !last.payload.equals(now.payload)) {
                sent.put(p.getUUID(), now);
                PacketDistributor.sendToPlayer(p, now.payload);
            }
        }
    }
}
