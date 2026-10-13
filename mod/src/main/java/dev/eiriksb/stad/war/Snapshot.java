package dev.eiriksb.stad.war;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The town's buildings as they were before a raid (PLAN.md §6.7). Afterwards whatever was damaged becomes a repair
 * order for the builders, with the materials it takes, so nothing is lost for good. Kept in memory for the raid.
 */
public final class Snapshot {
    private record Part(String name, ResourceLocation type, BoundingBox box, Blueprint before) {}

    private final List<Part> parts;

    private Snapshot(List<Part> parts) {
        this.parts = parts;
    }

    /** The town's finished buildings and the ground round the town hall, as they are now. Server thread. */
    public static Snapshot take(ServerLevel level, Colony colony) {
        List<Part> parts = new ArrayList<>();
        for (Building b : colony.buildings()) {
            if (b.done() && !b.isRepair() && !dev.eiriksb.stad.building.Fields.is(b)) { // the farmers mend their own fields
                b.placement().ifPresent(p -> parts.add(new Part(b.name, b.type(), p.bounds(), Blueprint.capture(level, p.bounds()))));
            }
        }
        BlockPos c = colony.center;
        BoundingBox hall = new BoundingBox(c.getX() - 3, c.getY() - 1, c.getZ() - 3, c.getX() + 3, c.getY() + 4, c.getZ() + 3);
        if (parts.stream().noneMatch(p -> p.box.intersects(hall))) {
            parts.add(new Part("town hall", ResourceLocation.fromNamespaceAndPath("stad", "building"), hall, Blueprint.capture(level, hall)));
        }
        return new Snapshot(parts);
    }

    /** What's different now, building by building: the blocks to put back (air: to clear). */
    private static List<Blueprint.Entry> damage(ServerLevel level, Part part) {
        List<Blueprint.Entry> out = new ArrayList<>();
        BlockPos o = new BlockPos(part.box.minX(), part.box.minY(), part.box.minZ());
        for (Blueprint.Entry e : part.before.entries()) {
            BlockState was = e.state(), now = level.getBlockState(o.offset(e.pos()));
            if (now.is(was.getBlock()) || !was.getFluidState().isEmpty() && was.getBlock() == now.getBlock()) {
                continue;
            }
            if (was.isAir() || was.canBeReplaced()) {
                if (!now.canBeReplaced() && !now.is(Blocks.FIRE)) {
                    out.add(new Blueprint.Entry(e.pos(), Blocks.AIR.defaultBlockState())); // something left in the way
                }
            } else if (was.getFluidState().isEmpty()) {
                out.add(new Blueprint.Entry(e.pos(), was));
            }
        }
        return out;
    }

    /** How many blocks were damaged. */
    public int damaged(ServerLevel level) {
        return parts.stream().mapToInt(p -> damage(level, p).size()).sum();
    }

    /** Plans the repairs: one order per damaged building, for the builders to do first. Returns them. */
    public List<Building> repair(ServerLevel level, Colony colony) {
        List<Building> planned = new ArrayList<>();
        for (Part p : parts) {
            List<Blueprint.Entry> fix = damage(level, p);
            if (fix.isEmpty()) {
                continue;
            }
            String name = "Repairs to the " + p.name;
            Design d = Design.asBuilt(name, p.type, new Blueprint(p.before.size(), fix), "repair");
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath("stad", "repair/" + MyPack.slug(p.name));
            planned.add(colony.addBuilding(key, name, new BlockPos(p.box.minX(), p.box.minY(), p.box.minZ()), Rotation.NONE,
                    level.getGameTime(), d));
        }
        return planned;
    }
}
