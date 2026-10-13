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

    /** Something a building needs ({@code required}) or would be nicer with. */
    public record Need(String what, Predicate<Contents> met, boolean required) {}

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
                new Need("somewhere to cook (a furnace, smoker or campfire)", c -> c.has(Contents.Thing.COOKING), true),
                new Need("a chest", c -> c.has(Contents.Thing.STORAGE), false),
                new Need("a light", c -> c.has(Contents.Thing.LIGHT), false));
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
        if (w.contains("workshop") || w.contains("smithy") || w.contains("forge")) {
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
            default -> type.getPath().replace('_', ' ');
        };
    }
}
