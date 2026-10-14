package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.Contents;
import dev.eiriksb.stad.building.Requirements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a table's design against what its kind of building needs ({@link Requirements}), the way the town would once
 * it's built: what's in it, and whether it's closed in. A room is air the outside can't reach that's next to
 * something the design put there (so caves in the ground don't count); a door between a room and the outside is the
 * way in. Both sides: the screen runs it after every edit, the server before planning.
 */
public final class DesignCheck {
    private DesignCheck() {}

    public record Row(String what, boolean met, boolean required) {}

    public record Result(Contents contents, List<Row> rows) {
        /** Whether it has everything it must have. */
        public boolean ready() {
            return rows.stream().allMatch(r -> r.met() || !r.required());
        }

        public List<String> missing() {
            return rows.stream().filter(r -> r.required() && !r.met()).map(Row::what).toList();
        }
    }

    /** {@code near}: what's round the site ({@link dev.eiriksb.stad.building.Surroundings}), or null when unknown. */
    public static Result check(ResourceLocation type, Grid terrain, int sx, int sy, int sz, Map<Integer, BlockState> design,
                               Map<Integer, String> markers, java.util.Set<String> near) {
        Contents c = contents(terrain, sx, sy, sz, design, markers);
        c.near = near;
        List<Row> rows = new ArrayList<>();
        for (Requirements.Need n : Requirements.needs(type)) {
            rows.add(new Row(n.what(), n.met().test(c), n.required()));
        }
        return new Result(c, rows);
    }

    static Contents contents(Grid terrain, int sx, int sy, int sz, Map<Integer, BlockState> design, Map<Integer, String> markers) {
        BlockState[] cells = new BlockState[sx * sy * sz];
        boolean[] touched = new boolean[cells.length];
        Contents c = new Contents();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    BlockState d = design.get(Project.key(x, y, z));
                    cells[i] = d != null ? d : terrain.get(x + Project.MARGIN, y + Project.MARGIN, z + Project.MARGIN);
                    touched[i] = d != null && !d.isAir();
                    c.add(cells[i]);
                    if (touched[i]) {
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                }
            }
        }
        c.markers.addAll(markers.values());
        c.height = hi >= lo ? hi - lo + 1 : 0;
        land(c, cells, touched, sx, sy, sz);
        Enclosure open = enclosure(cells, touched, sx, sy, sz, true);
        if (open.rooms) {
            c.walls = c.roof = true;
            c.entrance = open.entrance;
        } else {
            c.walls = enclosure(cells, touched, sx, sy, sz, false).rooms; // closed in once the sky is shut: walls but no roof
            c.roof = false;
            c.entrance = false;
        }
        return c;
    }

    /**
     * What matters for fields, pens and groves: farmland left dry (no water within four blocks on its level or the one
     * above, as the game has it), saplings too close together, and whether a fence the design put up shuts in some
     * ground an animal can stand on (each level on its own: animals can't climb over a fence).
     */
    private static void land(Contents c, BlockState[] cells, boolean[] touched, int sx, int sy, int sz) {
        boolean fences = false;
        for (int i = 0; i < cells.length; i++) {
            BlockState s = cells[i];
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            if (s.is(net.minecraft.world.level.block.Blocks.FARMLAND) && !wet(cells, x, y, z, sx, sy, sz)) {
                c.dry++;
            } else if (s.is(net.minecraft.tags.BlockTags.SAPLINGS) && touched[i] && crowded(cells, x, y, z, sx, sy, sz)) {
                c.crowded++;
            }
            fences |= touched[i] && (fence(s) || !passable(s) && y > 0);
        }
        c.penned = fences && penned(cells, touched, sx, sy, sz);
    }

    private static boolean wet(BlockState[] cells, int x, int y, int z, int sx, int sy, int sz) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx >= 0 && nz >= 0 && nx < sx && nz < sz && ny < sy
                            && cells[(ny * sz + nz) * sx + nx].getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean crowded(BlockState[] cells, int x, int y, int z, int sx, int sy, int sz) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if ((dx != 0 || dz != 0) && nx >= 0 && ny >= 0 && nz >= 0 && nx < sx && ny < sy && nz < sz
                            && cells[(ny * sz + nz) * sx + nx].is(net.minecraft.tags.BlockTags.SAPLINGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean fence(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock || s.getBlock() instanceof net.minecraft.world.level.block.WallBlock
                || s.getBlock() instanceof FenceGateBlock;
    }

    private static boolean penned(BlockState[] cells, boolean[] touched, int sx, int sy, int sz) {
        for (int y = 1; y < sy; y++) {
            boolean[] out = new boolean[sx * sz];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if ((x == 0 || z == 0 || x == sx - 1 || z == sz - 1) && passable(cells[(y * sz + z) * sx + x])) {
                        out[z * sx + x] = true;
                        q.add(z * sx + x);
                    }
                }
            }
            while (!q.isEmpty()) {
                int i = q.poll(), x = i % sx, z = i / sx;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx >= 0 && nz >= 0 && nx < sx && nz < sz && !out[nz * sx + nx] && passable(cells[(y * sz + nz) * sx + nx])) {
                        out[nz * sx + nx] = true;
                        q.add(nz * sx + nx);
                    }
                }
            }
            int inside = 0;
            boolean byFence = false;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    if (out[z * sx + x] || !passable(cells[i]) || passable(cells[((y - 1) * sz + z) * sx + x])) {
                        continue;
                    }
                    inside++;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0], nz = z + d[1];
                        if (nx >= 0 && nz >= 0 && nx < sx && nz < sz) {
                            int n = (y * sz + nz) * sx + nx;
                            byFence |= touched[n] && !passable(cells[n]); // a fence, a wall, a palisade the design put up
                        }
                    }
                }
            }
            if (inside >= 4 && byFence) {
                return true;
            }
        }
        return false;
    }

    private record Enclosure(boolean rooms, boolean entrance) {}

    /** Floods the outdoors in from round the site (and down from the sky when {@code sky}), then finds the rooms left. */
    private static Enclosure enclosure(BlockState[] cells, boolean[] touched, int sx, int sy, int sz, boolean sky) {
        int ex = sx + 2, ey = sy + 2, ez = sz + 2;
        boolean[] outside = new boolean[ex * ey * ez];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < ey; y++) {
            for (int z = 0; z < ez; z++) {
                for (int x = 0; x < ex; x++) {
                    boolean shell = x == 0 || z == 0 || x == ex - 1 || z == ez - 1 || y == 0 || (sky && y == ey - 1);
                    if (shell) {
                        outside[(y * ez + z) * ex + x] = true;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (Direction d : Direction.values()) {
                int x = p[0] + d.getStepX(), y = p[1] + d.getStepY(), z = p[2] + d.getStepZ();
                if (x <= 0 || y <= 0 || z <= 0 || x >= ex - 1 || y >= ey - 1 || z >= ez - 1) {
                    continue;
                }
                int e = (y * ez + z) * ex + x;
                if (!outside[e] && passable(cells[((y - 1) * sz + (z - 1)) * sx + (x - 1)])) {
                    outside[e] = true;
                    queue.add(new int[]{x, y, z});
                }
            }
        }
        boolean[] seen = new boolean[cells.length];
        boolean rooms = false, entrance = false;
        for (int start = 0; start < cells.length; start++) {
            if (seen[start] || !passable(cells[start]) || outside[ext(start, sx, sz, ex, ez)]) {
                continue;
            }
            List<Integer> room = new ArrayList<>();
            boolean byDesign = false, byDoor = false;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(start);
            seen[start] = true;
            while (!q.isEmpty()) {
                int i = q.poll();
                room.add(i);
                int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
                for (Direction d : Direction.values()) {
                    int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
                        continue;
                    }
                    int n = (ny * sz + nz) * sx + nx;
                    byDesign |= touched[n];
                    if (cells[n].getBlock() instanceof DoorBlock && d.getAxis().isHorizontal() && doorToOutside(n, d, sx, sy, sz, outside, ex, ez)) {
                        byDoor = true;
                    }
                    if (!seen[n] && passable(cells[n]) && !outside[ext(n, sx, sz, ex, ez)]) {
                        seen[n] = true;
                        q.add(n);
                    }
                }
            }
            if (room.size() >= 2 && byDesign) {
                rooms = true;
                entrance |= byDoor;
            }
        }
        return new Enclosure(rooms, entrance);
    }

    /** Whether the far side of a door (seen from a room, going {@code d}) is outdoors. */
    private static boolean doorToOutside(int door, Direction d, int sx, int sy, int sz, boolean[] outside, int ex, int ez) {
        int x = door % sx + d.getStepX(), z = (door / sx) % sz + d.getStepZ(), y = door / (sx * sz);
        return outside[((y + 1) * ez + (z + 1)) * ex + (x + 1)] || y > 0 && outside[(y * ez + (z + 1)) * ex + (x + 1)];
    }

    private static int ext(int i, int sx, int sz, int ex, int ez) {
        int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
        return ((y + 1) * ez + (z + 1)) * ex + (x + 1);
    }

    public static boolean passable(BlockState s) {
        if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof TrapDoorBlock) {
            return false;
        }
        return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }
}
