package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderRecord;
import dev.eiriksb.stad.planner.Design;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roads for the whole town at the press of a button (PLAN.md §6.11): every door and gate of the town's buildings and the
 * town hall joined up over all of the town's land ({@link Routes}), growing from the roads already there, and planned
 * as one road for the builders. The land is read from the world as it stands (the ground under trees), so it needn't
 * fit in a table's site.
 */
public final class TownRoads {
    private TownRoads() {}

    public record Result(String message, boolean error) {}

    /** Plans roads joining up the town, paved and laid as {@code style} says ({@link Yard#paving}, width, lamps). */
    public static Result plan(ServerPlayer player, ServerLevel level, Colony colony, Yard style) {
        if (player != null && !Planning.canPlan(colony, player)) {
            return new Result("Only officers of " + colony.name() + " can plan roads.", true);
        }
        int r = colony.radius;
        Routes.Land land = new Routes.Land(colony.center.getX() - r, colony.center.getZ() - r, 2 * r + 1, 2 * r + 1);
        Set<Integer> tree = new HashSet<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = 0; x < land.sx; x++) {
            for (int z = 0; z < land.sz; z++) {
                int wx = land.ox + x, wz = land.oz + z;
                if (!colony.contains(level.dimension(), new BlockPos(wx, colony.center.getY(), wz))) {
                    land.blocked[x + z * land.sx] = true;
                    continue;
                }
                int g = ground(level, wx, wz, at);
                boolean trunk = level.getBlockState(at.set(wx, g + 1, wz)).is(BlockTags.LOGS);
                land.column(x, z, g, level.getBlockState(at.set(wx, g, wz)), trunk);
            }
        }
        List<Routes.End> ends = new ArrayList<>(Routes.doors(colony, land, (x, z) -> land.ground[x + z * land.sx], level::getBlockState));
        ends.removeIf(e -> outside(colony, level, land, e));
        ends.sort((a, b) -> Boolean.compare(!a.what().equals("the town hall"), !b.what().equals("the town hall"))); // from the hall out
        land.buildings(colony, style.width() / 2, ends);
        for (Building b : colony.buildings()) {
            if (b.is(BuildingDef.ROAD)) {
                b.placement().ifPresent(p -> p.markers("stad:road").forEach(m -> {
                    int x = m.getX() - land.ox, z = m.getZ() - land.oz;
                    if (land.inside(x, z)) {
                        tree.add(x + z * land.sx);
                    }
                }));
            }
        }
        if (ends.size() + (tree.isEmpty() ? 0 : 1) < 2) {
            return new Result("There's nothing to join yet: plan some buildings first, then lay roads between them.", true);
        }
        Routes.Joined j = Routes.join(land, ends, tree, "the town's roads");
        if (j.added().isEmpty()) {
            return new Result(j.missed().isEmpty() ? "The town's roads already join every door." : "The roads can't get any further."
                    + Routes.missedLine(j.missed()), !j.missed().isEmpty());
        }
        Map<BlockPos, BlockState> blocks = pave(level, colony, land, j.added(), style);
        Set<BlockPos> paved = new LinkedHashSet<>();
        blocks.forEach((pos, st) -> {
            if (Yard.paving(st)) {
                paved.add(pos);
            }
        });
        if (paved.isEmpty()) {
            return new Result("There's nowhere to lay a road: the way is all buildings.", true);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE,
                maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks.keySet()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        BlockPos origin = new BlockPos(minX, minY, minZ);
        List<Blueprint.Entry> entries = new ArrayList<>();
        blocks.forEach((pos, st) -> {
            BlockState now = level.getBlockState(pos);
            if (!now.equals(st) && !(st.isAir() && now.isAir())) {
                entries.add(new Blueprint.Entry(pos.subtract(origin), st));
            }
        });
        List<Blueprint.Marker> markers = paved.stream().map(p -> new Blueprint.Marker(p.subtract(origin), "stad:road")).toList();
        Blueprint bp = new Blueprint(new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1), entries, markers);
        String name = colony.buildings().stream().anyMatch(b -> b.is(BuildingDef.ROAD) && b.name.equals("Roads")) ? "More roads" : "Roads";
        Design design = Design.asBuilt(name, BuildingDef.ROAD, bp, "new");
        long now = level.getGameTime();
        Building planned = colony.addBuilding(ResourceLocation.fromNamespaceAndPath("stad", "design/" + design.slug()), name, origin, Rotation.NONE,
                now, design);
        planned.outlineUntil = now + 200;
        if (entries.isEmpty()) {
            planned.status = Building.Status.DONE; // it's all paved already
        }
        if (player != null) {
            colony.log(new OrderRecord(now, player.getUUID(), player.getGameProfile().getName(), null, name, OrderKind.PLAN_BUILDING,
                    Order.Outcome.ACCEPTED, null));
        }
        int items = Placement.of(bp, origin, Rotation.NONE).materials().values().stream().mapToInt(Integer::intValue).sum();
        List<String> places = j.joined().stream().filter(w -> !w.equals("the town's roads")).toList();
        return new Result("Planned roads to " + Design.join(places) + " (" + paved.size() + " blocks, " + items + " items for the builders)."
                + Routes.missedLine(j.missed()), false);
    }

    private static boolean outside(Colony colony, ServerLevel level, Routes.Land land, Routes.End e) {
        return !colony.contains(level.dimension(), new BlockPos(land.ox + e.x(), colony.center.getY(), land.oz + e.z()));
    }

    /** The top of the land in a column: the highest block that isn't a plant, leaves, a tree trunk or a barrier (water counts). */
    static int ground(ServerLevel level, int x, int z, BlockPos.MutableBlockPos at) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            if (!s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && !s.isAir() && !s.is(Blocks.BARRIER) && !(s.getFluidState().isEmpty() && s.canBeReplaced())) {
                break;
            }
            y--;
        }
        return y;
    }

    /**
     * The blocks a road along {@code line} needs, by world position: paving on the ground {@code width} wide (planks over
     * water), what's on it cleared (a tree's trunk too), lamp posts beside it every eight blocks. Not where the town's
     * buildings stand, and only on ground.
     */
    static Map<BlockPos, BlockState> pave(ServerLevel level, Colony colony, Routes.Land land, Set<Integer> line, Yard style) {
        Set<Integer> keep = new HashSet<>();
        for (Building b : colony.buildings()) {
            if (b.is(BuildingDef.ROAD) || b.placement().isEmpty()) {
                continue;
            }
            Placement pl = b.placement().get();
            if (b.ring()) {
                pl.steps().forEach(s -> keep.add(column(land, s.pos().getX(), s.pos().getZ())));
                continue;
            }
            var box = pl.bounds();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    keep.add(column(land, x, z));
                }
            }
        }
        keep.add(column(land, colony.center.getX(), colony.center.getZ()));
        Set<Integer> cells = new LinkedHashSet<>();
        int lo = -(style.width() - 1) / 2, hi = style.width() / 2;
        for (int c : line) {
            int cx = c % land.sx, cz = c / land.sx;
            for (int dx = lo; dx <= hi; dx++) {
                for (int dz = lo; dz <= hi; dz++) {
                    int x = cx + dx, z = cz + dz;
                    if (land.inside(x, z) && !keep.contains(x + z * land.sx)) {
                        cells.add(x + z * land.sx);
                    }
                }
            }
        }
        BlockState floor = style.pavingBlock(), bridge = style.woodBlock("planks");
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int c : cells) {
            int wx = land.ox + c % land.sx, wz = land.oz + c / land.sx;
            int g = ground(level, wx, wz, at);
            BlockState here = level.getBlockState(at.set(wx, g, wz));
            if (here.getFluidState().isEmpty() && !Yard.groundLike(here)) {
                continue;
            }
            out.put(new BlockPos(wx, g, wz), here.getFluidState().isEmpty() ? floor : bridge);
            for (int y = g + 1; y <= g + 2 || level.getBlockState(at.set(wx, y, wz)).is(BlockTags.LOGS) && y < g + 16; y++) {
                if (!level.getBlockState(at.set(wx, y, wz)).isAir()) {
                    out.put(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        if (style.extra()) {
            List<BlockPos> lamps = new ArrayList<>();
            for (int c : cells) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    int x = c % land.sx + dir.getStepX(), z = c / land.sx + dir.getStepZ();
                    int wx = land.ox + x, wz = land.oz + z;
                    if (!land.inside(x, z) || cells.contains(x + z * land.sx) || keep.contains(x + z * land.sx) || land.blocked[x + z * land.sx]
                            || lamps.stream().anyMatch(l -> l.distToCenterSqr(wx, l.getY(), wz) < 64)) {
                        continue;
                    }
                    int g = ground(level, wx, wz, at);
                    BlockState here = level.getBlockState(at.set(wx, g, wz)), above = level.getBlockState(at.set(wx, g + 1, wz));
                    if (here.getFluidState().isEmpty() && Yard.groundLike(here) && above.canBeReplaced()) {
                        out.put(new BlockPos(wx, g + 1, wz), style.fenceBlock());
                        out.put(new BlockPos(wx, g + 2, wz), Blocks.LANTERN.defaultBlockState());
                        lamps.add(new BlockPos(wx, g, wz));
                    }
                }
            }
        }
        return out;
    }

    private static int column(Routes.Land land, int wx, int wz) {
        int x = wx - land.ox, z = wz - land.oz;
        return land.inside(x, z) ? x + z * land.sx : -1;
    }
}
