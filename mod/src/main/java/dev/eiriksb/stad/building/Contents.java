package dev.eiriksb.stad.building;

import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.EnumMap;
import java.util.Map;

/**
 * What a building has that matters to the people using it: beds, doors, a crafting table, storage, somewhere to cook,
 * light, workstations, and (for a building someone made, see {@link Survey}) whether it's closed in.
 */
public final class Contents {
    public enum Thing { BED, DOOR, CRAFTING_TABLE, STORAGE, COOKING, LIGHT, WORKSTATION }

    private final Map<Thing, Integer> counts = new EnumMap<>(Thing.class);
    /** walls all round (no gaps to the outside) */
    public boolean walls = true;
    /** a roof without holes */
    public boolean roof = true;
    /** a door to the outside */
    public boolean entrance = true;
    /** how tall it is, in blocks */
    public int height;
    /** the markers in it ("stad:shaft"...) */
    public final java.util.Set<String> markers = new java.util.HashSet<>();
    /** every kind of block in it, counted */
    private final Map<Block, Integer> blocks = new java.util.HashMap<>();
    /**
     * what's round it, when that was looked at ({@link Surroundings}): "farmland", "trees", "water", "pen"; null when
     * nobody looked (then needs that depend on it count as met)
     */
    public java.util.Set<String> near;
    /** farmland with no water within four blocks, where crops won't grow (fields; worked out at the architect's table) */
    public int dry;
    /** fenced in all round, so animals can't get out (pens; worked out at the architect's table, assumed otherwise) */
    public boolean penned = true;
    /** saplings planted with less than two blocks between them, too close to grow (groves; at the architect's table) */
    public int crowded;

    public int count(Thing t) {
        return counts.getOrDefault(t, 0);
    }

    public boolean has(Thing t) {
        return count(t) > 0;
    }

    public boolean marker(String name) {
        return markers.contains(name);
    }

    public int count(Block b) {
        return blocks.getOrDefault(b, 0);
    }

    /** How many blocks of a kind it has. */
    public int count(java.util.function.Predicate<BlockState> what) {
        int n = 0;
        for (Map.Entry<Block, Integer> e : blocks.entrySet()) {
            if (what.test(e.getKey().defaultBlockState())) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** Whether it has a marker whose name starts with {@code prefix} ("stad:plot" matches "stad:plot_carrots"). */
    public boolean markerLike(String prefix) {
        return markers.stream().anyMatch(m -> m.startsWith(prefix));
    }

    /** Whether there's {@code what} round it (or nobody looked). */
    public boolean near(String what) {
        return near == null || near.contains(what);
    }

    public void add(BlockState s) {
        Block b = s.getBlock();
        blocks.merge(b, 1, Integer::sum);
        if (b instanceof BedBlock) {
            if (s.getValue(BedBlock.PART) == BedPart.HEAD) {
                inc(Thing.BED);
            }
            return;
        }
        if (b instanceof DoorBlock) {
            if (s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                inc(Thing.DOOR);
            }
            return;
        }
        if (b == Blocks.CRAFTING_TABLE) {
            inc(Thing.CRAFTING_TABLE);
        } else if (b instanceof ChestBlock || b instanceof BarrelBlock) {
            inc(Thing.STORAGE);
        } else if (b instanceof AbstractFurnaceBlock || b instanceof CampfireBlock) {
            inc(b == Blocks.BLAST_FURNACE ? Thing.WORKSTATION : Thing.COOKING);
        } else if (b == Blocks.SMITHING_TABLE || b == Blocks.STONECUTTER || b == Blocks.LOOM || b == Blocks.ANVIL
                || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL || b == Blocks.GRINDSTONE || b == Blocks.CARTOGRAPHY_TABLE
                || b == Blocks.FLETCHING_TABLE || b == Blocks.BREWING_STAND || b == Blocks.LECTERN || b == Blocks.COMPOSTER) {
            inc(Thing.WORKSTATION);
        }
        if (s.getLightEmission() >= 10) {
            inc(Thing.LIGHT);
        }
    }

    private void inc(Thing t) {
        counts.merge(t, 1, Integer::sum);
    }

    /** What a blueprint has (assumed closed in: it was built to be). */
    public static Contents of(Blueprint bp) {
        Contents c = new Contents();
        bp.entries().forEach(e -> c.add(e.state()));
        bp.markers().forEach(m -> c.markers.add(m.name()));
        c.height = bp.size().getY();
        return c;
    }

    @Override
    public String toString() {
        return counts + (walls ? "" : " gaps") + (roof ? "" : " no-roof") + (entrance ? "" : " no-door");
    }
}
