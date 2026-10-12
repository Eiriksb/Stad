package dev.eiriksb.stad.colony;

import net.minecraft.server.level.ServerPlayer;

/**
 * An order from a player to a citizen. Every order, from chat, the GUI or a command, goes through
 * {@link OrderService}: first the player's rank is checked, then (from M5) whether the citizen is willing.
 */
public record Order(ServerPlayer player, OrderKind kind, dev.eiriksb.stad.citizen.CitizenEntity citizen, String arg) {
    public Order(ServerPlayer player, OrderKind kind, dev.eiriksb.stad.citizen.CitizenEntity citizen) {
        this(player, kind, citizen, null);
    }

    public enum Outcome {
        /** done gladly */
        ACCEPTED,
        /** done, with complaints (M5) */
        GRUDGING,
        /** the citizen said no (M5) */
        REFUSED,
        /** the player isn't allowed to give this order */
        NOT_ALLOWED,
        /** it can't be done (not in a town, already a member...) */
        INVALID
    }

    public record Result(Outcome outcome, String reason) {
        public boolean done() {
            return outcome == Outcome.ACCEPTED || outcome == Outcome.GRUDGING;
        }
    }
}
