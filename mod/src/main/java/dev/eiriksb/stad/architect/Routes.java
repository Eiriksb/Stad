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
import java.util.function.IntBinaryOperator;

/**
 * Routing roads (PLAN.md §6.11): finding what there is to join (the doors and gates of the town's buildings, the town
 * hall, and roads that run up to a site's edge) and joining them up the cheapest way over the land as it is: round
 * buildings, not up anything steeper than a block at a time, over water only when there's no way round, along roads
 * that are already there where it can. The table routes inside its site ({@link #route}: the route becomes
 * {@code stad:road} markers, the same as a road drawn by hand); {@link TownRoads} routes over the whole town.
 */
public final class Routes {
    private Routes() {}

    /** Something the road joins: a column ({@code x}, {@code z} from the land's corner), and what it is ("the Bakery"). */
    record End(int x, int z, String what) {}

    /** What routing in a site came to: the columns it added, what it joined, what it couldn't reach, or why it couldn't start. */
    public record Route(Set<Integer> cells, List<String> joined, List<String> missed, String problem) {
        public String message() {
            if (problem != null) {
                return problem;
            }
            return "Routed a road joining " + dev.eiriksb.stad.planner.Design.join(joined) + " (" + cells.size() + " blocks long)." + missedLine(missed);
        }
    }

    static String missedLine(List<String> missed) {
        return missed.isEmpty() ? "" : " It can't get to " + dev.eiriksb.stad.planner.Design.join(missed)
                + " (too steep, or built round): draw that bit by hand.";
    }

    /** What joining up came to: the columns added ({@code x + z * sx}), what it joined and what it couldn't reach. */
    record Joined(Set<Integer> added, List<String> joined, List<String> missed) {}

    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * The land a road is routed over: a rectangle of columns ({@code x + z * sx}) from a corner in the world, each with
     * the height of its ground (world y), whether a road can go there, and what going there costs.
     */
    static final class Land {
        final int sx, sz, ox, oz;
        final int[] ground;
        final boolean[] blocked;
        final float[] cost;

        Land(int ox, int oz, int sx, int sz) {
            this.ox = ox;
            this.oz = oz;
            this.sx = sx;
            this.sz = sz;
            ground = new int[sx * sz];
            blocked = new boolean[sx * sz];
            cost = new float[sx * sz];
        }

        boolean inside(int x, int z) {
            return x >= 0 && z >= 0 && x < sx && z < sz;
        }

        /**
         * A column: its ground at world height {@code g} (the top of the land, under any tree), with {@code tree} when a
         * tree stands on it. Only ground a road can be laid on is open: not a chest, a table or a roof.
         */
        void column(int x, int z, int g, BlockState here, boolean tree) {
            int c = x + z * sx;
            ground[c] = g;
            if (here.getFluidState().isEmpty() && !Yard.groundLike(here)) {
                blocked[c] = true;
                return;
            }
            cost[c] = (!here.getFluidState().isEmpty() ? 4 : Yard.paving(here) ? 0.4f : 1) + (tree ? 6 : 0);
        }

        /**
         * Shuts off the town's buildings (with room for a road {@code pad} blocks either side of its middle, except near
         * the ends, so it can come up to a door), the walls' own blocks and the town hall.
         */
        void buildings(Colony colony, int pad, List<End> ends) {
            for (Building b : colony.buildings()) {
                if (b.is(BuildingDef.ROAD) || b.placement().isEmpty()) {
                    continue;
                }
                Placement pl = b.placement().get();
                if (b.ring()) {
                    pl.steps().forEach(s -> block(s.pos().getX() - ox, s.pos().getZ() - oz));
                    continue;
                }
                BoundingBox box = pl.bounds();
                for (int x = Math.max(0, box.minX() - pad - ox); x <= Math.min(sx - 1, box.maxX() + pad - ox); x++) {
                    for (int z = Math.max(0, box.minZ() - pad - oz); z <= Math.min(sz - 1, box.maxZ() + pad - oz); z++) {
                        int wx = x + ox, wz = z + oz, cx = x, cz = z;
                        boolean in = wx >= box.minX() && wx <= box.maxX() && wz >= box.minZ() && wz <= box.maxZ();
                        if (in || ends.stream().noneMatch(e -> Math.abs(e.x() - cx) + Math.abs(e.z() - cz) <= pad + 1)) {
                            blocked[x + z * sx] = true;
                        }
                    }
                }
            }
            block(colony.center.getX() - ox, colony.center.getZ() - oz);
            for (End e : ends) {
                blocked[e.x() + e.z() * sx] = false;
            }
        }

        void block(int x, int z) {
            if (inside(x, z)) {
                blocked[x + z * sx] = true;
            }
        }
    }

