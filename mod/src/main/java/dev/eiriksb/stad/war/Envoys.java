package dev.eiriksb.stad.war;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.Movement;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Envoys (PLAN.md §6.7): a citizen sent to the enemy's town hall to talk peace. They wait there to be heard; the other
 * side's officers talk to them (in character, through They Will Talk) and accept or send them home. Not saved: after a
 * restart they're just citizens again, and walk home.
 */
public final class Envoys {
    private Envoys() {}

    /** how long an envoy waits to be heard (game ticks) */
    public static final int PATIENCE = 12000;

    /**
     * @param from the town that sent them (it has offered peace)
     * @param to   the town they're talking to
     */
    public record Envoy(UUID citizen, int war, int from, int to, BlockPos post, long until, String sentBy) {}

    private static final Map<UUID, Envoy> envoys = new ConcurrentHashMap<>();

    public static boolean isEnvoy(UUID citizen) {
        return envoys.containsKey(citizen);
    }

    public static Optional<Envoy> of(UUID citizen) {
        return Optional.ofNullable(envoys.get(citizen));
    }

    public static Optional<BlockPos> post(UUID citizen) {
        return of(citizen).map(Envoy::post);
    }

    /** {@code player} sends {@code citizen} to the other town of {@code war} (an order: they may refuse). Returns why not, or null. */
    public static String send(ServerPlayer player, CitizenEntity citizen, War war, long now) {
        MinecraftServer server = player.server;
        Colony from = citizen.colony().orElse(null);
        if (from == null || !war.on() || !war.involves(from.id)) {
            return citizen.getName().getString() + " isn't from a town in that war.";
        }
        Colony to = ColonyManager.get(server).byId(war.other(from.id)).orElse(null);
        if (to == null) {
            return "That war is over.";
        }
        if (!from.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers of " + from.name() + " can send envoys.";
        }
        if (!to.dimension.equals(citizen.level().dimension())) {
            return to.name() + " is too far away (another dimension).";
        }
        if (isEnvoy(citizen.getUUID()) || citizen.isBaby()) {
            return citizen.getName().getString() + " can't go.";
        }
        Order.Result o = OrderService.issue(new Order(player, OrderKind.ENVOY, citizen, to.name()));
        if (!o.done()) {
            return o.reason();
        }
        ServerLevel level = (ServerLevel) citizen.level();
        envoys.put(citizen.getUUID(), new Envoy(citizen.getUUID(), war.id, from.id, to.id, to.center, level.getGameTime() + PATIENCE,
                player.getGameProfile().getName()));
        Movement.teleportNear(citizen, to.center);
        Wars.offerPeace(player, war, from, now);
        Announce.members(server, to, Component.literal(citizen.getName().getString() + " has come from " + from.name()
                + " as an envoy, to talk peace. They're waiting at your town hall.").withStyle(ChatFormatting.AQUA));
        return null;
    }

    /** Sends an envoy home. */
    public static void home(MinecraftServer server, UUID citizen) {
        Envoy e = envoys.remove(citizen);
        if (e == null) {
            return;
        }
        ColonyManager.get(server).byId(e.from).ifPresent(from -> {
            ServerLevel level = server.getLevel(from.dimension);
            if (level != null && level.getEntity(citizen) instanceof CitizenEntity c) {
                Movement.teleportNear(c, from.center);
            }
        });
    }

    /** The envoy's war ended: they go home in a little while (after a word with whoever made the peace). */
    static void warOver(MinecraftServer server, int war) {
        long soon = server.overworld().getGameTime() + 200;
        envoys.replaceAll((id, e) -> e.war == war ? new Envoy(e.citizen, e.war, e.from, e.to, e.post, Math.min(e.until, soon), e.sentBy) : e);
    }

    /** Every few seconds: envoys who've waited long enough go home. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        envoys.values().stream().filter(e -> now > e.until).map(Envoy::citizen).toList().forEach(id -> home(server, id));
    }

    /** An officer of the town an envoy came to answers: yes to peace, or go home. Returns what to tell them. */
    public static String answer(ServerPlayer player, Envoy e, boolean accept, long now) {
        MinecraftServer server = player.server;
        War war = ColonyManager.get(server).wars().stream().filter(w -> w.id == e.war).findFirst().orElse(null);
        Colony to = ColonyManager.get(server).byId(e.to).orElse(null);
        if (war == null || to == null || !war.on()) {
            return "The war is already over.";
        }
        if (!to.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers of " + to.name() + " can answer an envoy.";
        }
        if (accept) {
            Wars.Result r = Wars.offerPeace(player, war, to, now);
            return r.problem();
        }
        home(server, e.citizen);
        ColonyManager.get(server).byId(e.from).ifPresent(from -> Announce.members(server, from, Component.literal(to.name()
                + " sent your envoy home without peace.").withStyle(ChatFormatting.RED)));
        return null;
    }

    public static void clearAll() {
        envoys.clear();
    }
}
