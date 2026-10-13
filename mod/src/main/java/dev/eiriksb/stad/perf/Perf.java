package dev.eiriksb.stad.perf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where Stad's share of the server tick goes, for {@code /stad perf}: a few counters fed with {@link System#nanoTime()}
 * around the citizens' ticks and the town upkeep. Cheap enough to leave on. Server thread only.
 */
public final class Perf {
    /** Seconds of history the averages cover. */
    static final int WINDOW_TICKS = 20 * 10;

    private static final Map<String, Section> sections = new LinkedHashMap<>();
    private static long ticks;

    private Perf() {}

    private static final class Section {
        final long[] nanos = new long[WINDOW_TICKS];
        final int[] calls = new int[WINDOW_TICKS];
    }

    public static long start() {
        return System.nanoTime();
    }

    /** Adds the time since {@code start} to {@code section} for this tick. */
    public static void end(String section, long start) {
        Section s = sections.computeIfAbsent(section, k -> new Section());
        int i = (int) (ticks % WINDOW_TICKS);
        s.nanos[i] += System.nanoTime() - start;
        s.calls[i]++;
    }

    /** At the start of each server tick: forgets what happened {@link #WINDOW_TICKS} ago. */
    public static void nextTick() {
        ticks++;
        int i = (int) (ticks % WINDOW_TICKS);
        for (Section s : sections.values()) {
            s.nanos[i] = 0;
            s.calls[i] = 0;
        }
    }

    /** One section's average milliseconds per tick, and calls per tick, over the window. */
    public record Average(String section, double msPerTick, double callsPerTick, double maxMs) {}

    public static java.util.List<Average> averages() {
        int n = (int) Math.min(ticks, WINDOW_TICKS);
        java.util.List<Average> out = new java.util.ArrayList<>();
        if (n == 0) {
            return out;
        }
        for (Map.Entry<String, Section> e : sections.entrySet()) {
            long total = 0;
            long calls = 0;
            long max = 0;
            for (int k = 0; k < WINDOW_TICKS; k++) {
                total += e.getValue().nanos[k];
                calls += e.getValue().calls[k];
                max = Math.max(max, e.getValue().nanos[k]);
            }
            out.add(new Average(e.getKey(), total / 1e6 / n, (double) calls / n, max / 1e6));
        }
        return out;
    }

    public static void reset() {
        sections.clear();
    }
}
