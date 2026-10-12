package dev.eiriksb.stad.planner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The building materials the planner knows: each is a family of blocks (a full block, stairs and a slab; woods also
 * have a log, a fence and a door). The names are what the language model picks from.
 */
public final class Materials {
    public enum Shape { FULL, STAIRS, SLAB, LOG, FENCE, DOOR }

    public record Material(String name, String label, Block full, Block stairs, Block slab, Block log, Block fence, Block door) {
        public boolean wood() {
            return log != null;
        }

        /** The block of that shape; null when there's none (stone has no fence or door). */
        public Block block(Shape shape) {
            return switch (shape) {
                case FULL -> full;
                case STAIRS -> stairs;
                case SLAB -> slab;
                case LOG -> log != null ? log : full;
                case FENCE -> fence;
                case DOOR -> door;
            };
        }
    }

    private static final Map<String, Material> BY_NAME = new LinkedHashMap<>();
    private static final Map<Block, Map.Entry<Material, Shape>> BY_BLOCK = new HashMap<>();

    static {
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            add(wood, wood.replace('_', ' '), wood + "_planks", wood + "_stairs", wood + "_slab", wood + "_log", wood + "_fence", wood + "_door");
        }
        stone("cobblestone", "cobblestone", "cobblestone");
        stone("mossy_cobblestone", "mossy cobblestone", "mossy_cobblestone");
        stone("stone", "stone", "stone");
        add("stone_bricks", "stone brick", "stone_bricks", "stone_brick_stairs", "stone_brick_slab", null, null, null);
        add("mossy_stone_bricks", "mossy stone brick", "mossy_stone_bricks", "mossy_stone_brick_stairs", "mossy_stone_brick_slab", null, null, null);
        add("bricks", "brick", "bricks", "brick_stairs", "brick_slab", null, null, null);
        stone("sandstone", "sandstone", "sandstone");
        stone("red_sandstone", "red sandstone", "red_sandstone");
        stone("cobbled_deepslate", "deepslate", "cobbled_deepslate");
        add("deepslate_bricks", "deepslate brick", "deepslate_bricks", "deepslate_brick_stairs", "deepslate_brick_slab", null, null, null);
        stone("andesite", "andesite", "polished_andesite");
        stone("granite", "granite", "polished_granite");
        stone("diorite", "diorite", "polished_diorite");
        add("mud_bricks", "mud brick", "mud_bricks", "mud_brick_stairs", "mud_brick_slab", null, null, null);
        add("blackstone", "blackstone", "polished_blackstone_bricks", "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", null, null, null);
        add("tuff_bricks", "tuff brick", "tuff_bricks", "tuff_brick_stairs", "tuff_brick_slab", null, null, null);
        add("quartz", "quartz", "quartz_block", "quartz_stairs", "quartz_slab", null, null, null);
        stone("prismarine", "prismarine", "prismarine");
        add("nether_bricks", "nether brick", "nether_bricks", "nether_brick_stairs", "nether_brick_slab", null, null, null);
    }

    private Materials() {}

    private static void stone(String name, String label, String block) {
        add(name, label, block, block + "_stairs", block + "_slab", null, null, null);
    }

    private static void add(String name, String label, String full, String stairs, String slab, String log, String fence, String door) {
        Block f = block(full), st = block(stairs), sl = block(slab);
        if (f == null || st == null || sl == null) {
            return;
        }
        Material m = new Material(name, label, f, st, sl, block(log), block(fence), block(door));
        BY_NAME.put(name, m);
        BY_BLOCK.put(f, Map.entry(m, Shape.FULL));
        BY_BLOCK.put(st, Map.entry(m, Shape.STAIRS));
        BY_BLOCK.put(sl, Map.entry(m, Shape.SLAB));
        if (m.log != null) {
            BY_BLOCK.put(m.log, Map.entry(m, Shape.LOG));
        }
        if (m.fence != null) {
            BY_BLOCK.put(m.fence, Map.entry(m, Shape.FENCE));
        }
        if (m.door != null) {
            BY_BLOCK.put(m.door, Map.entry(m, Shape.DOOR));
        }
    }

    private static Block block(String name) {
        if (name == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace(name)).filter(b -> b != Blocks.AIR).orElse(null);
    }

    public static List<String> names() {
        return new ArrayList<>(BY_NAME.keySet());
    }

    public static List<String> woods() {
        return BY_NAME.values().stream().filter(Material::wood).map(Material::name).toList();
    }

    public static Optional<Material> get(String name) {
        return Optional.ofNullable(name == null ? null : BY_NAME.get(name));
    }

    public static Material getOr(String name, String fallback) {
        return get(name).orElseGet(() -> BY_NAME.get(fallback));
    }

    /** Which material and shape a block is, if it's one of ours. */
    public static Optional<Map.Entry<Material, Shape>> of(Block block) {
        return Optional.ofNullable(BY_BLOCK.get(block));
    }

    /** {@code to}'s default state with every property {@code from} shares with it (facing, half, axis...). */
    public static BlockState copyProperties(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property<?> p : from.getProperties()) {
            if (s.hasProperty(p)) {
                s = copy(from, s, p);
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }
}
