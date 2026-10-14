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

    /** what each town has earned since it last told its members (not saved: a restart skips telling, not earning) */
    private static final Map<Integer, java.util.List<String>> untold = new java.util.HashMap<>();

    /** Something the town did (a child born, a raid beaten off): it's earned, and its members get it soon. */
    public static void feat(Colony colony, String id) {
        if (colony.feats.add(id)) {
            untold.computeIfAbsent(colony.id, k -> new java.util.ArrayList<>()).add(id);
            colony.changed();
        }
    }

    /**
     * Tells the town's members what it's earned, once (the game's own announcement would be once per member, to the
     * whole server): one line each, or one for the lot when it's a handful at once (an older town catching up).
     */
    private static void tell(MinecraftServer server, Colony colony) {
        java.util.List<String> ids = untold.remove(colony.id);
        if (ids == null) {
            return;
        }
        java.util.List<net.minecraft.advancements.DisplayInfo> shown = ids.stream()
                .map(id -> server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, id)))
                .filter(a -> a != null && a.value().parent().isPresent()).flatMap(a -> a.value().display().stream()).toList();
        if (shown.size() > 3) {
            Announce.members(server, colony, net.minecraft.network.chat.Component.literal(colony.name() + " has earned " + shown.size()
                    + " achievements (the Stad tab, L).").withStyle(net.minecraft.ChatFormatting.GOLD));
            return;
        }
        for (net.minecraft.advancements.DisplayInfo d : shown) {
            Announce.members(server, colony, net.minecraft.network.chat.Component.literal(colony.name() + " has earned ")
                    .append(d.getTitle().copy().withStyle(net.minecraft.ChatFormatting.GREEN)
                            .withStyle(st -> st.withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, d.getDescription()))))
                    .append(": " + d.getDescription().getString()).withStyle(net.minecraft.ChatFormatting.GOLD));
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
        tell(server, colony);
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
