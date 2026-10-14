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
    public static final String SMITH = "smith";
    public static final String HEALER = "healer";
    public static final String MERCHANT = "merchant";
    public static final String TEACHER = "teacher";
    public static final String CARPENTER = "carpenter";
    public static final String MASON = "mason";
    public static final String WEAVER = "weaver";
    public static final String BEEKEEPER = "beekeeper";
    private static final List<String> ALL = List.of(BUILDER, FARMER, LUMBERJACK, COURIER, GUARD, COOK, FISHER, SHEPHERD, MINER, SMITH,
            HEALER, MERCHANT, TEACHER, CARPENTER, MASON, WEAVER, BEEKEEPER);
    private static final Map<String, String> WHAT = Map.ofEntries(Map.entry(
BUILDER, "you build what the town's leaders plan, with materials from the town hall"),
            Map.entry(FARMER, "you make the town's fields (till the ground round a water hole), sow them, and harvest and replant the crops"),
            Map.entry(LUMBERJACK, "you fell the trees on the town's land and replant them, and plant a grove when there are none"),
            Map.entry(COURIER, "you carry what the town needs from the warehouse to the town hall and the builder"),
            Map.entry(GUARD, "you keep the town safe: you patrol by day and fight off monsters and raiders, with a sword from the town's stores"),
            Map.entry(COOK, "you cook for the town: bread from the farmers' wheat, and the meat, fish and potatoes, at a furnace, smoker or campfire"),
            Map.entry(FISHER, "you fish from the shore of the water on the town's land"),
            Map.entry(SHEPHERD, "you look after the town's animals: you bring them in from nearby, shear the sheep, feed them so they breed, and butcher the extra ones"),
            Map.entry(MINER, "you dig the town's mine deeper, layer by layer, for stone, coal and ore"),
            Map.entry(SMITH, "you mend the town's worn tools at the anvil, make the tools and swords it's short of, and smelt the miner's ore"),
            Map.entry(HEALER, "you look after the sick and the wounded: you go round to them and treat them until they're well"),
            Map.entry(MERCHANT, "you keep the town's market: you sell what the town has spare and buy what it needs, for emeralds"),
            Map.entry(TEACHER, "you teach the town's children at the school, so they grow up quick to learn any trade"),
            Map.entry(CARPENTER, "you work at the sawmill: you saw the lumberjack's logs into planks and sticks, and make the doors, fences, stairs and chests the builders ask for"),
            Map.entry(MASON, "you work at the stoneworks: you burn cobblestone into stone, cut it into stone bricks, slabs and stairs, and make glass from sand"),
            Map.entry(WEAVER, "you work at the loom house: you make the shepherd's wool into beds for new homes, and carpets"),
            Map.entry(BEEKEEPER, "you keep the town's bees at the apiary: you bring bees to the hives, plant flowers for them, and take the honey and honeycomb"));

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
