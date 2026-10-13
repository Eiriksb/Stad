package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Citizens falling ill and getting better (PLAN.md §6.9). Ill, they don't work, are less happy and stay in bed; it
 * passes by itself in {@link #DAYS} days, or after {@link #TREATMENTS} of the healer's treatments. Server thread.
 */
public final class Illness {
    private Illness() {}

    public static final int DAYS = 3;
    public static final int TREATMENTS = 2;
    private static final List<String> KINDS = List.of("a cold", "a fever", "a bad cough", "a stomach ache", "the flu");

    /** Once a day, in the morning: the ill get over it in time, and the well may fall ill. */
    public static void morning(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        double chance = StadConfig.ILLNESS_CHANCE.get();
        for (CitizenRecord r : colony.citizens()) {
            if (r.illness != null) {
                if (day - r.illSince >= DAYS) {
                    recover(level, colony, r);
                }
                continue;
            }
            if (level.getGameTime() - r.joined < 2 * 24000L) {
                continue; // newcomers arrive healthy
            }
            double p = chance * (r.wentHungry ? 2 : 1) * (r.home == null ? 2 : 1);
            if (p > 0 && level.random.nextDouble() < p) {
                fallIll(level, colony, r, KINDS.get(level.random.nextInt(KINDS.size())));
            }
        }
    }

    public static void fallIll(ServerLevel level, Colony colony, CitizenRecord r, String what) {
        r.illness = what;
        r.illSince = level.getDayTime() / 24000;
        r.treatments = 0;
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(r.name + " has fallen ill with " + what + ".").withStyle(ChatFormatting.YELLOW));
    }

    public static void recover(ServerLevel level, Colony colony, CitizenRecord r) {
        r.illness = null;
        r.treatments = 0;
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(r.name + " is well again.").withStyle(ChatFormatting.GREEN));
    }

    /** One of the healer's treatments; returns whether that cured them. */
    public static boolean treat(ServerLevel level, Colony colony, CitizenRecord r) {
        if (r.illness == null) {
            return false;
        }
        if (++r.treatments >= TREATMENTS) {
            recover(level, colony, r);
            return true;
        }
        colony.changed();
        return false;
    }
}
