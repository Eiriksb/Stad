package dev.eiriksb.stad.colony;

import java.util.List;
import java.util.Map;

/** The jobs citizens can have (PLAN.md §6.2). */
public final class Jobs {
    public static final String BUILDER = "builder";
    public static final String FARMER = "farmer";
    public static final String LUMBERJACK = "lumberjack";
    public static final String COURIER = "courier";
    public static final String GUARD = "guard";
    public static final String COOK = "cook";
    public static final String FISHER = "fisher";
    public static final String SHEPHERD = "shepherd";
    public static final String MINER = "miner";
    private static final List<String> ALL = List.of(BUILDER, FARMER, LUMBERJACK, COURIER, GUARD, COOK, FISHER, SHEPHERD, MINER);
    private static final Map<String, String> WHAT = Map.ofEntries(Map.entry(
BUILDER, "you build what the town's leaders plan, with materials from the town hall"),
            Map.entry(FARMER, "you harvest and replant the crops on the town's farmland"),
            Map.entry(LUMBERJACK, "you fell the trees on the town's land and replant them"),
            Map.entry(COURIER, "you carry what the town needs from the warehouse to the town hall and the builder"),
            Map.entry(GUARD, "you keep the town safe: you patrol by day and fight off monsters and raiders, with a sword from the town's stores"),
            Map.entry(COOK, "you cook for the town: bread from the farmers' wheat, and the meat, fish and potatoes, at a furnace, smoker or campfire"),
            Map.entry(FISHER, "you fish from the shore of the water on the town's land"),
            Map.entry(SHEPHERD, "you look after the town's animals: you shear the sheep, feed them so they breed, and butcher the extra ones"),
            Map.entry(MINER, "you dig the town's mine deeper, layer by layer, for stone, coal and ore"));

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
