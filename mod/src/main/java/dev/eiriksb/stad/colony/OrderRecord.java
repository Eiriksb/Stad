package dev.eiriksb.stad.colony;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** One order as it was logged: who gave it, to whom, and what came of it. */
public record OrderRecord(long time, UUID player, String playerName, UUID citizen, String citizenName, OrderKind kind,
                          Order.Outcome outcome, String reason) {
    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Time", time);
        t.putUUID("Player", player);
        t.putString("PlayerName", playerName);
        if (citizen != null) {
            t.putUUID("Citizen", citizen);
        }
        t.putString("CitizenName", citizenName == null ? "" : citizenName);
        t.putString("Kind", kind.name());
        t.putString("Outcome", outcome.name());
        t.putString("Reason", reason == null ? "" : reason);
        return t;
    }

    static OrderRecord load(CompoundTag t) {
        return new OrderRecord(t.getLong("Time"), t.getUUID("Player"), t.getString("PlayerName"),
                t.hasUUID("Citizen") ? t.getUUID("Citizen") : null, t.getString("CitizenName"), OrderKind.valueOf(t.getString("Kind")),
                Order.Outcome.valueOf(t.getString("Outcome")), t.getString("Reason"));
    }
}
