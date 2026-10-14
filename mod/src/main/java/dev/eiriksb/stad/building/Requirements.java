package dev.eiriksb.stad.building;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What each kind of building needs (PLAN.md §6.3: "building types declare requirements, not layouts"). Any building
 * that has them will do, which is what lets a house someone built themselves become a home or a workplace.
 */
public final class Requirements {
    private Requirements() {}

    public static final ResourceLocation WORKSHOP = ResourceLocation.fromNamespaceAndPath("stad", "workshop");

    /**
     * Something a building needs ({@code required}) or would be nicer with; {@code furnish} is a block that meets it,
     * which the architect's table puts in a generated building that lacks it (or null).
     */
    public record Need(String what, Predicate<Contents> met, boolean required, net.minecraft.world.level.block.Block furnish) {
        public Need(String what, Predicate<Contents> met, boolean required) {
            this(what, met, required, null);
        }
    }

    private static Need block(String what, net.minecraft.world.level.block.Block b) {
        return new Need(what, c -> c.count(b) > 0, true, b);
    }

    private static final List<Need> SHELTER = List.of(
            new Need("walls all round", c -> c.walls, true),
            new Need("a roof without holes", c -> c.roof, true),
            new Need("a door", c -> c.entrance && c.has(Contents.Thing.DOOR), true));

    private static final Map<ResourceLocation, List<Need>> TYPES = new LinkedHashMap<>();