    /**
     * Joins {@code ends} up: a tree grown from {@code start} (columns of a road there already; when empty, from the first
     * end), reaching the nearest end not yet joined each time.
     */
    static Joined join(Land land, List<End> ends, Set<Integer> start, String startName) {
        Set<Integer> tree = new LinkedHashSet<>(start);
        List<String> joined = new ArrayList<>(), missed = new ArrayList<>();
        List<End> left = new ArrayList<>(ends);
        Set<Integer> added = new LinkedHashSet<>();
        if (!tree.isEmpty()) {
            joined.add(startName);
            left.removeIf(e -> tree.contains(e.x() + e.z() * land.sx)); // already on the road
        } else if (!left.isEmpty()) {
            End first = left.removeFirst();
            tree.add(first.x() + first.z() * land.sx);
            added.add(first.x() + first.z() * land.sx);
            joined.add(first.what());
        }
        while (!left.isEmpty()) {
            int[] path = nearest(land, tree, left);
            if (path == null) {
                left.forEach(e -> missed.add(e.what()));
                break;
            }
            int end = path[path.length - 1];
            End reached = left.stream().filter(e -> e.x() + e.z() * land.sx == end).findFirst().orElseThrow();
            left.removeIf(e -> e.x() + e.z() * land.sx == end);
            joined.add(reached.what());
            for (int c : path) {
                if (tree.add(c)) {
                    added.add(c);
                }
            }
        }
        return new Joined(added, joined, missed);
    }

