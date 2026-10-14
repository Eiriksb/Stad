package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The town's fields: 9 by 9 round a water hole, the size the water keeps moist. A field is planned like a building
 * (with the building plans, or by a farmer who has none), but there's nothing for the builders to do: the farmers
 * level it, till it, dig the water hole and sow it. Server thread.
 */
public final class Fields {
    private Fields() {}

    public static final int SIZE = 9;
    public static final ResourceLocation KEY = ResourceLocation.fromNamespaceAndPath("stad", "fields/field");
    public static final BuildingDef DEF = new BuildingDef(KEY, "fields", BuildingDef.FIELD, "Field",
            "A field round a water hole. Farmers level it, till it, dig the water and sow it with seeds from the town hall;"
                    + " there's nothing for the builders to do.", List.of("field"), blueprint(), null);

    /** A 9 by 9 of plots round a water hole: only markers, since the farmers make all of it. */
    private static Blueprint blueprint() {
        List<Blueprint.Marker> markers = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                markers.add(new Blueprint.Marker(new BlockPos(x, 0, z), x == SIZE / 2 && z == SIZE / 2 ? "stad:water" : "stad:plot"));
            }
        }
        return new Blueprint(new Vec3i(SIZE, 2, SIZE), List.of(), markers);
    }

    public static boolean is(Building b) {
        return b.is(BuildingDef.FIELD);
    }

    public static List<Building> of(Colony colony) {
        return colony.buildings().stream().filter(Fields::is).toList();
    }

    /** Where the field's water goes: one hole in the middle of a plain field, channels in one designed at the table. */
    public static List<BlockPos> waters(Building field) {
        return field.placement().map(p -> p.markers("stad:water")).orElse(List.of());
    }

    /** The first of its water holes (the middle, for a plain field). */
    public static BlockPos water(Building field) {
        List<BlockPos> w = waters(field);
        return w.isEmpty() ? centre(field) : w.getFirst();
    }

    public static BlockPos centre(Building field) {
        BoundingBox b = bounds(field);
        return new BlockPos((b.minX() + b.maxX()) / 2, plots(field).stream().findFirst().map(BlockPos::getY).orElse(b.minY()), (b.minZ() + b.maxZ()) / 2);
    }

    public static BoundingBox bounds(Building field) {
        return field.placement().map(Placement::bounds).orElseGet(() -> new BoundingBox(field.origin.getX(), field.origin.getY(),
                field.origin.getZ(), field.origin.getX() + SIZE - 1, field.origin.getY() + 1, field.origin.getZ() + SIZE - 1));
    }

    /** Every spot of a field where farmland goes. */
    public static List<BlockPos> plots(Building field) {
        return field.placement().map(p -> p.markers().stream().filter(m -> m.name().startsWith("stad:plot")).map(Blueprint.Marker::pos).toList())
                .orElse(List.of());
    }

    /** The crop each plot that asks for one wants ("carrots"...): the rest take whatever seeds there are. */
    public static java.util.Map<BlockPos, String> crops(Building field) {
        java.util.Map<BlockPos, String> out = new java.util.HashMap<>();
        field.placement().ifPresent(p -> p.markers().stream().filter(m -> m.name().startsWith("stad:plot_"))
                .forEach(m -> out.put(m.pos(), m.name().substring("stad:plot_".length()))));
        return out;
    }

    /** Ground a farmer can till (or make into farmland once it's dug out). */
    public static boolean tillable(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY);
    }

    /**
     * Somewhere for a new field near {@code near}: flat-ish grass or dirt, dry, clear of trees, buildings and other
     * fields, on the town's land and not right by the town hall. Empty when there's nowhere.
     */
    public static Optional<BlockPos> findSpot(ServerLevel level, Colony colony, BlockPos near) {
        for (int r = 6; r <= colony.radius; r += 3) {
            for (int i = 0; i < Math.max(8, r * 2); i++) {
                double a = Math.PI * 2 * i / Math.max(8, r * 2);
                int cx = near.getX() + (int) Math.round(Math.cos(a) * r);
                int cz = near.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!level.isLoaded(new BlockPos(cx, near.getY(), cz))) {
                    continue;
                }
                int y = ground(level, cx, cz);
                BlockPos origin = new BlockPos(cx - SIZE / 2, y, cz - SIZE / 2);
                if (fits(level, colony, origin)) {
                    return Optional.of(origin);
                }
            }
        }
        return Optional.empty();
    }

    /** The top solid block of a column (under any grass or flowers). */
    private static int ground(ServerLevel level, int x, int z) {
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below();
        while (level.getBlockState(top).canBeReplaced() && level.getFluidState(top).isEmpty() && top.getY() > level.getMinBuildHeight()) {
            top = top.below();
        }
        return top.getY();
    }

    static boolean fits(ServerLevel level, Colony colony, BlockPos origin) {
        BoundingBox box = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + SIZE - 1, origin.getY() + 1,
                origin.getZ() + SIZE - 1);
        for (BlockPos c : new BlockPos[]{origin, origin.offset(SIZE - 1, 0, 0), origin.offset(0, 0, SIZE - 1), origin.offset(SIZE - 1, 0, SIZE - 1)}) {
            if (!colony.contains(colony.dimension, c)) {
                return false;
            }
        }
        if (box.inflatedBy(2).isInside(colony.center)) {
            return false;
        }
        BoundingBox room = box.inflatedBy(2);
        for (Building b : colony.buildings()) {
            if (b.overlaps(room)) {
                return false;
            }
        }
        int good = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int y = ground(level, origin.getX() + x, origin.getZ() + z);
                BlockPos g = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                if (Math.abs(y - origin.getY()) > 1 || !level.getFluidState(g).isEmpty() || !level.getFluidState(g.above()).isEmpty()) {
                    return false;
                }
                BlockState s = level.getBlockState(g);
                if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || level.getBlockEntity(g) != null) {
                    return false;
                }
                if (s.is(BlockTags.DIRT)) {
                    good++;
                }
            }
        }
        return good >= SIZE * SIZE * 3 / 4;
    }

    /** A field laid out by a farmer (no player's order: nothing to check against a rank). */
    public static Building layOut(ServerLevel level, Colony colony, BlockPos origin) {
        Building b = colony.addBuilding(KEY, DEF.name(), origin, Rotation.NONE, level.getGameTime());
        b.status = Building.Status.DONE;
        b.outlineUntil = level.getGameTime() + 200;
        colony.changed();
        return b;
    }
}
