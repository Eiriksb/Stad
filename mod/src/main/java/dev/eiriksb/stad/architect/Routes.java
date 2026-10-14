package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The table routing a road for you (PLAN.md §6.11): it finds what there is to join in the site (the doors of the
 * town's buildings, the town hall, and roads and paths that run up to the site's edge) and joins them up the cheapest
 * way over the land as it is: round buildings, not up anything steeper than a block at a time, over water only when
 * there's no way round, along roads that are already there where it can. The route becomes {@code stad:road} markers,
 * the same as a road drawn by hand, so it can be changed afterwards.
 */
public final class Routes {
    private Routes() {}

    /** Something the road joins: a column of the site, and what it is ("the Bakery's door"). */
    record End(int x, int z, String what) {}

    /** What routing came to: the cells it added, what it joined, what it couldn't reach, or why it couldn't start. */
    public record Route(Set<Integer> cells, List<String> joined, List<String> missed, String problem) {
        public String message() {
            if (problem != null) {
                return problem;
            }
            String out = "Routed a road joining " + dev.eiriksb.stad.planner.Design.join(joined) + " (" + cells.size() + " blocks long).";
            if (!missed.isEmpty()) {
                out += " It can't get to " + dev.eiriksb.stad.planner.Design.join(missed) + " (too steep, or built round): draw that bit by hand.";
            }
            return out;
        }
    }

    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** Routes a road in the site's project and adds it to the road drawn so far. */
    public static Route route(Project p, Grid terrain, Colony colony) {
        List<End> ends = ends(p, terrain, colony);
        Set<Integer> drawn = new HashSet<>();
        p.markers.forEach((k, m) -> {
            if (m.equals("stad:road")) {
                drawn.add(cell(Project.kx(k), Project.kz(k)));
            }
        });
        if (ends.isEmpty() || ends.size() == 1 && drawn.isEmpty()) {
            int[] g = Yard.gate(p);
            if (ends.size() == 1 && (g[0] != ends.getFirst().x() || g[1] != ends.getFirst().z())) {
                ends.add(new End(g[0], g[1], "the " + p.door.getName() + " edge of the site"));
            } else {
                return new Route(Set.of(), List.of(), List.of(), "There's nothing here to join: put the site over a door or two (or the town hall)"
                        + ", or where a road comes in, or draw the road with the Road tool.");
            }
        }
        int n = p.sx * p.sz;
        int[] ground = new int[n];
        boolean[] blocked = new boolean[n];
        float[] cost = new float[n];
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                int c = x + z * p.sx, g = Yard.top(terrain, p, x, z);
                ground[c] = g;
                if (g < 0 || g + 2 >= p.sy) {
                    blocked[c] = true;
                    continue;
                }
                BlockState here = land(terrain, x, g, z), above = land(terrain, x, g + 1, z);
                if (here.getFluidState().isEmpty() && !Yard.groundLike(here)) {
                    blocked[c] = true; // something standing there: a chest, a table, a roof
                    continue;
                }
                cost[c] = !here.getFluidState().isEmpty() ? 4 : Yard.paving(here) ? 0.4f : 1;
                if (above.is(BlockTags.LOGS) || above.is(BlockTags.LEAVES)) {
                    cost[c] += 6; // a tree to fell
                }
            }
        }
        // buildings, with room for the road's width beside them (but it can always come up to a door)
        int pad = (p.yard != null ? p.yard : Yard.DEFAULT).width() / 2;
        for (Building b : colony.buildings()) {
            if (b.ring() || b.is(BuildingDef.ROAD)) {
                continue;
            }
            b.placement().map(Placement::bounds).ifPresent(box -> {
                for (int x = Math.max(0, box.minX() - pad - p.min.getX()); x <= Math.min(p.sx - 1, box.maxX() + pad - p.min.getX()); x++) {
                    for (int z = Math.max(0, box.minZ() - pad - p.min.getZ()); z <= Math.min(p.sz - 1, box.maxZ() + pad - p.min.getZ()); z++) {
                        int wx = x + p.min.getX(), wz = z + p.min.getZ(), cx = x, cz = z;
                        boolean inside = wx >= box.minX() && wx <= box.maxX() && wz >= box.minZ() && wz <= box.maxZ();
                        if (inside || ends.stream().noneMatch(e -> Math.abs(e.x() - cx) + Math.abs(e.z() - cz) <= pad + 1)) {
                            blocked[x + z * p.sx] = true;
                        }
                    }
                }
            });
        }
        // the town hall's own block, and anything else standing on the ground that isn't a plant
        int hx = colony.center.getX() - p.min.getX(), hz = colony.center.getZ() - p.min.getZ();
        if (hx >= 0 && hz >= 0 && hx < p.sx && hz < p.sz) {
            blocked[hx + hz * p.sx] = true;
        }
        for (End e : ends) {
            blocked[e.x() + e.z() * p.sx] = false;
        }
        Set<Integer> tree = new LinkedHashSet<>();
        List<String> joined = new ArrayList<>(), missed = new ArrayList<>();
        List<End> left = new ArrayList<>(ends);
        if (!drawn.isEmpty()) {
            for (int c : drawn) {
                tree.add(Project.kx(c) + (c >> 6) * p.sx);
            }
            joined.add("the road drawn so far");
        } else {
            End first = left.removeFirst();
            tree.add(first.x() + first.z() * p.sx);
            joined.add(first.what());
        }
        Set<Integer> added = new LinkedHashSet<>();
        while (!left.isEmpty()) {
            int[] path = nearest(p, tree, left, ground, blocked, cost);
            if (path == null) {
                left.forEach(e -> missed.add(e.what()));
                break;
            }
            int end = path[path.length - 1];
            End reached = left.stream().filter(e -> e.x() + e.z() * p.sx == end).findFirst().orElseThrow();
            left.remove(reached);
            joined.add(reached.what());
            for (int c : path) {
                if (tree.add(c)) {
                    added.add(c);
                }
            }
        }
        if (drawn.isEmpty() && !tree.isEmpty()) {
            added.add(tree.iterator().next());
        }
        if (added.isEmpty() && missed.isEmpty()) {
            return new Route(Set.of(), joined, missed, "The road already joins " + dev.eiriksb.stad.planner.Design.join(joined) + ".");
        }
        if (joined.size() < 2) {
            return new Route(Set.of(), joined, missed, "There's no way to " + dev.eiriksb.stad.planner.Design.join(missed)
                    + " without climbing more than a block at a time or going through a building: draw the road with the Road tool.");
        }
        Set<Integer> cells = new LinkedHashSet<>();
        for (int c : added) {
            int x = c % p.sx, z = c / p.sx;
            p.markers.put(Project.key(x, Math.max(0, ground[c]), z), "stad:road");
            cells.add(cell(x, z));
        }
        return new Route(cells, joined, missed, null);
    }

    private static int cell(int x, int z) {
        return x | z << 6;
    }

    /**
     * The cheapest way from any cell of {@code tree} to the nearest of {@code ends} (cells as {@code x + z * sx}, the
     * end last), or null when none can be reached. Turning costs a little, so the road runs straight where it can.
     */
    private static int[] nearest(Project p, Set<Integer> tree, List<End> ends, int[] ground, boolean[] blocked, float[] cost) {
        int n = p.sx * p.sz;
        float[] dist = new float[n * 5];
        int[] from = new int[n * 5];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(from, -1);
        Set<Integer> goals = new HashSet<>();
        ends.forEach(e -> goals.add(e.x() + e.z() * p.sx));
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        for (int c : tree) {
            dist[c * 5 + 4] = 0;
            open.add(new float[]{0, c * 5 + 4});
        }
        while (!open.isEmpty()) {
            float[] top = open.poll();
            int state = (int) top[1], c = state / 5, dir = state % 5;
            if (top[0] > dist[state]) {
                continue;
            }
            if (goals.contains(c) && !tree.contains(c)) {
                List<Integer> path = new ArrayList<>();
                for (int s = state; s >= 0 && !(tree.contains(s / 5) && s % 5 == 4); s = from[s]) {
                    path.addFirst(s / 5);
                }
                return path.stream().mapToInt(Integer::intValue).toArray();
            }
            int x = c % p.sx, z = c / p.sx;
            for (int d = 0; d < 4; d++) {
                int nx = x + STEPS[d][0], nz = z + STEPS[d][1];
                if (nx < 0 || nz < 0 || nx >= p.sx || nz >= p.sz) {
                    continue;
                }
                int m = nx + nz * p.sx;
                int climb = Math.abs(ground[m] - ground[c]);
                if (blocked[m] || climb > 1) {
                    continue;
                }
                float next = top[0] + cost[m] + climb * 2 + (dir != 4 && dir != d ? 0.6f : 0);
                int ns = m * 5 + d;
                if (next < dist[ns]) {
                    dist[ns] = next;
                    from[ns] = state;
                    open.add(new float[]{next, ns});
                }
            }
        }
        return null;
    }

    private static BlockState land(Grid terrain, int x, int y, int z) {
        return terrain.get(x + Project.MARGIN, y + Project.MARGIN, z + Project.MARGIN);
    }

    /**
     * What there is to join: the doors (and gates) of the town's buildings, outside them, that are in the site; the
     * town hall; and roads and paths running up to the site's edge (one end for each).
     */
    static List<End> ends(Project p, Grid terrain, Colony colony) {
        List<End> out = new ArrayList<>();
        BoundingBox site = new BoundingBox(p.min.getX(), p.min.getY(), p.min.getZ(), p.min.getX() + p.sx - 1, p.min.getY() + p.sy - 1,
                p.min.getZ() + p.sz - 1);
        for (Building b : colony.buildings()) {
            if (b.ring() || b.is(BuildingDef.ROAD) || b.placement().isEmpty()) {
                continue;
            }
            Placement pl = b.placement().get();
            BoundingBox box = pl.bounds();
            if (!box.inflatedBy(1).intersects(site)) {
                continue;
            }
            List<BlockPos> doors = new ArrayList<>(pl.markers("stad:door"));
            for (Placement.Step s : pl.steps()) {
                BlockState st = s.state();
                if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER || st.getBlock() instanceof FenceGateBlock) {
                    doors.add(s.pos());
                }
            }
            End found = null;
            for (BlockPos d : doors) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos o = d.relative(dir);
                    int x = o.getX() - p.min.getX(), z = o.getZ() - p.min.getZ();
                    if (!box.isInside(o) && x >= 0 && z >= 0 && x < p.sx && z < p.sz) {
                        found = new End(x, z, "the " + b.name);
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
            }
            if (found != null) {
                out.add(found);
            }
        }
        BlockPos hall = colony.center;
        int hx = hall.getX() - p.min.getX(), hz = hall.getZ() - p.min.getZ();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int x = hx + dir.getStepX(), z = hz + dir.getStepZ();
            if (x >= 0 && z >= 0 && x < p.sx && z < p.sz && Yard.top(terrain, p, x, z) == hall.getY() - p.min.getY() - 1) {
                out.add(new End(x, z, "the town hall"));
                break;
            }
        }
        // roads coming up to the edge: runs of paths (or the town's roads) just outside it, one end in the middle of each
        List<BoundingBox> roads = colony.buildings().stream().filter(b -> b.done() && b.is(BuildingDef.ROAD))
                .flatMap(b -> b.placement().map(Placement::bounds).stream()).toList();
        List<BoundingBox> others = colony.buildings().stream().filter(b -> !b.ring() && !b.is(BuildingDef.ROAD))
                .flatMap(b -> b.placement().map(Placement::bounds).stream()).toList(); // a field's path isn't a road
        for (int side = 0; side < 4; side++) {
            int len = side % 2 == 0 ? p.sx : p.sz;
            int run = -1;
            for (int i = 0; i <= len; i++) {
                boolean road = false;
                if (i < len) {
                    int[] in = side(p, side, i), outside = {in[0] + STEPS[side][0], in[1] + STEPS[side][1]};
                    int g = Yard.top(terrain, p, outside[0], outside[1]);
                    BlockState top = g >= 0 ? land(terrain, outside[0], g, outside[1]) : null;
                    BlockPos at = p.min.offset(outside[0], Math.max(0, g), outside[1]);
                    road = top != null && (top.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || Yard.paving(top)
                            && roads.stream().anyMatch(r -> r.isInside(at))) && others.stream().noneMatch(o -> o.isInside(at));
                }
                if (road && run < 0) {
                    run = i;
                } else if (!road && run >= 0) {
                    int[] mid = side(p, side, (run + i - 1) / 2);
                    out.add(new End(mid[0], mid[1], "the road coming in from the " + Direction.from2DDataValue((side + 2) % 4).getName()));
                    run = -1;
                }
            }
        }
        return out;
    }

    /** The {@code i}th cell along a side of the site: 0 north, 1 east, 2 south, 3 west. */
    private static int[] side(Project p, int side, int i) {
        return switch (side) {
            case 0 -> new int[]{i, 0};
            case 1 -> new int[]{p.sx - 1, i};
            case 2 -> new int[]{i, p.sz - 1};
            default -> new int[]{0, i};
        };
    }
}