    static {
        type(BuildingDef.HOME,
                new Need("a bed", c -> c.has(Contents.Thing.BED), true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false),
                new Need("somewhere to cook (a furnace, smoker or campfire)", c -> c.has(Contents.Thing.COOKING), false));
        type(BuildingDef.BUILDER_HUT,
                new Need("a crafting table", c -> c.has(Contents.Thing.CRAFTING_TABLE), true),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true),
                new Need("a bed", c -> c.has(Contents.Thing.BED), true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.WAREHOUSE,
                new Need("at least four chests or barrels", c -> c.count(Contents.Thing.STORAGE) >= 4, true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.BARRACKS,
                new Need("two beds", c -> c.count(Contents.Thing.BED) >= 2, true),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        TYPES.put(BuildingDef.WATCHTOWER, List.of( // open at the top is fine
                new Need("a door", c -> c.entrance && c.has(Contents.Thing.DOOR), true),
                new Need("some height (at least 8 blocks)", c -> c.height >= 8, true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false)));
        type(BuildingDef.KITCHEN,
                new Need("somewhere to cook (a furnace, smoker or campfire)", c -> c.has(Contents.Thing.COOKING), true,
                        net.minecraft.world.level.block.Blocks.SMOKER),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        TYPES.put(BuildingDef.MINE, List.of( // a headframe over the shaft: walls and a roof are up to you
                new Need("a shaft marker where the shaft goes down", c -> c.marker("stad:shaft"), true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false)));
        type(BuildingDef.FARMHOUSE,
                block("a composter", net.minecraft.world.level.block.Blocks.COMPOSTER),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("farmland within " + Surroundings.FARMLAND + " blocks", c -> c.near("farmland"), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.LUMBER_CAMP,
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a crafting table", c -> c.has(Contents.Thing.CRAFTING_TABLE), true, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE),
                new Need("trees within " + Surroundings.TREES + " blocks", c -> c.near("trees"), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.FISHERY,
                block("a barrel", net.minecraft.world.level.block.Blocks.BARREL),
                new Need("a pond or river within " + Surroundings.WATER + " blocks", c -> c.near("water"), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.BARN,
                block("a loom", net.minecraft.world.level.block.Blocks.LOOM),
                block("a hay bale", net.minecraft.world.level.block.Blocks.HAY_BLOCK),
                new Need("a fenced pen beside it", c -> c.near("pen"), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.SMITHY,
                block("an anvil", net.minecraft.world.level.block.Blocks.ANVIL),
                new Need("a furnace or blast furnace", c -> c.count(net.minecraft.world.level.block.Blocks.FURNACE)
                        + c.count(net.minecraft.world.level.block.Blocks.BLAST_FURNACE) > 0, true, net.minecraft.world.level.block.Blocks.BLAST_FURNACE),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.INFIRMARY,
                block("a brewing stand", net.minecraft.world.level.block.Blocks.BREWING_STAND),
                new Need("a bed", c -> c.has(Contents.Thing.BED), true),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), false, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.MARKET,
                block("a bell", net.minecraft.world.level.block.Blocks.BELL),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.SCHOOL,
                block("a lectern", net.minecraft.world.level.block.Blocks.LECTERN),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.UNIVERSITY,
                block("a lectern", net.minecraft.world.level.block.Blocks.LECTERN),
                new Need("bookshelves (four or more)", c -> c.count(net.minecraft.world.level.block.Blocks.BOOKSHELF) >= 4, true,
                        net.minecraft.world.level.block.Blocks.BOOKSHELF),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        TYPES.put(BuildingDef.FIELD, List.of( // fields, pens and groves are land, not buildings: no walls or roof
                new Need("farmland (at least four blocks)", c -> c.count(net.minecraft.world.level.block.Blocks.FARMLAND) >= 4
                        || c.markerLike("stad:plot"), true),
                new Need("water near all the farmland (within four blocks)", c -> c.dry == 0, true),
                new Need("a fence round it, to keep animals out", c -> c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                        || s.getBlock() instanceof net.minecraft.world.level.block.WallBlock) >= 8, false)));
        TYPES.put(BuildingDef.PEN, List.of(
                new Need("a fence all round", c -> c.penned && c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                        || s.getBlock() instanceof net.minecraft.world.level.block.WallBlock) >= 8, true),
                new Need("a gate", c -> c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) > 0, true),
                new Need("water to drink (a cauldron of water or a pond)", c -> c.count(net.minecraft.world.level.block.Blocks.WATER_CAULDRON) > 0
                        || c.count(net.minecraft.world.level.block.Blocks.WATER) > 0, false),
                new Need("a hay bale", c -> c.count(net.minecraft.world.level.block.Blocks.HAY_BLOCK) > 0, false)));
        TYPES.put(BuildingDef.GROVE, List.of(
                new Need("saplings where the trees go (at least four)", c -> c.count(s -> s.is(net.minecraft.tags.BlockTags.SAPLINGS)) >= 4
                        || c.markerLike("stad:sapling"), true),
                new Need("room between the saplings (two blocks)", c -> c.crowded == 0, false)));
        TYPES.put(BuildingDef.QUARRY, List.of(
                new Need("the pit marked out (Lay it out)", c -> c.markerLike("stad:quarry"), true),
                new Need("a fence round it, so nobody falls in", c -> c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) >= 8,
                        false)));
        TYPES.put(BuildingDef.DOCK, List.of(
                new Need("water (eight blocks or more)", c -> c.count(net.minecraft.world.level.block.Blocks.WATER) >= 8, true),
                new Need("spots to fish from (Lay it out)", c -> c.marker("stad:fish"), true),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false)));
        TYPES.put(BuildingDef.POST, List.of(
                new Need("a watch spot (Lay it out, or mark one)", c -> c.marker("stad:post"), true),
                new Need("some height (four blocks or more)", c -> c.height >= 4, false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false)));
        TYPES.put(BuildingDef.WALL, List.of(
                new Need("a wall all round", c -> c.penned, true),
                new Need("a way in (a door or a gate)", c -> c.has(Contents.Thing.DOOR)
                        || c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) > 0, true),
                new Need("watch spots for the guards", c -> c.marker("stad:post"), false)));
        type(BuildingDef.SAWMILL,
                block("a crafting table", net.minecraft.world.level.block.Blocks.CRAFTING_TABLE),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.STONEWORKS,
                block("a stonecutter", net.minecraft.world.level.block.Blocks.STONECUTTER),
                new Need("a furnace", c -> c.count(net.minecraft.world.level.block.Blocks.FURNACE) + c.count(net.minecraft.world.level.block.Blocks.BLAST_FURNACE) > 0,
                        true, net.minecraft.world.level.block.Blocks.FURNACE),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        type(BuildingDef.LOOM_HOUSE,
                block("a loom", net.minecraft.world.level.block.Blocks.LOOM),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), true, net.minecraft.world.level.block.Blocks.CHEST),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
        TYPES.put(BuildingDef.APIARY, List.of( // out in the open, among the flowers
                new Need("a beehive", c -> c.count(net.minecraft.world.level.block.Blocks.BEEHIVE) + c.count(net.minecraft.world.level.block.Blocks.BEE_NEST) > 0, true,
                        net.minecraft.world.level.block.Blocks.BEEHIVE),
                new Need("flowers for the bees (four or more)", c -> c.count(s -> s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)) >= 4
                        || c.markerLike("stad:flower"), false),
                new Need("a fence round it", c -> c.count(s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) >= 8, false)));
        TYPES.put(BuildingDef.STALLS, List.of(
                new Need("stalls, with a spot for each merchant (Lay it out, or mark work spots)", c -> c.marker("stad:work"), true),
                block("a bell", net.minecraft.world.level.block.Blocks.BELL),
                new Need("somewhere to keep the wares (a barrel or a chest)", c -> c.has(Contents.Thing.STORAGE), true,
                        net.minecraft.world.level.block.Blocks.BARREL),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false)));
        TYPES.put(BuildingDef.ROAD, List.of(
                new Need("some road (four blocks of paving or more)", c -> c.count(dev.eiriksb.stad.architect.Yard::paving) >= 4, true),
                new Need("lamps along it", c -> c.has(Contents.Thing.LIGHT), false)));
        type(WORKSHOP,
                new Need("a crafting table", c -> c.has(Contents.Thing.CRAFTING_TABLE), true),
                new Need("a workstation (a smithing table, stonecutter, loom, anvil...)", c -> c.has(Contents.Thing.WORKSTATION), true),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), false));
    }

    private static void type(ResourceLocation type, Need... needs) {
        List<Need> all = new ArrayList<>(SHELTER);
        all.addAll(List.of(needs));
        TYPES.put(type, List.copyOf(all));
    }

    public static List<ResourceLocation> types() {
        return List.copyOf(TYPES.keySet());
    }

    public static List<Need> needs(ResourceLocation type) {
        return TYPES.getOrDefault(type, SHELTER);
    }

    /** What it still needs to be a {@code type} (empty: it'll do). */
    public static List<String> missing(ResourceLocation type, Contents c) {
        return needs(type).stream().filter(n -> n.required() && !n.met().test(c)).map(Need::what).toList();
    }

    /** What would make it nicer as a {@code type}. */
    public static List<String> wishes(ResourceLocation type, Contents c) {
        return needs(type).stream().filter(n -> !n.required() && !n.met().test(c)).map(Need::what).toList();
    }

    /** What a building most likely is, from what's in it: beds make a home, lots of storage a warehouse... */
    public static Optional<ResourceLocation> guess(Contents c) {
        if (c.has(Contents.Thing.BED)) {
            return Optional.of(BuildingDef.HOME); // a builder's hut has a bed too, but only when someone says so
        }
        if (c.count(Contents.Thing.STORAGE) >= 4) {
            return Optional.of(BuildingDef.WAREHOUSE);
        }
        if (c.has(Contents.Thing.CRAFTING_TABLE) && c.has(Contents.Thing.WORKSTATION)) {
            return Optional.of(WORKSHOP);
        }
        return Optional.empty();
    }

    /** The type a word means: "home", "house", "hut", "warehouse", "workshop"... (null: none). */
    public static ResourceLocation typeOf(String word) {
        if (word == null) {
            return null;
        }
        String w = word.toLowerCase(Locale.ROOT);
        if (w.contains("barracks")) {
            return BuildingDef.BARRACKS;
        }
        if (w.contains("tower") || w.contains("lookout")) {
            return BuildingDef.WATCHTOWER;
        }
        if (w.contains("builder") || w.contains("hut")) {
            return BuildingDef.BUILDER_HUT;
        }
        if (w.contains("warehouse") || w.contains("store") || w.contains("storage")) {
            return BuildingDef.WAREHOUSE;
        }
        if (w.contains("farm")) {
            return BuildingDef.FARMHOUSE;
        }
        if (w.contains("lumber") || w.contains("sawmill") || w.contains("woodcutter") || w.contains("logging")) {
            return BuildingDef.LUMBER_CAMP;
        }
        if (w.contains("fish") || w.contains("boathouse") || w.contains("boat house")) {
            return BuildingDef.FISHERY;
        }
        if (w.contains("barn") || w.contains("stable") || w.contains("byre")) {
            return BuildingDef.BARN;
        }
        if (w.contains("kitchen") || w.contains("bakery")) {
            return BuildingDef.KITCHEN;
        }
        if (w.contains("smith") || w.contains("forge")) {
            return BuildingDef.SMITHY;
        }
        if (w.contains("infirmary") || w.contains("hospital") || w.contains("healer") || w.contains("clinic") || w.contains("sickhouse")) {
            return BuildingDef.INFIRMARY;
        }
        if (w.contains("sawmill") || w.contains("carpent") || w.contains("joiner")) {
            return BuildingDef.SAWMILL;
        }
        if (w.contains("mason") || w.contains("stonework") || w.contains("stonecutter")) {
            return BuildingDef.STONEWORKS;
        }
        if (w.contains("loom") || w.contains("weav") || w.contains("tailor")) {
            return BuildingDef.LOOM_HOUSE;
        }
        if (w.contains("apiary") || w.contains("bee") || w.contains("hive")) {
            return BuildingDef.APIARY;
        }
        if (w.contains("road") || w.contains("street")) {
            return BuildingDef.ROAD;
        }
        if (w.contains("square")) {
            return BuildingDef.STALLS;
        }
        if (w.contains("market") || w.contains("shop") || w.contains("stall") || w.contains("trading")) {
            return BuildingDef.MARKET;
        }
        if (w.contains("university") || w.contains("research") || w.contains("academy") || w.contains("library")) {
            return BuildingDef.UNIVERSITY;
        }
        if (w.contains("school") || w.contains("classroom")) {
            return BuildingDef.SCHOOL;
        }
        if (w.contains("workshop")) {
            return WORKSHOP;
        }
        if (w.contains("home") || w.contains("house") || w.contains("place") || w.contains("cottage") || w.contains("cabin")) {
            return BuildingDef.HOME;
        }
        ResourceLocation id = ResourceLocation.tryParse(w.contains(":") ? w : "stad:" + w);
        return id != null && TYPES.containsKey(id) ? id : null;
    }

    /** "home", "builder's hut", "warehouse", "workshop" */
    public static String word(ResourceLocation type) {
        return switch (type.getPath()) {
            case "builder_hut" -> "builder's hut";
            case "post" -> "guard post";
            case "stalls" -> "market square";
            default -> type.getPath().replace('_', ' ');
        };
    }
}
