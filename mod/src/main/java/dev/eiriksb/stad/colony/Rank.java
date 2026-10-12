package dev.eiriksb.stad.colony;

import java.util.Locale;

/** A player's place in a colony (PLAN.md §6.6, "Multiplayer"). Higher ranks may give more kinds of orders. */
public enum Rank {
    VISITOR, MEMBER, OFFICER, OWNER;

    public boolean atLeast(Rank other) {
        return ordinal() >= other.ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "the town's founder", "an officer", ... for prompts and chat. */
    public String describe() {
        return switch (this) {
            case OWNER -> "the town's founder and leader";
            case OFFICER -> "an officer of the town";
            case MEMBER -> "a member of the town";
            case VISITOR -> "a visitor, not a member of the town";
        };
    }

    public static Rank byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VISITOR;
        }
    }
}
