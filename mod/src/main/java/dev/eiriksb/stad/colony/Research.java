package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a town has learned (PLAN.md §6.14). A new town can build its homes, the builder's hut, the warehouse, the
 * farmhouse and fields, the lumber camp and groves, the kitchen, roads and the university from the start; the rest
 * its researchers work out at the university, a topic at a time, each taking books and materials from the stores and
 * some days' work: buildings to unlock (and so their jobs), and upgrades (quicker work, more land). The town's officers
 * choose what's next at the town hall.
 */
public final class Research {
    private Research() {}

    /**
     * A topic: what it needs researched first, what it takes from the stores, how much work it is (a researcher does a
     * piece every half minute or so), the kinds of building it unlocks, and its upgrade.
     */
    public record Topic(String id, String name, List<String> requires, Map<Item, Integer> cost, int work, List<ResourceLocation> unlocks,
                        String effect) {
        public boolean upgrade() {
            return unlocks.isEmpty();
        }
    }

    private static Map<Item, Integer> cost(Object... pairs) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Item) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    public static final List<Topic> TOPICS = List.of(
            new Topic("husbandry", "Animal husbandry", List.of(), cost(Items.BOOK, 2, Items.WHEAT, 16), 12, List.of(BuildingDef.BARN, BuildingDef.PEN), ""),
            new Topic("fishing", "Fishing", List.of(), cost(Items.BOOK, 2, Items.STRING, 4), 12, List.of(BuildingDef.FISHERY, BuildingDef.DOCK), ""),
            new Topic("mining", "Mining", List.of(), cost(Items.BOOK, 2, Items.OAK_PLANKS, 16), 12, List.of(BuildingDef.MINE, BuildingDef.QUARRY), ""),
            new Topic("carpentry", "Carpentry", List.of(), cost(Items.BOOK, 2, Items.OAK_LOG, 16), 12, List.of(BuildingDef.SAWMILL), ""),
            new Topic("trade", "Trade", List.of(), cost(Items.BOOK, 3, Items.EMERALD, 2), 16, List.of(BuildingDef.MARKET, BuildingDef.STALLS), ""),
            new Topic("masonry", "Masonry", List.of("mining"), cost(Items.BOOK, 3, Items.COBBLESTONE, 32), 20, List.of(BuildingDef.STONEWORKS), ""),
            new Topic("smithing", "Smithing", List.of("mining"), cost(Items.BOOK, 3, Items.IRON_INGOT, 4), 20, List.of(BuildingDef.SMITHY), ""),
            new Topic("weaving", "Weaving", List.of("husbandry"), cost(Items.BOOK, 3, Items.WHITE_WOOL, 8), 20, List.of(BuildingDef.LOOM_HOUSE), ""),
            new Topic("beekeeping", "Beekeeping", List.of("husbandry"), cost(Items.BOOK, 3, Items.GLASS_BOTTLE, 4), 16, List.of(BuildingDef.APIARY), ""),
            new Topic("medicine", "Medicine", List.of(), cost(Items.BOOK, 3, Items.GLASS_BOTTLE, 4), 20, List.of(BuildingDef.INFIRMARY), ""),
            new Topic("schooling", "Schooling", List.of(), cost(Items.BOOK, 4, Items.PAPER, 16), 20, List.of(BuildingDef.SCHOOL), ""),
            new Topic("defence", "Defence", List.of("smithing"), cost(Items.BOOK, 4, Items.IRON_INGOT, 8), 24,
                    List.of(BuildingDef.BARRACKS, BuildingDef.WATCHTOWER, BuildingDef.POST, BuildingDef.WALL), ""),
            new Topic("tools", "Better tools", List.of("carpentry"), cost(Items.BOOK, 4, Items.IRON_INGOT, 8), 24, List.of(),
                    "every worker works a tenth quicker"),
            new Topic("mastery", "Mastery", List.of("tools", "smithing"), cost(Items.BOOK, 6, Items.GOLD_INGOT, 4), 36, List.of(),
                    "every worker works another tenth quicker"),
            new Topic("surveying", "Surveying", List.of(), cost(Items.BOOK, 3, Items.PAPER, 8), 16, List.of(),
                    "the town's land grows two blocks more with each home, and 32 further"),
            new Topic("cartography", "Cartography", List.of("surveying"), cost(Items.BOOK, 5, Items.COMPASS, 1), 30, List.of(),
                    "the town's land grows another 32 further"));

    public static Optional<Topic> topic(String id) {
        return TOPICS.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** The topic that unlocks a kind of building, if any (the rest can be built from the start). */
    public static Optional<Topic> unlocking(ResourceLocation type) {
        if (type == null) { // a building whose pack has gone
            return Optional.empty();
        }
        return TOPICS.stream().filter(t -> t.unlocks().contains(type)).findFirst();
    }

    /** What the town has to research before it can build a kind of building (empty: it can). */
    public static Optional<Topic> needed(Colony colony, ResourceLocation type) {
        if (type == null || !StadConfig.RESEARCH.get()) {
            return Optional.empty();
        }
        return unlocking(type).filter(t -> !colony.research.done.contains(t.id()));
    }

    /** Why a kind of building can't be planned yet, or null. */
    public static String locked(Colony colony, ResourceLocation type) {
        return needed(colony, type).map(t -> "The town hasn't learned how to build a " + dev.eiriksb.stad.building.Requirements.word(type)
                + " yet: research " + t.name() + " at a university (the town hall's Research tab).").orElse(null);
    }

    public static boolean has(Colony colony, String id) {
        return !StadConfig.RESEARCH.get() && topic(id).map(t -> !t.upgrade()).orElse(false) || colony.research.done.contains(id);
    }

    /** How long work takes, from the upgrades: 1 less a tenth for each. */
    public static double speed(Colony colony) {
        return 1.0 - (has(colony, "tools") ? 0.1 : 0) - (has(colony, "mastery") ? 0.1 : 0);
    }

    /** How much further than the config the town's land can grow, and how much more each home adds. */
    public static int moreLand(Colony colony) {
        return (has(colony, "surveying") ? 32 : 0) + (has(colony, "cartography") ? 32 : 0);
    }

    public static int morePerHome(Colony colony) {
        return has(colony, "surveying") ? 2 : 0;
    }

    /** An officer chooses what the university works on next. Returns what they're told. */
    public static String choose(Colony colony, net.minecraft.server.level.ServerPlayer player, String id) {
        Optional<Topic> t = topic(id);
        if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only the town's officers choose what's researched.";
        }
        if (t.isEmpty() || !available(colony, t.get())) {
            return "That can't be researched yet.";
        }
        if (colony.research.current != null && colony.research.paid && !colony.research.current.equals(id)) {
            return "The university is busy with " + topic(colony.research.current).map(Topic::name).orElse("something") + ": that comes first.";
        }
        colony.research.current = id;
        colony.research.progress = 0;
        colony.research.paid = false;
        colony.changed();
        boolean researcher = colony.citizens().stream().anyMatch(c -> Jobs.RESEARCHER.equals(c.job));
        return "The university will research " + t.get().name() + " next. It takes " + describe(t.get().cost()) + " from the stores"
                + (researcher ? "." : ": make someone a researcher (and build a university) to start.");
    }

    /** Whether a topic can be started: not done, and what it needs is. */
    public static boolean available(Colony colony, Topic t) {
        return !colony.research.done.contains(t.id()) && colony.research.done.containsAll(t.requires());
    }

    /** "3 books, 32 cobblestone" */
    public static String describe(Map<Item, Integer> cost) {
        List<String> out = new ArrayList<>();
        cost.forEach((item, n) -> {
            String name = item.getDescription().getString().toLowerCase(java.util.Locale.ROOT);
            if (n > 1 && name.matches(".*\\b(book|ingot|bottle|emerald|log)$")) {
                name += "s";
            } else if (n > 1 && name.endsWith("compass")) {
                name += "es";
            }
            out.add(n + " " + name);
        });
        return String.join(", ", out);
    }

    /** What a topic gives, in words. */
    public static String gives(Topic t) {
        if (t.upgrade()) {
            return capital(t.effect());
        }
        List<String> out = new ArrayList<>();
        for (ResourceLocation type : t.unlocks()) {
            String job = Workplaces.jobFor(type).orElse(dev.eiriksb.stad.architect.Yard.is(type) ? dev.eiriksb.stad.architect.Yard.job(type) : null);
            out.add("the " + dev.eiriksb.stad.building.Requirements.word(type) + (job == null ? "" : " (" + job + ")"));
        }
        return "Unlocks " + dev.eiriksb.stad.planner.Design.join(out);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A town's research: what's done, what's being worked on, how far along, and whether its cost has been paid. */
    public static final class State {
        public final Set<String> done = new LinkedHashSet<>();
        public String current;
        public int progress;
        public boolean paid;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag list = new ListTag();
            done.forEach(d -> list.add(StringTag.valueOf(d)));
            t.put("Done", list);
            if (current != null) {
                t.putString("Current", current);
            }
            t.putInt("Progress", progress);
            t.putBoolean("Paid", paid);
            return t;
        }

        public void load(CompoundTag t) {
            done.clear();
            for (Tag d : t.getList("Done", Tag.TAG_STRING)) {
                done.add(d.getAsString());
            }
            current = t.contains("Current") ? t.getString("Current") : null;
            progress = t.getInt("Progress");
            paid = t.getBoolean("Paid");
        }

        /** A town from before research: what it's built (or planned) it already knows. */
        void fromBuildings(Colony colony) {
            for (Building b : colony.buildings()) {
                unlocking(b.type()).ifPresent(topic -> done.add(topic.id()));
            }
        }

        /** Everything (for tests). */
        public void all() {
            TOPICS.forEach(topic -> done.add(topic.id()));
        }
    }
}
