package dev.eiriksb.stad.colony;

import java.util.List;
import java.util.Map;

/** The jobs citizens can have (PLAN.md §6.2). */
public final class Jobs {
    public static final String BUILDER = "builder";
    public static final String FARMER = "farmer";
    public static final String LUMBERJACK = "lumberjack";
    public static final String COURIER = "courier";
    private static final List<String> ALL = List.of(BUILDER, FARMER, LUMBERJACK, COURIER);
    private static final Map<String, String> WHAT = Map.of(
            BUILDER, "you build what the town's leaders plan, with materials from the town hall",
            FARMER, "you harvest and replant the crops on the town's farmland",
            LUMBERJACK, "you fell the trees on the town's land and replant them",
            COURIER, "you carry what the town needs from the warehouse to the town hall and the builder");

    private Jobs() {}

    public static boolean exists(String job) {
        return ALL.contains(job);
    }

    public static List<String> all() {
        return ALL;
    }

    /** What the job is, for prompts: "you harvest and replant ...". */
    public static String describe(String job) {
        return WHAT.getOrDefault(job, "");
    }
}
