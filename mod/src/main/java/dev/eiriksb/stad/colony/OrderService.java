package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The one way orders reach citizens (PLAN.md §6.6). Checks the player's rank, then (from M5) whether the citizen is
 * willing, carries the order out and logs it in the colony. Server thread.
 */
public final class OrderService {
    private OrderService() {}

    public static Order.Result issue(Order order) {
        ServerPlayer player = order.player();
        CitizenEntity citizen = order.citizen();
        ColonyManager colonies = ColonyManager.get(player.server);
        Optional<Colony> found = switch (order.kind()) {
            case INVITE -> colonies.at(citizen.level().dimension(), citizen.blockPosition());
            case DISMISS -> colonies.ofCitizen(citizen.getUUID());
        };
        if (found.isEmpty()) {
            return new Order.Result(Order.Outcome.INVALID, order.kind() == OrderKind.INVITE
                    ? citizen.getName().getString() + " isn't in a town." : citizen.getName().getString() + " doesn't live in a town.");
        }
        Colony colony = found.get();
        Order.Result result = decide(order, colony);
        if (result.done()) {
            carryOut(order, colony, colonies);
        }
        colony.log(new OrderRecord(citizen.level().getGameTime(), player.getUUID(), player.getGameProfile().getName(),
                citizen.getUUID(), citizen.getName().getString(), order.kind(), result.outcome(), result.reason()));
        return result;
    }

    private static Order.Result decide(Order order, Colony colony) {
        Rank rank = colony.rank(order.player().getUUID());
        String who = order.citizen().getName().getString();
        if (!rank.atLeast(order.kind().minRank)) {
            return new Order.Result(Order.Outcome.NOT_ALLOWED, "Only " + (order.kind().minRank == Rank.OWNER ? "the founder" : "officers")
                    + " of " + colony.name() + " can do that.");
        }
        switch (order.kind()) {
            case INVITE -> {
                if (colony.hasCitizen(order.citizen().getUUID())) {
                    return new Order.Result(Order.Outcome.INVALID, who + " already lives in " + colony.name() + ".");
                }
                Optional<Colony> other = ColonyManager.get(order.player().server).ofCitizen(order.citizen().getUUID());
                if (other.isPresent()) {
                    return new Order.Result(Order.Outcome.INVALID, who + " lives in " + other.get().name() + ".");
                }
            }
            case DISMISS -> {
                if (!colony.hasCitizen(order.citizen().getUUID())) {
                    return new Order.Result(Order.Outcome.INVALID, who + " doesn't live in " + colony.name() + ".");
                }
            }
        }
        // M5: the citizen's willingness (standing, affinity, happiness, persona, cost) decides here.
        return new Order.Result(Order.Outcome.ACCEPTED, null);
    }

    private static void carryOut(Order order, Colony colony, ColonyManager colonies) {
        switch (order.kind()) {
            case INVITE -> colonies.join(colony, order.citizen());
            case DISMISS -> colonies.leave(colony, order.citizen());
        }
    }
}
