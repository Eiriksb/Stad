package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Optional;

/**
 * The town's pens (PLAN.md §6.11), designed at the architect's table: a fence round some ground, a gate, and a marker
 * in the middle saying which animals it's for ({@code stad:pen_sheep}...). The shepherd leads that kind in through the
 * gate. Server thread.
 */
public final class Pens {
    private Pens() {}

    public static boolean is(Building b) {
        return b.is(BuildingDef.PEN);
    }

    public static List<Building> of(Colony colony) {
        return colony.buildings().stream().filter(Pens::is).toList();
    }

    /** The animals a pen is for (sheep when it doesn't say). */
    public static EntityType<?> kind(Building pen) {
        String name = pen.placement().flatMap(p -> p.markers().stream().map(m -> m.name()).filter(n -> n.startsWith("stad:pen")).findFirst())
                .orElse("stad:pen_sheep");
        return switch (name) {
            case "stad:pen_cows" -> EntityType.COW;
            case "stad:pen_pigs" -> EntityType.PIG;
            case "stad:pen_chickens" -> EntityType.CHICKEN;
            default -> EntityType.SHEEP;
        };
    }

    public static Optional<BoundingBox> bounds(Building pen) {
        return pen.placement().map(Placement::bounds);
    }

    /** Whether a spot is inside the pen's fence. */
    public static boolean inside(Building pen, BlockPos p) {
        return bounds(pen).map(b -> p.getX() > b.minX() && p.getX() < b.maxX() && p.getZ() > b.minZ() && p.getZ() < b.maxZ()
                && p.getY() >= b.minY() - 1 && p.getY() <= b.maxY() + 1).orElse(false);
    }

    /** The middle of the pen, where its marker is. */
    public static BlockPos middle(Building pen) {
        return pen.placement().flatMap(p -> p.markers().stream().filter(m -> m.name().startsWith("stad:pen")).map(m -> m.pos()).findFirst())
                .orElseGet(() -> bounds(pen).map(BoundingBox::getCenter).orElse(pen.origin));
    }

    /** A gate in the pen's fence as it stands, and which way is out through it. */
    public record Gate(BlockPos pos, Direction out) {
        public BlockPos outside() {
            return pos.relative(out);
        }

        public BlockPos inside() {
            return pos.relative(out.getOpposite());
        }
    }

    public static Optional<Gate> gate(ServerLevel level, Building pen) {
        Optional<BoundingBox> box = bounds(pen);
        if (box.isEmpty()) {
            return Optional.empty();
        }
        BoundingBox b = box.get();
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            if (level.getBlockState(p).getBlock() instanceof FenceGateBlock) {
                Direction out = p.getZ() == b.minZ() ? Direction.NORTH : p.getZ() == b.maxZ() ? Direction.SOUTH
                        : p.getX() == b.minX() ? Direction.WEST : Direction.EAST;
                return Optional.of(new Gate(p.immutable(), out));
            }
        }
        return Optional.empty();
    }

    /** "sheep", "cows", "pigs", "chickens" */
    public static String word(EntityType<?> kind) {
        return kind == EntityType.COW ? "cows" : kind == EntityType.PIG ? "pigs" : kind == EntityType.CHICKEN ? "chickens" : "sheep";
    }
}
