package dev.eiriksb.stad.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks round a building someone made, from inside (PLAN.md §6.5): fills the room you're standing in, and the rooms
 * behind its doors, with a limit on size. Doors that lead outside are the way in; gaps to the outside mean the walls
 * aren't closed; open sky inside means the roof has holes. Everything along the way (beds, chests, a crafting
 * table...) is counted. Server thread.
 */
public final class Survey {
    private Survey() {}

    /** the most a room may hold before it counts as outdoors */
    static final int MAX_CELLS = 4000;
    static final int REACH = 24, UP = 20, DOWN = 6, MAX_ROOMS = 16, RAY = 12;

    /**
     * @param problem   why it can't be surveyed at all (not inside anything), or null
     * @param cells     the air inside
     * @param box       the building: the rooms with their walls, floor and roof
     * @param holes     open sky inside (the roof's holes)
     * @param gaps      openings in the walls to the outside
     * @param doors     the doors to the outside (their lower halves)
     * @param front     which way the first of them faces out (north if none)
     */
    public record Result(String problem, Set<BlockPos> cells, BoundingBox box, Contents contents, int rooms, int holes, int gaps,
                         List<BlockPos> doors, Direction front) {
        public boolean ok() {
            return problem == null;
        }
    }

    private static final class Room {
        final Set<BlockPos> cells = new HashSet<>();
        /** door (lower half) -> the cell on its far side */
        final Map<BlockPos, BlockPos> doors = new HashMap<>();
        final Map<BlockPos, Direction> doorDir = new HashMap<>();
        final Set<BlockPos> outsideDoors = new HashSet<>();
        int holes, gaps;
        boolean escaped;
        boolean outside() {
            return escaped || gaps > 0;
        }
    }

