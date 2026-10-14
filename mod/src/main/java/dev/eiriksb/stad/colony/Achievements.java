package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Fields;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The town's achievements, the game's own advancements in a Stad tab (data/stad/advancement): a town earns them (the
 * ones that come of how it's doing are worked out every few seconds, the rest as they happen) and keeps them
 * ({@link Colony#feats}), and every member gets them, then or when they're next on. Server thread.
 */
public final class Achievements {
    private Achievements() {}

    public static final String ROOT = "root";
    public static final String CHILD = "child";
    public static final String TRADE = "trade";
    public static final String RAID = "raid";
    public static final String WAR = "war";
    public static final String DIAMONDS = "diamonds";
    public static final String BEDROCK = "bedrock";
    public static final String RESEARCH = "research";

    /** The ones that come of how the town is doing, and what earns them. */
    static final Map<String, Predicate<Colony>> STATE = new LinkedHashMap<>();

    static {
        STATE.put(ROOT, c -> true);
        STATE.put("first_citizen", c -> c.population() >= 1);
        STATE.put("first_job", c -> c.citizens().stream().anyMatch(r -> r.job != null));
        STATE.put("first_building", c -> c.buildings().stream().anyMatch(b -> b.done() && !Fields.is(b) && !b.isRepair()));
        STATE.put("fed", c -> c.citizens().stream().anyMatch(r -> Jobs.FARMER.equals(r.job)) && c.citizens().stream().anyMatch(r -> Jobs.COOK.equals(r.job)));
        STATE.put("homes", c -> c.population() >= 5 && c.citizens().stream().allMatch(r -> r.home != null));
        STATE.put("village", c -> c.population() >= 10);
        STATE.put("town", c -> c.population() >= 25);
        STATE.put("city", c -> c.population() >= 50);
        STATE.put("road", c -> c.buildings().stream().anyMatch(b -> b.done() && b.is(BuildingDef.ROAD)));
        STATE.put("warehouse", c -> c.buildings().stream().anyMatch(b -> b.done() && b.is(BuildingDef.WAREHOUSE))
                && c.citizens().stream().anyMatch(r -> Jobs.COURIER.equals(r.job)));
        STATE.put("all_research", c -> c.research.done.containsAll(Research.TOPICS.stream().map(Research.Topic::id).toList()));
        STATE.put("land", c -> c.radius >= Growth.max(c));
        STATE.put("jobs", c -> c.citizens().stream().map(r -> r.job).filter(java.util.Objects::nonNull).distinct().count() >= 10);
    }

    /** Something the town did (a child born, a raid beaten off): it's earned, and its members get it soon. */
    public static void feat(Colony colony, String id) {
        if (colony.feats.add(id)) {
            colony.changed();
        }
    }

    /** Works out what the town's earned by how it's doing, and gives what it's earned to the members who are on. */
    public static void update(ServerLevel level, Colony colony) {
        STATE.forEach((id, earned) -> {
            if (!colony.feats.contains(id) && earned.test(colony)) {
                feat(colony, id);
            }
        });
        MinecraftServer server = level.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (colony.rank(p.getUUID()).atLeast(Rank.MEMBER)) {
                give(server, p, colony);
            }
        }
    }

    private static void give(MinecraftServer server, ServerPlayer player, Colony colony) {
        for (String id : colony.feats) {
            AdvancementHolder a = server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, id));
            if (a != null && !player.getAdvancements().getOrStartProgress(a).isDone()) {
                player.getAdvancements().award(a, "done");
            }
        }
    }
}