    /** Routes a road in the site's project and adds it to the road drawn so far. */
    public static Route route(Project p, Grid terrain, Colony colony) {
        List<End> ends = ends(p, terrain, colony);
        Set<Integer> drawn = new HashSet<>();
        p.markers.forEach((k, m) -> {
            if (m.equals("stad:road")) {
                drawn.add(Project.kx(k) + Project.kz(k) * p.sx);
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
        Land land = new Land(p.min.getX(), p.min.getZ(), p.sx, p.sz);
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                int g = Yard.top(terrain, p, x, z);
                if (g < 0 || g + 2 >= p.sy) {
                    land.blocked[x + z * p.sx] = true;
                    continue;
                }
                BlockState above = land(terrain, x, g + 1, z);
                land.column(x, z, g + p.min.getY(), land(terrain, x, g, z), above.is(BlockTags.LOGS) || above.is(BlockTags.LEAVES));
            }
        }
        land.buildings(colony, (p.yard != null ? p.yard : Yard.DEFAULT).width() / 2, ends);
        Joined j = join(land, ends, drawn, "the road drawn so far");
        if (j.added().isEmpty() && j.missed().isEmpty()) {
            return new Route(Set.of(), j.joined(), j.missed(), "The road already joins " + dev.eiriksb.stad.planner.Design.join(j.joined()) + ".");
        }
        if (j.joined().size() < 2) {
            return new Route(Set.of(), j.joined(), j.missed(), "There's no way to " + dev.eiriksb.stad.planner.Design.join(j.missed())
                    + " without climbing more than a block at a time or going through a building: draw the road with the Road tool.");
        }
        Set<Integer> cells = new LinkedHashSet<>();
        for (int c : j.added()) {
            int x = c % p.sx, z = c / p.sx;
            p.markers.put(Project.key(x, Math.max(0, land.ground[c] - p.min.getY()), z), "stad:road");
            cells.add(Project.col(x, z));
        }
        return new Route(cells, j.joined(), j.missed(), null);
    }

    /**
     * The cheapest way from any column of {@code tree} to the nearest of {@code ends} (columns as {@code x + z * sx},
     * the end last), or null when none can be reached. Turning costs a little, so the road runs straight where it can.
     */
    private static int[] nearest(Land land, Set<Integer> tree, List<End> ends) {
        int n = land.sx * land.sz;
        float[] dist = new float[n * 5];
        int[] from = new int[n * 5];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(from, -1);
        Set<Integer> goals = new HashSet<>();
        ends.forEach(e -> goals.add(e.x() + e.z() * land.sx));
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat((int) a[0]), Float.intBitsToFloat((int) b[0])));
        for (int c : tree) {
            dist[c * 5 + 4] = 0;
            open.add(new long[]{Float.floatToIntBits(0), c * 5L + 4});
        }
        while (!open.isEmpty()) {
            long[] top = open.poll();
            float at = Float.intBitsToFloat((int) top[0]);
            int state = (int) top[1], c = state / 5, dir = state % 5;
            if (at > dist[state]) {
                continue;
            }
            if (goals.contains(c) && !tree.contains(c)) {
                List<Integer> path = new ArrayList<>();
                for (int s = state; s >= 0 && !(tree.contains(s / 5) && s % 5 == 4); s = from[s]) {
                    path.addFirst(s / 5);
                }
                return path.stream().mapToInt(Integer::intValue).toArray();
            }
            int x = c % land.sx, z = c / land.sx;
            for (int d = 0; d < 4; d++) {
                int nx = x + STEPS[d][0], nz = z + STEPS[d][1];
                if (!land.inside(nx, nz)) {
                    continue;
                }
                int m = nx + nz * land.sx;
                int climb = Math.abs(land.ground[m] - land.ground[c]);
                if (land.blocked[m] || climb > 1) {
                    continue;
                }
                float next = at + land.cost[m] + climb * 2 + (dir != 4 && dir != d ? 0.6f : 0);
                int ns = m * 5 + d;
                if (next < dist[ns]) {
                    dist[ns] = next;
                    from[ns] = state;
                    open.add(new long[]{Float.floatToIntBits(next), ns});
                }
            }
        }
        return null;
    }

    private static BlockState land(Grid terrain, int x, int y, int z) {
        return terrain.get(x + Project.MARGIN, y + Project.MARGIN, z + Project.MARGIN);
    }

    /**
     * The doors (and gates) of the town's buildings in the land, the first column outside each, and the town hall (a
     * column beside it whose ground is the hall's: {@code groundAt} gives a column's ground, world y). A finished
     * building's doors are looked for in {@code world} too (a gate that stood there before it was planned isn't one of
     * its steps); one with no door at all (a market square's open way in) is joined at the middle of its side nearest
     * the hall.
     */
    static List<End> doors(Colony colony, Land land, IntBinaryOperator groundAt, java.util.function.Function<BlockPos, BlockState> world) {
        List<End> out = new ArrayList<>();
        BoundingBox area = new BoundingBox(land.ox, -100000, land.oz, land.ox + land.sx - 1, 100000, land.oz + land.sz - 1);
        for (Building b : colony.buildings()) {
            if (b.ring() || b.is(BuildingDef.ROAD) || b.placement().isEmpty()) {
                continue;
            }
            Placement pl = b.placement().get();
            BoundingBox box = pl.bounds();
            if (!box.inflatedBy(1).intersects(area)) {
                continue;
            }
            List<BlockPos> doors = new ArrayList<>(pl.markers("stad:door"));
            for (Placement.Step s : pl.steps()) {
                if (door(s.state())) {
                    doors.add(s.pos());
                }
            }
            if (doors.isEmpty() && world != null && b.done()) {
                BlockPos.betweenClosedStream(box).filter(pos -> door(world.apply(pos))).findFirst().ifPresent(pos -> doors.add(pos.immutable()));
            }
            End found = null;
            for (BlockPos d : doors) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos o = d.relative(dir);
                    int x = o.getX() - land.ox, z = o.getZ() - land.oz;
                    if (!box.isInside(o) && land.inside(x, z)) {
                        found = new End(x, z, "the " + b.name);
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
            }
            if (found == null && doors.isEmpty()) { // no door: the middle of the side facing the hall
                int mx = (box.minX() + box.maxX()) / 2, mz = (box.minZ() + box.maxZ()) / 2;
                int[][] sides = {{mx, box.minZ() - 1}, {mx, box.maxZ() + 1}, {box.minX() - 1, mz}, {box.maxX() + 1, mz}};
                int[] best = null;
                for (int[] sd : sides) {
                    if (land.inside(sd[0] - land.ox, sd[1] - land.oz) && (best == null || dist(sd, colony.center) < dist(best, colony.center))) {
                        best = sd;
                    }
                }
                if (best != null) {
                    found = new End(best[0] - land.ox, best[1] - land.oz, "the " + b.name);
                }
            }
            if (found != null) {
                out.add(found);
            }
        }
        BlockPos hall = colony.center;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int x = hall.getX() + dir.getStepX() - land.ox, z = hall.getZ() + dir.getStepZ() - land.oz;
            if (land.inside(x, z) && groundAt.applyAsInt(x, z) == hall.getY() - 1) {
                out.add(new End(x, z, "the town hall"));
                break;
            }
        }
        return out;
    }

    private static boolean door(BlockState st) {
        return st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER || st.getBlock() instanceof FenceGateBlock;
    }

    private static long dist(int[] c, BlockPos to) {
        long dx = c[0] - to.getX(), dz = c[1] - to.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * What there is to join in a site: the doors, the town hall, and roads and paths running up to the site's edge
     * (one end for each).
     */
    static List<End> ends(Project p, Grid terrain, Colony colony) {
        Land area = new Land(p.min.getX(), p.min.getZ(), p.sx, p.sz);
        List<End> out = doors(colony, area, (x, z) -> {
            int g = Yard.top(terrain, p, x, z);
            return g < 0 ? Integer.MIN_VALUE : g + p.min.getY();
        }, pos -> { // the site and the land round it, as the table sees it
            int x = pos.getX() - p.min.getX() + Project.MARGIN, y = pos.getY() - p.min.getY() + Project.MARGIN, z = pos.getZ() - p.min.getZ() + Project.MARGIN;
            return terrain.inside(x, y, z) ? terrain.get(x, y, z) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        });
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