    public static Result of(ServerLevel level, BlockPos at) {
        BlockPos start = at;
        if (!passable(level, start) && passable(level, start.above())) {
            start = start.above();
        }
        if (!passable(level, start)) {
            return fail("Stand inside the building.");
        }
        if (sky(level, start) && !underHole(level, start)) {
            return fail(enclosed(level, start) ? "There's no roof over it." : "Stand inside the building.");
        }
        Map<BlockPos, Room> owner = new HashMap<>();
        List<Room> rooms = new ArrayList<>();
        Room first = explore(level, start, start, owner);
        if (first.escaped) {
            return fail(first.holes > first.cells.size() / 3 ? "That's open to the sky: there's no roof to speak of."
                    : "That's too big or too open to be a building. Stand inside a house.");
        }
        rooms.add(first);
        Set<BlockPos> outsideDoors = new LinkedHashSet<>();
        Map<BlockPos, Direction> outward = new HashMap<>();
        ArrayDeque<Room> todo = new ArrayDeque<>();
        todo.add(first);
        while (!todo.isEmpty()) {
            Room room = todo.poll();
            for (Map.Entry<BlockPos, BlockPos> d : room.doors.entrySet()) {
                BlockPos beyond = d.getValue();
                Room other = owner.get(beyond);
                if (other == null) {
                    if (!passable(level, beyond)) {
                        continue; // a door into a wall
                    }
                    if (sky(level, beyond) && !underHole(level, beyond)) {
                        outsideDoors.add(d.getKey());
                        outward.put(d.getKey(), room.doorDir.get(d.getKey()));
                        continue;
                    }
                    if (rooms.size() >= MAX_ROOMS) {
                        continue;
                    }
                    other = explore(level, beyond, start, owner);
                    if (!other.outside()) {
                        rooms.add(other);
                        todo.add(other);
                    }
                }
                if (other.outside()) {
                    outsideDoors.add(d.getKey());
                    outward.put(d.getKey(), room.doorDir.get(d.getKey()));
                }
            }
        }
        Set<BlockPos> cells = new HashSet<>();
        int holes = 0;
        for (Room r : rooms) {
            cells.addAll(r.cells);
            holes += r.holes;
        }
        Contents contents = new Contents();
        Set<BlockPos> seen = new HashSet<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos c : cells) {
            if (seen.add(c)) {
                contents.add(level.getBlockState(c));
            }
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (!cells.contains(n) && seen.add(n)) {
                    contents.add(level.getBlockState(n));
                }
            }
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            minZ = Math.min(minZ, c.getZ());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
            maxZ = Math.max(maxZ, c.getZ());
        }
        // the roof: whatever stands above the rooms, a little way up
        int top = maxY + 1;
        for (int y = maxY + 1; y <= maxY + 12; y++) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        top = y;
                    }
                }
            }
        }
        BoundingBox box = new BoundingBox(minX - 1, minY - 1, minZ - 1, maxX + 1, top, maxZ + 1);
        contents.walls = first.gaps == 0;
        contents.roof = holes == 0;
        contents.entrance = !outsideDoors.isEmpty();
        List<BlockPos> doors = new ArrayList<>(outsideDoors);
        Direction front = doors.isEmpty() ? Direction.NORTH : outward.getOrDefault(doors.getFirst(), Direction.NORTH);
        return new Result(null, cells, box, contents, rooms.size(), holes, first.gaps, doors, front);
    }

    private static Result fail(String problem) {
        return new Result(problem, Set.of(), null, new Contents(), 0, 0, 0, List.of(), Direction.NORTH);
    }

    /** Fills one room from {@code seed}: up to walls, doors and the outdoors. */
    private static Room explore(ServerLevel level, BlockPos seed, BlockPos start, Map<BlockPos, Room> owner) {
        Room room = new Room();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        room.cells.add(seed);
        owner.put(seed, room);
        if (sky(level, seed)) {
            room.holes++;
        }
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (Math.abs(n.getX() - start.getX()) > REACH || Math.abs(n.getZ() - start.getZ()) > REACH
                        || n.getY() - start.getY() > UP || start.getY() - n.getY() > DOWN) {
                    room.escaped = true;
                    return room;
                }
                if (room.cells.contains(n)) {
                    continue;
                }
                BlockState s = level.getBlockState(n);
                if (s.getBlock() instanceof DoorBlock) {
                    if (d.getAxis().isHorizontal()) {
                        BlockPos lower = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? n : n.below();
                        BlockPos beyond = n.relative(d);
                        if (s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                            beyond = beyond.below();
                        }
                        room.doors.putIfAbsent(lower.immutable(), beyond.immutable());
                        room.doorDir.putIfAbsent(lower.immutable(), d);
                    }
                    continue;
                }
                if (!passable(level, n, s)) {
                    continue;
                }
                Room other = owner.get(n);
                if (other != null && other != room) {
                    continue; // another room reached round a corner (shouldn't happen without a door)
                }
                if (sky(level, n)) {
                    if (!underHole(level, n)) {
                        if (d.getAxis().isHorizontal()) {
                            room.gaps++;
                        } else {
                            room.holes++;
                        }
                        continue;
                    }
                    room.holes++;
                }
                room.cells.add(n);
                owner.put(n, room);
                if (room.cells.size() > MAX_CELLS) {
                    room.escaped = true;
                    return room;
                }
                queue.add(n);
            }
        }
        return room;
    }

    /** the most open sky a hole in a roof can let in, on one level */
    static final int MAX_HOLE = 16;

    /**
     * Whether open sky at {@code pos} is a hole in a roof rather than the outdoors: the open patch it's in (on that
     * level, between walls) is small.
     */
    private static boolean underHole(ServerLevel level, BlockPos pos) {
        Set<BlockPos> patch = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        patch.add(pos);
        queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = c.relative(d);
                if (!patch.contains(n) && passable(level, n) && sky(level, n)) {
                    patch.add(n);
                    if (patch.size() > MAX_HOLE) {
                        return false;
                    }
                    queue.add(n);
                }
            }
        }
        return true;
    }

    /** Whether there are walls all round {@code pos}, near enough. */
    private static boolean enclosed(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int k = 1; k <= RAY && !hit; k++) {
                hit = !passable(level, pos.relative(d, k));
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    /** Whether nothing solid is overhead (by the height map, which is up to date even for blocks placed this tick). */
    static boolean sky(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    static boolean passable(ServerLevel level, BlockPos pos) {
        return passable(level, pos, level.getBlockState(pos));
    }

    private static boolean passable(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof TrapDoorBlock) {
            return false;
        }
        return s.getCollisionShape(level, pos).isEmpty();
    }
}
