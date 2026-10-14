package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.building.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link BuildSpec} into a {@link Blueprint}, the same way every time (PLAN.md §6.4): a shell of walls and
 * floors with log framing, the floor split into rooms with doorways, stairs (or a ladder) between floors, furniture
 * for each kind of room, windows, a door, then the roof (gable, hip or flat), a porch and a chimney. Like pack
 * buildings, the front door faces north (z = 0).
 *
 * <p>Rooms are worked out in a "canonical" frame: u runs along the long side of the house, v across it. Rooms are
 * strips across the house, side by side along u, so every room touches both long walls and the doorways line up.
 */
public final class Generator {
    /** What came out: the blocks, and how it went (beds and rooms that fitted, and what didn't). */
    public record Result(Blueprint blueprint, int beds, Map<String, Integer> rooms, List<String> notes) {}

    static final int STOREY = 4;
    private static final String[] BED_COLORS = {"red", "blue", "green", "yellow", "white", "light_blue", "orange", "purple"};

    private final BuildSpec spec;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Materials.Material walls, roof, floor, frame;
    private final int w, d, floors;
    private final boolean swapped;
    private final int lu, lv;
    private final int ox, oz;
    private final int overhang;
    private int doorX;
    private final List<String> notes = new ArrayList<>();
    private final Set<Long> windowless = new HashSet<>();
    private final Map<String, Integer> roomsBuilt = new LinkedHashMap<>();
    private int bedsPlaced;
    private int bedColor;

    private static final class Room {
        final String type;
        int u0, u1;
        int beds;

        Room(String type) {
            this.type = type;
        }
    }

    private static final class Floor {
        final List<Room> rooms = new ArrayList<>();
        final List<Integer> walls = new ArrayList<>();
        final Set<Long> reserved = new HashSet<>();
        final Set<Long> used = new HashSet<>();
        int vDoor;
    }

    private Generator(BuildSpec spec) {
        this.spec = spec;
        walls = Materials.getOr(spec.walls(), "oak");
        roof = Materials.getOr(spec.roofMaterial(), "spruce");
        floor = Materials.getOr(spec.floor(), "oak");
        frame = spec.frame().equals("none") ? null : Materials.get(spec.frame()).filter(Materials.Material::wood).orElse(null);
        w = spec.width();
        d = spec.depth();
        floors = spec.floors();
        swapped = d > w;
        lu = (swapped ? d : w) - 2;
        lv = (swapped ? w : d) - 2;
        overhang = spec.roof().equals("flat") ? 0 : 1;
        ox = overhang;
        oz = Math.max(1, Math.max(overhang, spec.has("porch") ? 2 : 0));
        doorX = w / 2;
    }

    public static Result generate(BuildSpec spec) {
        return new Generator(spec).run();
    }

    private Result run() {
        List<Floor> plans = planFloors();
        shell(plans);
        for (int f = 0; f < floors; f++) {
            interior(plans, f);
        }
        windows();
        door();
        for (Sign sg : signs) { // over the racks, where there's wall to hang it on (not a window)
            BlockState behind = blocks.get(sg.wall().offset(ox, 0, oz));
            if (behind != null && behind.isSolid()) {
                set(sg.at().getX(), sg.at().getY(), sg.at().getZ(), sg.state());
            }
        }
        int top = switch (spec.roof()) {
            case "hip" -> hipRoof();
            case "flat" -> flatRoof();
            default -> gableRoof();
        };
        if (spec.has("porch")) {
            porch();
        }
        if (spec.has("chimney")) {
            chimney(top);
        }
        return new Result(blueprint(), bedsPlaced, roomsBuilt, notes);
    }

    // ---- coordinates --------------------------------------------------------------------------------------------

    /** A sign for a warehouse's rack: where (in body coordinates), the wall behind it, and the sign. */
    private record Sign(BlockPos at, BlockPos wall, BlockState state) {}

    private final List<Sign> signs = new ArrayList<>();

    private void set(int bx, int y, int bz, BlockState state) {
        blocks.put(new BlockPos(bx + ox, y, bz + oz), state);
    }

