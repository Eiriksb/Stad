package dev.eiriksb.stad.war;

import dev.eiriksb.stad.StadConfig;
import net.minecraft.nbt.CompoundTag;

import java.time.Instant;
import java.time.ZoneId;

/**
 * A town's stance towards other towns (PLAN.md §6.7): peaceful (the default) or open to war, whether rivals may lure
 * its unhappy citizens away, when it can be raided, and when it was last raided by mobs. Times are real-world
 * milliseconds unless said otherwise.
 */
public final class Politics {
    public enum Stance {
        PEACEFUL, OPEN;

        public String word() {
            return this == PEACEFUL ? "peaceful" : "open to war";
        }
    }

    public Stance stance = Stance.PEACEFUL;
    /** when it last went open to war (it can't go back to peaceful for a while) */
    public long openSince;
    public boolean poaching = StadConfig.POACHING_DEFAULT.get();
    /** raids by players can start from this hour (0-23, the server's local time)... */
    public int windowFrom = 18;
    /** ...until this one (exclusive; equal to {@code windowFrom}: any time) */
    public int windowTo = 22;
    /** the Minecraft day of the last mob raid */
    public long lastMobRaidDay;

    /** Whether it can be attacked by and declare war on other towns (the server may force it). */
    public boolean open() {
        return switch (StadConfig.PVP_RAIDS.get()) {
            case OFF -> false;
            case FORCED -> true;
            case OPT_IN -> stance == Stance.OPEN;
        };
    }

    /** Why the stance can't be changed to {@code to} now, or null. */
    public String cantChange(Stance to, boolean atWar, long now) {
        if (to == stance) {
            return "It already is " + to.word() + ".";
        }
        if (StadConfig.PVP_RAIDS.get() == StadConfig.PvpRaids.OFF && to == Stance.OPEN) {
            return "Wars between towns are switched off on this server.";
        }
        if (to == Stance.PEACEFUL) {
            if (atWar) {
                return "Not while there's a war on.";
            }
            long ready = openSince + StadConfig.STANCE_COOLDOWN_MINUTES.get() * 60_000L;
            if (now < ready) {
                return "A town that went open to war has to stay that way for a while: it can go back to peaceful in "
                        + Wars.duration(ready - now) + ".";
            }
        }
        return null;
    }

    public void set(Stance to, long now) {
        stance = to;
        if (to == Stance.OPEN) {
            openSince = now;
        }
    }

    /** Whether {@code now} is inside the raid window. */
    public boolean inWindow(long now) {
        if (windowFrom == windowTo) {
            return true;
        }
        int h = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).getHour();
        return windowFrom < windowTo ? h >= windowFrom && h < windowTo : h >= windowFrom || h < windowTo;
    }

    /** "18:00 to 22:00" or "any time" */
    public String window() {
        return windowFrom == windowTo ? "any time" : String.format("%02d:00 to %02d:00", windowFrom, windowTo);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Stance", stance.name());
        t.putLong("OpenSince", openSince);
        t.putBoolean("Poaching", poaching);
        t.putInt("WindowFrom", windowFrom);
        t.putInt("WindowTo", windowTo);
        t.putLong("LastMobRaidDay", lastMobRaidDay);
        return t;
    }

    public void load(CompoundTag t) {
        if (t.isEmpty()) {
            return;
        }
        try {
            stance = Stance.valueOf(t.getString("Stance"));
        } catch (IllegalArgumentException e) {
            stance = Stance.PEACEFUL;
        }
        openSince = t.getLong("OpenSince");
        poaching = t.getBoolean("Poaching");
        windowFrom = t.getInt("WindowFrom");
        windowTo = t.getInt("WindowTo");
        lastMobRaidDay = t.getLong("LastMobRaidDay");
    }
}
