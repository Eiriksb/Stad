package dev.eiriksb.stad.colony;

import java.util.List;

/** The jobs citizens can have (PLAN.md §6.2). More come in M4. */
public final class Jobs {
    public static final String BUILDER = "builder";
    private static final List<String> ALL = List.of(BUILDER);

    private Jobs() {}

    public static boolean exists(String job) {
        return ALL.contains(job);
    }

    public static List<String> all() {
        return ALL;
    }
}
