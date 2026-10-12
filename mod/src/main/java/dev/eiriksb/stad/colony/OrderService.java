package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.agent.Leadership;
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
            case DISMISS, ASSIGN_JOB, ASSIGN_HOME -> colonies.ofCitizen(citizen.getUUID());
            case PLAN_BUILDING, ADOPT_BUILDING -> throw new IllegalArgumentException("buildings go through Planning and Adoption");
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
        react(order, colony, result);
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
            case DISMISS, ASSIGN_JOB -> {
                if (!colony.hasCitizen(order.citizen().getUUID())) {
                    return new Order.Result(Order.Outcome.INVALID, who + " doesn't live in " + colony.name() + ".");
                }
                if (order.kind() == OrderKind.ASSIGN_JOB && order.arg() != null && !Jobs.exists(order.arg())) {
                    return new Order.Result(Order.Outcome.INVALID, "There's no such job as " + order.arg() + ".");
                }
            }
            case ASSIGN_HOME -> {
                Building home = home(order, colony);
                if (home == null) {
                    return new Order.Result(Order.Outcome.INVALID, "There's no such house in " + colony.name() + ".");
                }
                if (Homes.inside(order.citizen().home().orElse(null), home)) {
                    return new Order.Result(Order.Outcome.INVALID, who + " already lives in the " + home.name + ".");
                }
                if (Homes.freeBed((net.minecraft.server.level.ServerLevel) order.citizen().level(), colony, home, order.citizen().getUUID()) == null) {
                    return new Order.Result(Order.Outcome.INVALID, "There's no free bed in the " + home.name + ".");
                }
            }
            case PLAN_BUILDING, ADOPT_BUILDING -> {
            }
        }
        if (order.kind() == OrderKind.ASSIGN_JOB && order.arg() != null && order.citizen().isBaby()) {
            return new Order.Result(Order.Outcome.INVALID, who + " is a child; children don't work.");
        }
        if (order.kind() == OrderKind.DISMISS) {
            colony.changeStanding(order.player().getUUID(), order.player().getGameProfile().getName(), -2, "you sent " + who + " away",
                    order.citizen().level().getGameTime());
            return new Order.Result(Order.Outcome.ACCEPTED, null);
        }
        // Whether they're willing: standing, how they feel about the player, how they're doing, who they are.
        Leadership.Willingness w = Leadership.willingness(order.citizen(), order.player(), colony, order.kind(), order.arg());
        if (w.outcome() == Order.Outcome.REFUSED) {
            if (w.condition() != null && order.kind() == OrderKind.ASSIGN_JOB) {
                colony.citizen(order.citizen().getUUID()).ifPresent(r -> {
                    r.condition = w.condition();
                    r.pendingJob = order.arg();
                    r.pendingFrom = order.player().getUUID();
                });
                return new Order.Result(Order.Outcome.REFUSED, who + " won't: " + w.reason() + ". They'll do it once "
                        + Leadership.conditionText(w.condition()) + ".");
            }
            return new Order.Result(Order.Outcome.REFUSED, who + " won't: " + w.reason() + ".");
        }
        if (w.outcome() == Order.Outcome.GRUDGING) {
            return new Order.Result(Order.Outcome.GRUDGING, w.reason());
        }
        return new Order.Result(Order.Outcome.ACCEPTED, null);
    }

    /** The citizen says what they think of it, in their own words (when the language model is running). */
    private static void react(Order order, Colony colony, Order.Result result) {
        String what = Leadership.describe(order.kind(), order.arg(), colony);
        String player = order.player().getGameProfile().getName();
        String instruction = switch (result.outcome()) {
            case REFUSED -> "(" + player + " just asked you to " + what + ". You refuse: " + result.reason().replaceFirst("^[^:]*: ", "")
                    .replace("they're", "you're").replace("they ", "you ").replace("their", "your") + " Say so in your own words, in a sentence or two.)";
            case GRUDGING -> "(" + player + " just asked you to " + what + ". You agree, but grudgingly"
                    + (result.reason() == null ? "" : ": " + result.reason().replace("they're", "you're").replace("they ", "you ").replace("their", "your"))
                    + ". Say so, in a sentence.)";
            case ACCEPTED -> order.kind() == OrderKind.ASSIGN_JOB || order.kind() == OrderKind.INVITE || order.kind() == OrderKind.ASSIGN_HOME
                    ? "(" + player + " just asked you to " + what + ". You're glad to. Say so, in a sentence.)" : null;
            default -> null;
        };
        if (instruction != null) {
            dev.eiriksb.theywilltalk.api.TheyWillTalkApi.get().speak(order.citizen(), order.player(), instruction);
        }
        if (result.outcome() == Order.Outcome.GRUDGING) {
            colony.citizen(order.citizen().getUUID()).ifPresent(r -> r.grudgingUntil = order.citizen().level().getGameTime() + 24000);
        }
    }

    private static void carryOut(Order order, Colony colony, ColonyManager colonies) {
        switch (order.kind()) {
            case INVITE -> colonies.join(colony, order.citizen());
            case DISMISS -> colonies.leave(colony, order.citizen());
            case ASSIGN_JOB -> colony.citizen(order.citizen().getUUID()).ifPresent(r -> {
                r.job = order.arg();
                colony.buildings().stream().filter(b -> order.citizen().getUUID().equals(b.builder) && !b.done()).forEach(b -> b.builder = null);
                colony.changed();
            });
            case ASSIGN_HOME -> colony.citizen(order.citizen().getUUID()).ifPresent(r -> {
                Building home = home(order, colony);
                r.home = Homes.freeBed((net.minecraft.server.level.ServerLevel) order.citizen().level(), colony, home, r.uuid);
                colony.changed();
            });
            case PLAN_BUILDING, ADOPT_BUILDING -> {
            }
        }
    }

    private static Building home(Order order, Colony colony) {
        try {
            return colony.building(Integer.parseInt(order.arg())).filter(Building::done).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
