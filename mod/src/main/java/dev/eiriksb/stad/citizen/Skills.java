package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.colony.CitizenRecord;

/**
 * How good a citizen is at each job they've done (PLAN.md §6.9): points from the work they do, in ten levels from
 * novice to master, each making the work 2% quicker. Schooling as a child makes them learn faster.
 */
public final class Skills {
    private Skills() {}

    public static final int MAX = 10;
    public static final int MAX_SCHOOLING = 10;

    /** Points to reach a level: 250 n² (a unit of work is worth 10 points, more with schooling). */
    static int points(int level) {
        return 250 * level * level;
    }

    public static int level(CitizenRecord r, String job) {
        if (job == null) {
            return 0;
        }
        int p = r.skills.getOrDefault(job, 0), l = 0;
        while (l < MAX && p >= points(l + 1)) {
            l++;
        }
        return l;
    }

    /** They did {@code amount} units of work at {@code job}; returns whether that made them a new title. */
    public static boolean learn(CitizenRecord r, String job, int amount) {
        if (job == null || amount <= 0) {
            return false;
        }
        String before = title(level(r, job));
        r.skills.merge(job, amount * (10 + Math.min(MAX_SCHOOLING, r.schooling)), Integer::sum);
        return !title(level(r, job)).equals(before);
    }

    /** "novice", "apprentice", "journeyman", "skilled", "master" */
    public static String title(int level) {
        if (level <= 0) {
            return "novice";
        }
        if (level <= 3) {
            return "apprentice";
        }
        if (level <= 6) {
            return "journeyman";
        }
        return level < MAX ? "skilled" : "master";
    }

    /** How long work takes at a level, as a share of a novice's time. */
    public static double speed(int level) {
        return 1 - 0.02 * Math.max(0, Math.min(MAX, level));
    }

    /** "a journeyman miner" (null with no job) */
    public static String describe(CitizenRecord r) {
        if (r.job == null) {
            return null;
        }
        String t = title(level(r, r.job));
        return ("aeiou".indexOf(t.charAt(0)) >= 0 ? "an " : "a ") + t + " " + r.job;
    }
}
