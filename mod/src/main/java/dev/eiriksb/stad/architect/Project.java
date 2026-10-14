package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.planner.BuildSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an architect's table is working on (PLAN.md §6.8): the site, the generated building (its spec, which way its
 * door faces and the floor's height), and the hand edits and markers on top. Positions inside the site are packed
 * into one int ({@link #key}). The generated blocks aren't saved; they're drawn up again from the spec.
 */
public final class Project {
    /**
     * a site is at most this wide (positions are packed in nine bits a side: {@link #key}); in practice the town's land
     * is the limit, since the whole site has to be on it
     */
    public static final int MAX_SIDE = 512, MAX_HEIGHT = 32, MIN_HEIGHT = 12;
    private static final int BITS = 9, MASK = (1 << BITS) - 1;
    /** the land shown round the site, faded */
    public static final int MARGIN = 4;

    /** the site's lowest corner; null when there's no site yet */
    public BlockPos min;
    public int sx, sy, sz;
    public String name = "New building";
    public ResourceLocation type = BuildingDef.HOME;
    /** the generated building's spec, or null */
    public BuildSpec spec;
    /** what a field, pen or grove was generated from ({@link Yard}), or null */
    public Yard yard;
    public Direction door = Direction.NORTH;
    /** the floor's height in the site (-1: not worked out yet) */
    public int floorY = -1;
    public final Map<Integer, BlockState> edits = new HashMap<>();
    public final Map<Integer, String> markers = new HashMap<>();
    /** the generated building's blocks, drawn up from {@link #spec} (not saved) */
    public final Map<Integer, BlockState> generated = new HashMap<>();
    /**
     * Columns of the site ({@link #col}) where the town's other buildings stand, which a road stops at (worked out
     * when the land is looked at: {@link Tables#capture}; not saved)
     */
    public final java.util.Set<Integer> keepOut = new java.util.HashSet<>();

    public static int key(int x, int y, int z) {
        return x | z << BITS | y << 2 * BITS;
    }

    public static int kx(int key) {
        return key & MASK;
    }

    public static int kz(int key) {
        return key >> BITS & MASK;
    }

    public static int ky(int key) {
        return key >> 2 * BITS;
    }

    /** A column of the site, packed: x and z (as {@link #key} with y 0). */
    public static int col(int x, int z) {
        return x | z << BITS;
    }

    /** The column a key is in. */
    public static int colOf(int key) {
        return key & (1 << 2 * BITS) - 1;
    }

    /** A key saved before sites could be bigger than 64 (six bits a side). */
    private static int fromOld(int key) {
        return key(key & 63, key >> 12, key >> 6 & 63);
    }

    public boolean hasSite() {
        return min != null;
    }

    public boolean inside(int key) {
        int x = kx(key), y = ky(key), z = kz(key);
        return x < sx && y >= 0 && y < sy && z < sz;
    }

    /** Draws the generated part again from what it was generated from (a building's spec or a yard); why not, or null. */
    public String regenerate(Grid terrain) {
        if (yard != null && Yard.is(type)) {
            return yard.generate(this, terrain);
        }
        return spec != null ? Fit.generate(this, terrain) : null;
    }

    /** Whether it was generated from something (and so can be drawn again). */
    public boolean generatedFrom() {
        return spec != null || yard != null && Yard.is(type);
    }

    /** What the design puts at {@code key}: a hand edit, else the generated building, else null (the land as it is). */
    public BlockState at(int key) {
        BlockState e = edits.get(key);
        return e != null ? e : generated.get(key);
    }

    /** Everything the design puts anywhere. */
    public Map<Integer, BlockState> design() {
        Map<Integer, BlockState> m = new HashMap<>(generated);
        m.putAll(edits);
        return m;
    }

    /** Starts over on a new site (corners in any order; at least {@link #MIN_HEIGHT} high, upwards). */
    public void setSite(BlockPos a, BlockPos b) {
        min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        sx = Math.abs(a.getX() - b.getX()) + 1;
        sy = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, Math.abs(a.getY() - b.getY()) + 1));
        sz = Math.abs(a.getZ() - b.getZ()) + 1;
        spec = null;
        yard = null;
        floorY = -1;
        edits.clear();
        markers.clear();
        generated.clear();
    }

    /** Why a site with these corners won't do, or null. */
    public static String siteProblem(Level level, Colony colony, BlockPos a, BlockPos b) {
        int sx = Math.abs(a.getX() - b.getX()) + 1, sz = Math.abs(a.getZ() - b.getZ()) + 1, sy = Math.abs(a.getY() - b.getY()) + 1;
        if (sx > MAX_SIDE || sz > MAX_SIDE || sy > MAX_HEIGHT) {
            return "That's too big: a site is at most " + MAX_SIDE + " by " + MAX_SIDE + " blocks and " + MAX_HEIGHT + " high (it's "
                    + sx + " by " + sz + ", " + sy + " high).";
        }
        if (sx < 3 || sz < 3) {
            return "That's too small for a building: make it at least 3 by 3 blocks.";
        }
        int minY = Math.min(a.getY(), b.getY());
        if (minY < level.getMinBuildHeight() || minY + Math.max(sy, MIN_HEIGHT) > level.getMaxBuildHeight()) {
            return "That's outside the height you can build at.";
        }
        int x0 = Math.min(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ());
        for (int[] c : new int[][]{{x0, z0}, {x0 + sx - 1, z0}, {x0, z0 + sz - 1}, {x0 + sx - 1, z0 + sz - 1}}) {
            if (!colony.contains(level.dimension(), new BlockPos(c[0], minY, c[1]))) {
                return "The whole site has to be on " + colony.name() + "'s land.";
            }
        }
        return null;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        t.putString("Type", type.toString());
        if (min == null) {
            return t;
        }
        t.put("Min", NbtUtils.writeBlockPos(min));
        t.putIntArray("Size", new int[]{sx, sy, sz});
        if (spec != null) {
            t.putString("Spec", spec.toJson());
        }
        if (yard != null) {
            t.putString("Yard", yard.toJson());
        }
        t.putString("Door", door.getSerializedName());
        t.putInt("Floor", floorY);
        t.putInt("KeyBits", BITS);
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new LinkedHashMap<>();
        int[] keys = new int[edits.size()];
        int[] states = new int[edits.size()];
        int i = 0;
        for (Map.Entry<Integer, BlockState> e : edits.entrySet()) {
            keys[i] = e.getKey();
            states[i++] = index.computeIfAbsent(e.getValue(), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }
        ListTag p = new ListTag();
        palette.forEach(s -> p.add(NbtUtils.writeBlockState(s)));
        t.put("Palette", p);
        t.putIntArray("Keys", keys);
        t.putIntArray("States", states);
        ListTag ms = new ListTag();
        markers.forEach((k, n) -> {
            CompoundTag m = new CompoundTag();
            m.putInt("Key", k);
            m.putString("Name", n);
            ms.add(m);
        });
        t.put("Markers", ms);
        return t;
    }

    public static Project load(CompoundTag t) {
        Project p = new Project();
        if (t.contains("Name")) {
            p.name = t.getString("Name");
        }
        ResourceLocation type = ResourceLocation.tryParse(t.getString("Type"));
        if (type != null) {
            p.type = type;
        }
        if (!t.contains("Min")) {
            return p;
        }
        p.min = NbtUtils.readBlockPos(t, "Min").orElse(null);
        int[] size = t.getIntArray("Size");
        if (p.min == null || size.length != 3) {
            p.min = null;
            return p;
        }
        p.sx = size[0];
        p.sy = size[1];
        p.sz = size[2];
        if (t.contains("Spec")) {
            p.spec = BuildSpec.parse(t.getString("Spec"));
        }
        if (t.contains("Yard")) {
            p.yard = Yard.parse(t.getString("Yard"));
        }
        Direction door = Direction.byName(t.getString("Door"));
        p.door = door != null && door.getAxis().isHorizontal() ? door : Direction.NORTH;
        p.floorY = t.getInt("Floor");
        ListTag pal = t.getList("Palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), pal.getCompound(i)));
        }
        boolean old = t.getInt("KeyBits") != BITS;
        int[] keys = t.getIntArray("Keys"), states = t.getIntArray("States");
        for (int i = 0; i < Math.min(keys.length, states.length); i++) {
            if (states[i] >= 0 && states[i] < palette.size()) {
                p.edits.put(old ? fromOld(keys[i]) : keys[i], palette.get(states[i]));
            }
        }
        for (Tag tag : t.getList("Markers", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) tag;
            p.markers.put(old ? fromOld(m.getInt("Key")) : m.getInt("Key"), m.getString("Name"));
        }
        return p;
    }
}