    private boolean isSet(int bx, int y, int bz) {
        BlockState s = blocks.get(new BlockPos(bx + ox, y, bz + oz));
        return s != null && !s.isAir();
    }

    /** Canonical (u, v) to body (x, z). */
    private int x(int u, int v) {
        return swapped ? v : u;
    }

    private int z(int u, int v) {
        return swapped ? d - 1 - u : v;
    }

    private Direction dirU() {
        return swapped ? Direction.NORTH : Direction.EAST;
    }

    private Direction dirV() {
        return swapped ? Direction.EAST : Direction.SOUTH;
    }

    private void setC(int u, int v, int y, BlockState state) {
        set(x(u, v), y, z(u, v), state);
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private static long key(int f, int x, int z) {
        return ((long) f << 40) | ((long) (x & 0xfffff) << 20) | (z & 0xfffff);
    }

    // ---- planning the floors ------------------------------------------------------------------------------------

    private List<Floor> planFloors() {
        List<String> wanted = new ArrayList<>(spec.rooms());
        if (wanted.isEmpty()) {
            wanted.addAll(switch (spec.type()) {
                case "home" -> List.of("bedroom", "kitchen");
                case "warehouse" -> List.of("storage");
                case "builder_hut" -> List.of("workshop", "bedroom");
                case "workshop" -> List.of("workshop");
                case "barracks" -> List.of("bedroom", "storage");
                default -> List.of("living");
            });
        }
        int beds = spec.beds();
        if (beds == 0 && spec.type().equals("home")) {
            beds = 2;
        } else if (beds == 0 && spec.type().equals("builder_hut")) {
            beds = 1;
        } else if (beds < 2 && spec.type().equals("barracks")) {
            beds = 4;
        }
        if (beds > 0 && !wanted.contains("bedroom") && (spec.type().equals("home") || spec.type().equals("builder_hut")
                || spec.type().equals("barracks"))) {
            wanted.add("bedroom");
        }
        // which floor each room goes on: bedrooms upstairs when there is an upstairs
        List<List<String>> perFloor = new ArrayList<>();
        for (int f = 0; f < floors; f++) {
            perFloor.add(new ArrayList<>());
        }
        int next = 1;
        for (String r : wanted) {
            if (floors > 1 && r.equals("bedroom")) {
                perFloor.get(next).add(r);
                next = next + 1 >= floors ? 1 : next + 1;
            } else {
                perFloor.get(0).add(r);
            }
        }
        for (int f = 0; f < floors; f++) {
            if (perFloor.get(f).isEmpty()) {
                perFloor.get(f).add(f == 0 ? "living" : beds > 0 ? "bedroom" : "storage");
            }
        }
        int maxRooms = Math.max(1, (lu + 1) / 4);
        List<Floor> plans = new ArrayList<>();
        List<Room> bedrooms = new ArrayList<>();
        for (int f = 0; f < floors; f++) {
            Floor fl = new Floor();
            List<String> names = perFloor.get(f);
            for (int i = 0; i < names.size(); i++) {
                if (i < maxRooms) {
                    fl.rooms.add(new Room(names.get(i)));
                } else if (!names.get(i).equals("bedroom")) {
                    notes.add("There wasn't room for the " + names.get(i) + ".");
                }
            }
            fl.rooms.stream().filter(r -> r.type.equals("bedroom")).forEach(bedrooms::add);
            partition(fl, f);
            plans.add(fl);
        }
        // beds: shared out between the bedrooms, or in the first room when there's no bedroom
        if (beds > 0) {
            List<Room> sleep = bedrooms.isEmpty() ? List.of(plans.get(0).rooms.get(0)) : bedrooms;
            for (int i = 0; i < beds; i++) {
                sleep.get(i % sleep.size()).beds++;
            }
        }
        reserveStairs(plans);
        return plans;
    }

    /** Splits a floor into strips along u, with walls between and a doorway in each, out of the front door's way. */
    private void partition(Floor fl, int f) {
        int n = fl.rooms.size();
        int avail = lu - (n - 1);
        int u = 1;
        for (int i = 0; i < n; i++) {
            int width = avail / n + (i < avail % n ? 1 : 0);
            Room r = fl.rooms.get(i);
            r.u0 = u;
            r.u1 = u + width - 1;
            u = r.u1 + 2;
            if (i < n - 1) {
                fl.walls.add(r.u1 + 1);
            }
        }
        if (f == 0 && !swapped) {
            // the front door opens at u = doorX; a wall can't start right behind it
            for (int i = 0; i < fl.walls.size(); i++) {
                if (fl.walls.get(i) != doorX) {
                    continue;
                }
                Room left = fl.rooms.get(i), right = fl.rooms.get(i + 1);
                if (left.u1 - left.u0 >= 3) {
                    left.u1--;
                    right.u0--;
                    fl.walls.set(i, doorX - 1);
                } else if (right.u1 - right.u0 >= 3) {
                    left.u1++;
                    right.u0++;
                    fl.walls.set(i, doorX + 1);
                } else {
                    doorX++;
                }
            }
        }
        fl.vDoor = (lv + 1) / 2;
        if (n > 1) {
            for (int uu = 1; uu <= lu; uu++) {
                fl.reserved.add(key(uu, fl.vDoor));
            }
        }
        if (f == 0) {
            if (swapped) {
                fl.reserved.add(key(lu, doorX));
                fl.reserved.add(key(lu - 1, doorX));
            } else {
                fl.reserved.add(key(doorX, 1));
                fl.reserved.add(key(doorX, 2));
            }
        }
    }

    private int stairU(int f) {
        return f % 2 == 0 ? 1 : lu;
    }

    private int inward(int f) {
        return stairU(f) == 1 ? 2 : lu - 1;
    }

    private boolean ladder() {
        return lv < 6;
    }

    private void reserveStairs(List<Floor> plans) {
        for (int f = 0; f < floors - 1; f++) {
            int su = stairU(f), in = inward(f);
            Floor here = plans.get(f), up = plans.get(f + 1);
            if (ladder()) {
                for (Floor fl : List.of(here, up)) {
                    fl.reserved.add(key(su, 1));
                    fl.reserved.add(key(su, 2));
                }
                windowless.add(key(f, x(su, 0), z(su, 0)));
            } else {
                for (int v = lv - 4; v <= lv; v++) {
                    here.reserved.add(key(su, v));
                }
                here.reserved.add(key(in, lv));
                for (int v = lv - 5; v <= lv - 1; v++) {
                    up.reserved.add(key(su, v));
                }
                up.reserved.add(key(in, lv - 5));
            }
        }
    }

    // ---- the shell ----------------------------------------------------------------------------------------------

    private BlockState log(Direction.Axis axis) {
        Block b = frame != null ? frame.log() : walls.full();
        BlockState s = b.defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    private boolean perimeter(int bx, int bz) {
        return bx == 0 || bx == w - 1 || bz == 0 || bz == d - 1;
    }

    private boolean corner(int bx, int bz) {
        return (bx == 0 || bx == w - 1) && (bz == 0 || bz == d - 1);
    }

    private BlockState beam(int bx, int bz) {
        if (frame == null) {
            return walls.full().defaultBlockState();
        }
        if (corner(bx, bz)) {
            return log(Direction.Axis.Y);
        }
        return log(bz == 0 || bz == d - 1 ? Direction.Axis.X : Direction.Axis.Z);
    }

    private void shell(List<Floor> plans) {
        Block foundation = walls.wood() ? Blocks.COBBLESTONE : walls.full();
        for (int f = 0; f <= floors; f++) {
            int y = f * STOREY;
            boolean top = f == floors;
            for (int bx = 0; bx < w; bx++) {
                for (int bz = 0; bz < d; bz++) {
                    if (perimeter(bx, bz)) {
                        set(bx, y, bz, f == 0 ? foundation.defaultBlockState() : beam(bx, bz));
                    } else {
                        set(bx, y, bz, (top && spec.roof().equals("flat") ? roof.full() : floor.full()).defaultBlockState());
                    }
                }
            }
            if (top) {
                break;
            }
            Set<Long> posts = new HashSet<>();
            for (int wall : plans.get(f).walls) {
                posts.add(key(x(wall, 0), z(wall, 0)));
                posts.add(key(x(wall, lv + 1), z(wall, lv + 1)));
                windowless.add(key(f, x(wall, 0), z(wall, 0)));
                windowless.add(key(f, x(wall, lv + 1), z(wall, lv + 1)));
            }
            for (int dy = 1; dy < STOREY; dy++) {
                for (int bx = 0; bx < w; bx++) {
                    for (int bz = 0; bz < d; bz++) {
                        if (!perimeter(bx, bz)) {
                            continue;
                        }
                        boolean post = frame != null && (corner(bx, bz) || posts.contains(key(bx, bz)));
                        set(bx, y + dy, bz, post ? log(Direction.Axis.Y) : walls.full().defaultBlockState());
                    }
                }
            }
        }
    }

    // ---- inside -------------------------------------------------------------------------------------------------

    private void interior(List<Floor> plans, int f) {
        Floor fl = plans.get(f);
        int y0 = f * STOREY;
        for (int wall : fl.walls) {
            for (int v = 1; v <= lv; v++) {
                for (int dy = 1; dy < STOREY; dy++) {
                    boolean doorway = v == fl.vDoor && dy <= 2;
                    setC(wall, v, y0 + dy, doorway ? Blocks.AIR.defaultBlockState() : floor.full().defaultBlockState());
                }
            }
        }
        if (f < floors - 1) {
            stairs(f, y0);
        }
        for (Room r : fl.rooms) {
            furnish(fl, r, y0);
            roomsBuilt.merge(r.type, 1, Integer::sum);
        }
    }

    private void stairs(int f, int y0) {
        int su = stairU(f);
        if (ladder()) {
            BlockState l = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, dirV());
            for (int dy = 1; dy <= STOREY; dy++) {
                setC(su, 1, y0 + dy, l);
            }
            return;
        }
        BlockState step = floor.stairs().defaultBlockState().setValue(StairBlock.FACING, dirV().getOpposite());
        for (int i = 1; i <= 4; i++) {
            setC(su, lv - i, y0 + i, step);
        }
        // the stairwell: open above the first three steps (mobs need headroom over the step they jump from)
        for (int i = 1; i <= 3; i++) {
            setC(su, lv - i, y0 + STOREY, Blocks.AIR.defaultBlockState());
        }
    }

    /** A wall-side spot in a room: where, and which way the wall is. */
    private record Spot(int u, int v, Direction toWall) {}

    private List<Spot> spots(Floor fl, Room r) {
        List<Spot> out = new ArrayList<>();
        for (int u = r.u0; u <= r.u1; u++) {
            out.add(new Spot(u, lv, dirV()));
        }
        for (int u = r.u0; u <= r.u1; u++) {
            out.add(new Spot(u, 1, dirV().getOpposite()));
        }
        for (int v = 2; v < lv; v++) {
            out.add(new Spot(r.u0, v, dirU().getOpposite()));
            out.add(new Spot(r.u1, v, dirU()));
        }
        out.removeIf(s -> fl.reserved.contains(key(s.u, s.v)));
        return out;
    }

    private boolean free(Floor fl, Room r, int u, int v) {
        return u >= r.u0 && u <= r.u1 && v >= 1 && v <= lv && !fl.reserved.contains(key(u, v)) && !fl.used.contains(key(u, v));
    }

    private int[] step(Spot s, Direction dir) {
        // a step in canonical (u, v) for a direction in the world
        int du = 0, dv = 0;
        if (dir == dirU()) {
            du = 1;
        } else if (dir == dirU().getOpposite()) {
            du = -1;
        } else if (dir == dirV()) {
            dv = 1;
        } else if (dir == dirV().getOpposite()) {
            dv = -1;
        }
        return new int[]{s.u + du, s.v + dv};
    }

    private void furnish(Floor fl, Room r, int y0) {
        List<Spot> spots = spots(fl, r);
        int y = y0 + 1;
        // beds: head against the wall, foot into the room
        int placed = 0;
        for (Spot s : spots) {
            if (placed >= r.beds) {
                break;
            }
            int[] foot = step(s, s.toWall.getOpposite());
            if (!free(fl, r, s.u, s.v) || !free(fl, r, foot[0], foot[1])) {
                continue;
            }
            Block bed = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace(BED_COLORS[bedColor++ % BED_COLORS.length] + "_bed"));
            BlockState head = bed.defaultBlockState().setValue(BedBlock.FACING, s.toWall).setValue(BedBlock.PART, BedPart.HEAD);
            setC(s.u, s.v, y, head);
            setC(foot[0], foot[1], y, head.setValue(BedBlock.PART, BedPart.FOOT));
            fl.used.add(key(s.u, s.v));
            fl.used.add(key(foot[0], foot[1]));
            placed++;
        }
        bedsPlaced += placed;
        if (placed < r.beds) {
            notes.add("Only " + placed + (placed == 1 ? " bed" : " beds") + " fitted in a " + r.type + " (" + r.beds + " wanted).");
        }
        List<Block> items = new ArrayList<>(switch (r.type) {
            case "bedroom" -> List.of(Blocks.CHEST);
            case "kitchen" -> List.of(Blocks.SMOKER, Blocks.CRAFTING_TABLE, Blocks.BARREL);
            case "living" -> List.of(Blocks.BOOKSHELF);
            case "storage" -> List.of(Blocks.CHEST, Blocks.CHEST, Blocks.BARREL, Blocks.CHEST, Blocks.CHEST, Blocks.BARREL);
            case "workshop" -> List.of(Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.BARREL, Blocks.STONECUTTER);
            case "library" -> List.of(Blocks.BOOKSHELF, Blocks.BOOKSHELF, Blocks.LECTERN, Blocks.BOOKSHELF, Blocks.BOOKSHELF);
            default -> List.of();
        });
        boolean racks = spec.type().equals("warehouse") && r.type.equals("storage");
        if (racks) { // a warehouse: racks all round the walls, two high, a sign on each for its shelf (building.Warehouse)
            items.clear();
            for (int i = 0; i < spots.size(); i++) {
                items.add(i % 3 == 2 ? Blocks.BARREL : Blocks.CHEST);
            }
        }
        int i = 0;
        for (Spot s : spots) {
            if (i >= items.size()) {
                break;
            }
            if (!free(fl, r, s.u, s.v)) {
                continue;
            }
            BlockState state = items.get(i++).defaultBlockState();
            if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
                state = state.setValue(HorizontalDirectionalBlock.FACING, s.toWall.getOpposite());
            } else if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, Direction.UP);
            }
            setC(s.u, s.v, y, state);
            if (racks) {
                setC(s.u, s.v, y + 1, state);
                Materials.Material wood = floor.wood() ? floor : Materials.getOr("oak", "oak");
                Block sign = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                        wood.name() + "_wall_sign"));
                if (!(sign instanceof net.minecraft.world.level.block.WallSignBlock)) {
                    sign = Blocks.OAK_WALL_SIGN;
                }
                int[] wall = step(s, s.toWall);
                signs.add(new Sign(new BlockPos(x(s.u, s.v), y + 2, z(s.u, s.v)), new BlockPos(x(wall[0], wall[1]), y + 2, z(wall[0], wall[1])),
                        sign.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, s.toWall.getOpposite())));
            }
            fl.used.add(key(s.u, s.v));
        }
        int uc = (r.u0 + r.u1) / 2, vc = (1 + lv) / 2;
        if (r.type.equals("living") && r.u1 - r.u0 >= 2 && lv >= 3) {
            table(fl, r, uc, vc, y);
        }
        // a lantern hanging from the ceiling in the middle of the room
        setC(uc, vc, y0 + STOREY - 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /** A table (a fence post with a carpet on it) with a chair on either side. */
    private void table(Floor fl, Room r, int uc, int vc, int y) {
        if (!free(fl, r, uc, vc)) {
            return;
        }
        Materials.Material wood = floor.wood() ? floor : Materials.getOr("oak", "oak");
        setC(uc, vc, y, wood.fence().defaultBlockState());
        setC(uc, vc, y + 1, Blocks.WHITE_CARPET.defaultBlockState());
        fl.used.add(key(uc, vc));
        for (Direction side : List.of(dirU(), dirU().getOpposite())) {
            int[] c = step(new Spot(uc, vc, side), side);
            if (free(fl, r, c[0], c[1])) {
                setC(c[0], c[1], y, wood.stairs().defaultBlockState().setValue(StairBlock.FACING, side));
                fl.used.add(key(c[0], c[1]));
            }
        }
    }

    // ---- windows and the door -----------------------------------------------------------------------------------

    private static List<Integer> windowSpots(int length) {
        List<Integer> out = new ArrayList<>();
        for (int p = 1; p <= length - 2; p++) {
            if (length % 2 == 1 ? Math.abs(p - (length - 1) / 2) % 3 == 0
                    : (p <= length / 2 - 1 ? (length / 2 - 1 - p) % 3 == 0 : (p - length / 2) % 3 == 0)) {
                out.add(p);
            }
        }
        return out;
    }

    private void windows() {
        windowless.add(key(0, doorX, 0));
        windowless.add(key(0, doorX - 1, 0));
        windowless.add(key(0, doorX + 1, 0));
        if (spec.has("chimney")) {
            for (int f = 0; f < floors; f++) {
                windowless.add(key(f, w - 1, d / 2));
            }
        }
        boolean big = spec.has("big_windows");
        for (int f = 0; f < floors; f++) {
            int y = f * STOREY + 2;
            List<int[]> cells = new ArrayList<>();
            for (int p : windowSpots(w)) {
                cells.add(new int[]{p, 0});
                cells.add(new int[]{p, d - 1});
            }
            for (int p : windowSpots(d)) {
                cells.add(new int[]{0, p});
                cells.add(new int[]{w - 1, p});
            }
            for (int[] c : cells) {
                if (windowless.contains(key(f, c[0], c[1])) || corner(c[0], c[1])) {
                    continue;
                }
                set(c[0], y, c[1], Blocks.GLASS_PANE.defaultBlockState());
                if (big) {
                    set(c[0], y + 1, c[1], Blocks.GLASS_PANE.defaultBlockState());
                }
            }
        }
    }

    private Block doorBlock() {
        if (frame != null && frame.door() != null) {
            return frame.door();
        }
        if (floor.door() != null) {
            return floor.door();
        }
        return Blocks.OAK_DOOR;
    }

    private void door() {
        BlockState lower = doorBlock().defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        set(doorX, 1, 0, lower);
        set(doorX, 2, 0, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH);
        set(doorX - 1, 2, -1, torch);
        set(doorX + 1, 2, -1, torch);
    }

    // ---- roofs --------------------------------------------------------------------------------------------------

    private BlockState roofStairs(Direction facing) {
        return roof.stairs().defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    private BlockState roofSlab() {
        return roof.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    /** A pitched roof with its ridge along the long side, gable walls at the ends; returns its top y. */
    private int gableRoof() {
        boolean ridgeX = w >= d;
        int across = (ridgeX ? d : w) + 2;
        int alongMin = -1, alongMax = ridgeX ? w : d;
        int base = floors * STOREY;
        int top = base;
        for (int k = 0; ; k++) {
            int s1 = k, s2 = across - 1 - k;
            if (s1 > s2) {
                break;
            }
            int y = base + k;
            top = y;
            for (int a = alongMin; a <= alongMax; a++) {
                if (s1 == s2) {
                    put(ridgeX, a, s1 - 1, y, roofSlab());
                } else {
                    put(ridgeX, a, s1 - 1, y, roofStairs(ridgeX ? Direction.SOUTH : Direction.EAST));
                    put(ridgeX, a, s2 - 1, y, roofStairs(ridgeX ? Direction.NORTH : Direction.WEST));
                }
            }
            if (k >= 1) {
                int end2 = (ridgeX ? w : d) - 1;
                for (int s = s1 + 1; s < s2; s++) {
                    boolean window = k == 1 && s2 - s1 - 1 >= 3 && s == across / 2;
                    BlockState state = window ? Blocks.GLASS_PANE.defaultBlockState() : walls.full().defaultBlockState();
                    put(ridgeX, 0, s - 1, y, state);
                    put(ridgeX, end2, s - 1, y, state);
                }
            }
        }
        return top;
    }

    private void put(boolean ridgeX, int along, int acrossCoord, int y, BlockState state) {
        if (ridgeX) {
            set(along, y, acrossCoord, state);
        } else {
            set(acrossCoord, y, along, state);
        }
    }

    /** Sloping in from all four sides; returns its top y. */
    private int hipRoof() {
        int a = w + 2, b = d + 2;
        int base = floors * STOREY;
        int topLayer = (Math.min(a, b) - 1) / 2;
        boolean slabTop = Math.min(a, b) % 2 == 1;
        int top = base;
        for (int i = 0; i < a; i++) {
            for (int j = 0; j < b; j++) {
                int ex = Math.min(i, a - 1 - i), ez = Math.min(j, b - 1 - j);
                int k = Math.min(ex, ez);
                int y = base + k;
                top = Math.max(top, y);
                BlockState state;
                if (slabTop && k == topLayer) {
                    state = roofSlab();
                } else if (ez <= ex) {
                    state = roofStairs(j < b / 2 ? Direction.SOUTH : Direction.NORTH);
                } else {
                    state = roofStairs(i < a / 2 ? Direction.EAST : Direction.WEST);
                }
                set(i - 1, y, j - 1, state);
            }
        }
        return top;
    }

    /** A flat roof with a low parapet; returns its top y. */
    private int flatRoof() {
        int y = floors * STOREY + 1;
        for (int bx = 0; bx < w; bx++) {
            for (int bz = 0; bz < d; bz++) {
                if (perimeter(bx, bz)) {
                    set(bx, y, bz, walls.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                }
            }
        }
        return y;
    }

    // ---- extras -------------------------------------------------------------------------------------------------

    private void porch() {
        Materials.Material wood = frame != null ? frame : floor.wood() ? floor : Materials.getOr("oak", "oak");
        for (int bx = 0; bx < w; bx++) {
            for (int bz = -2; bz <= -1; bz++) {
                set(bx, 0, bz, wood.full().defaultBlockState());
                if (!isSet(bx, STOREY, bz)) {
                    set(bx, STOREY, bz, roofSlab()); // under the main roof's eaves when it's a one-storey house
                }
            }
        }
        for (int bx : new int[]{0, w - 1}) {
            for (int y = 1; y < STOREY; y++) {
                set(bx, y, -2, wood.fence().defaultBlockState());
            }
        }
    }

    private void chimney(int roofTop) {
        Block brick = walls.full() == Blocks.BRICKS ? Blocks.COBBLESTONE : Blocks.BRICKS;
        int bz = d / 2;
        for (int y = 0; y <= roofTop + 1; y++) {
            set(w, y, bz, brick.defaultBlockState());
        }
        set(w, roofTop + 2, bz, Blocks.CAMPFIRE.defaultBlockState());
    }

    private Blueprint blueprint() {
        int sx = 0, sy = 0, sz = 0;
        for (BlockPos p : blocks.keySet()) {
            sx = Math.max(sx, p.getX() + 1);
            sy = Math.max(sy, p.getY() + 1);
            sz = Math.max(sz, p.getZ() + 1);
        }
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = blocks.get(p);
                    if (s != null) {
                        entries.add(new Blueprint.Entry(p, s));
                    } else if (y > 0) {
                        entries.add(new Blueprint.Entry(p, Blocks.AIR.defaultBlockState())); // clear the site, but leave the ground
                    }
                }
            }
        }
        return new Blueprint(new Vec3i(sx, sy, sz), List.copyOf(entries));
    }
}
