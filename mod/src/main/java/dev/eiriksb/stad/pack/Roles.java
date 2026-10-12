package dev.eiriksb.stad.pack;

import com.google.gson.JsonObject;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.planner.Materials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What each block of a building is for (PLAN.md §6.3): floor, walls, frame, roof, trim, windows, doors, lights,
 * furniture or decoration, guessed from the block's shape and where it sits (stairs and slabs at the eaves start the
 * roof; the bottom layer is the floor; logs are the frame; the most common full block in between is the walls), with
 * the pack author's corrections on top. A {@link Palette} gives the first five roles new materials.
 */
public final class Roles {
    private Roles() {}

    public enum Role {
        FLOOR("floor", true), WALL("walls", true), FRAME("frame", true), ROOF("roof", true), TRIM("trim", true),
        WINDOW("windows", false), DOOR("doors", false), LIGHT("lights", false), FURNITURE("furniture", false),
        DECOR("decoration", false);

        public final String label;
        /** whether a palette changes its material */
        public final boolean restyled;

        Role(String label, boolean restyled) {
            this.label = label;
            this.restyled = restyled;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Role> of(String id) {
            for (Role r : values()) {
                if (r.id().equals(id) || r.label.equals(id)) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
    }

    /** A correction: blocks of this kind that were guessed to be {@code from} are really {@code to}. */
    public record Fix(Block block, Role from, Role to) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(block).toString());
            o.addProperty("from", from.id());
            o.addProperty("to", to.id());
            return o;
        }

        public static Optional<Fix> fromJson(JsonObject o) {
            ResourceLocation id = ResourceLocation.tryParse(o.has("block") ? o.get("block").getAsString() : "");
            Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            Optional<Role> from = Role.of(o.has("from") ? o.get("from").getAsString() : "");
            Optional<Role> to = Role.of(o.has("to") ? o.get("to").getAsString() : "");
            return block.isPresent() && from.isPresent() && to.isPresent()
                    ? Optional.of(new Fix(block.get(), from.get(), to.get())) : Optional.empty();
        }
    }

    /** Every block's role (null for air), by index into the blueprint's entries. */
    public record Analysis(Blueprint blueprint, Role[] guessed, Role[] roles, int floorY, int roofY) {
        public int height() {
            return roofY == Integer.MAX_VALUE ? blueprint.size().getY() : roofY - floorY;
        }

        public boolean hasRoof() {
            return roofY != Integer.MAX_VALUE;
        }
    }

    /** The blocks of one kind that got one role: what the pack editor shows a row for. */
    public record Group(Block block, Role guessed, Role role, int count) {}

    /** The shape a block has, as far as restyling goes (null: none we can swap). */
    public static Materials.Shape shape(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof StairBlock) {
            return Materials.Shape.STAIRS;
        }
        if (b instanceof SlabBlock) {
            return Materials.Shape.SLAB;
        }
        if (b instanceof FenceBlock) {
            return Materials.Shape.FENCE;
        }
        if (b instanceof DoorBlock) {
            return Materials.Shape.DOOR;
        }
        if (b instanceof RotatedPillarBlock && s.is(BlockTags.LOGS)) {
            return Materials.Shape.LOG;
        }
        if (!(b instanceof BaseEntityBlock) && s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && s.canOcclude() && s.getLightEmission() == 0) {
            return Materials.Shape.FULL;
        }
        return null;
    }

    public static Analysis analyse(Blueprint bp) {
        return analyse(bp, List.of());
    }

    public static Analysis analyse(Blueprint bp, List<Fix> fixes) {
        List<Blueprint.Entry> entries = bp.entries();
        int sx = bp.size().getX(), sy = bp.size().getY(), sz = bp.size().getZ();
        BlockState[] grid = new BlockState[sx * sy * sz];
        int floorY = Integer.MAX_VALUE, roofY = Integer.MAX_VALUE;
        int[] eaves = new int[sy];
        for (Blueprint.Entry e : entries) {
            BlockPos p = e.pos();
            grid[(p.getY() * sz + p.getZ()) * sx + p.getX()] = e.state();
            if (e.state().isAir()) {
                continue;
            }
            floorY = Math.min(floorY, p.getY());
            Materials.Shape shape = shape(e.state());
            boolean edge = p.getX() == 0 || p.getZ() == 0 || p.getX() == sx - 1 || p.getZ() == sz - 1; // eaves, not stairs and chairs inside
            if (edge && (shape == Materials.Shape.STAIRS || shape == Materials.Shape.SLAB)) {
                eaves[p.getY()]++;
            }
        }
        // the roof starts where the eaves run along more than one side (a porch roof only runs along the front)
        for (int y = 0; y < sy && roofY == Integer.MAX_VALUE; y++) {
            if (eaves[y] > Math.max(sx, sz)) {
                roofY = y;
            }
        }
        if (floorY == Integer.MAX_VALUE) {
            floorY = 0;
        }
        if (roofY <= floorY) {
            roofY = Integer.MAX_VALUE; // steps at the door, not a roof
        }
        // the walls: the most common full block between the floor and the roof
        Map<Block, Integer> counts = new HashMap<>();
        for (Blueprint.Entry e : entries) {
            int y = e.pos().getY();
            if (y > floorY && y < roofY && shape(e.state()) == Materials.Shape.FULL) {
                counts.merge(e.state().getBlock(), 1, Integer::sum);
            }
        }
        Block walls = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        Map<Block, Map<Role, Role>> fixed = new HashMap<>();
        for (Fix f : fixes) {
            fixed.computeIfAbsent(f.block(), k -> new EnumMap<>(Role.class)).put(f.from(), f.to());
        }
        Role[] guessed = new Role[entries.size()];
        Role[] roles = new Role[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Blueprint.Entry e = entries.get(i);
            if (e.state().isAir()) {
                continue;
            }
            guessed[i] = guess(e, grid, sx, sy, sz, floorY, roofY, walls);
            Map<Role, Role> f = fixed.get(e.state().getBlock());
            roles[i] = f != null ? f.getOrDefault(guessed[i], guessed[i]) : guessed[i];
        }
        return new Analysis(bp, guessed, roles, floorY, roofY);
    }

    private static Role guess(Blueprint.Entry e, BlockState[] grid, int sx, int sy, int sz, int floorY, int roofY, Block walls) {
        BlockState s = e.state();
        Block b = s.getBlock();
        BlockPos p = e.pos();
        int y = p.getY();
        if (s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES) || b instanceof TransparentBlock
                || (b instanceof IronBarsBlock && !s.is(Blocks.IRON_BARS))) {
            return Role.WINDOW;
        }
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) {
            return Role.DOOR;
        }
        if (b instanceof BedBlock || b instanceof BaseEntityBlock || b instanceof CraftingTableBlock) {
            return s.getLightEmission() > 0 && !(b instanceof BedBlock) && !isWorkstation(s) ? Role.LIGHT : Role.FURNITURE;
        }
        if (s.getLightEmission() > 0) {
            return Role.LIGHT;
        }
        Materials.Shape shape = shape(s);
        if (shape == Materials.Shape.LOG) {
            return Role.FRAME;
        }
        if (shape == null) {
            return isWorkstation(s) ? Role.FURNITURE : Role.DECOR;
        }
        boolean edge = p.getX() == 0 || p.getZ() == 0 || p.getX() == sx - 1 || p.getZ() == sz - 1;
        BlockState above = y + 1 < sy ? grid[((y + 1) * sz + p.getZ()) * sx + p.getX()] : null;
        BlockState below = y > 0 ? grid[((y - 1) * sz + p.getZ()) * sx + p.getX()] : null;
        // an upper floor (or the attic's, under the roof): inside the walls, with a room under it and nothing solid on top
        boolean upperFloor = shape == Materials.Shape.FULL && y > floorY && y <= roofY && !edge
                && (above == null || shape(above) != Materials.Shape.FULL) && (below == null || below.isAir());
        if (y >= roofY && !upperFloor) {
            return Role.ROOF;
        }
        if (shape == Materials.Shape.FENCE) {
            return Role.TRIM;
        }
        if (shape == Materials.Shape.FULL) {
            if (y == floorY || upperFloor) {
                return Role.FLOOR;
            }
            if (b == walls) {
                return Role.WALL;
            }
        }
        return Role.TRIM;
    }

    private static boolean isWorkstation(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE
                || b == Blocks.ANVIL || b == Blocks.SMITHING_TABLE || b == Blocks.STONECUTTER || b == Blocks.LOOM
                || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.FLETCHING_TABLE || b == Blocks.GRINDSTONE || b == Blocks.COMPOSTER
                || b == Blocks.BARREL || b == Blocks.LECTERN || b == Blocks.BREWING_STAND || b == Blocks.CAULDRON
                || b == Blocks.BOOKSHELF || b == Blocks.CHISELED_BOOKSHELF;
    }

    /** One row per kind of block and guessed role, biggest first. */
    public static List<Group> groups(Analysis a) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Group> first = new HashMap<>();
        List<Blueprint.Entry> entries = a.blueprint().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (a.roles()[i] == null) {
                continue;
            }
            Block b = entries.get(i).state().getBlock();
            String key = BuiltInRegistries.BLOCK.getKey(b) + "|" + a.guessed()[i];
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            first.putIfAbsent(key, new Group(b, a.guessed()[i], a.roles()[i], 0));
        }
        List<Group> out = new ArrayList<>();
        counts.forEach((key, n) -> {
            Group g = first.get(key);
            out.add(new Group(g.block(), g.guessed(), g.role(), n[0]));
        });
        out.sort((x, y) -> Integer.compare(y.count(), x.count()));
        return out;
    }

    /** The most common known material in each restyled role ("oak" walls, "spruce" roof...). */
    public static Map<Role, String> materials(Analysis a) {
        Map<Role, Map<String, Integer>> counts = new EnumMap<>(Role.class);
        List<Blueprint.Entry> entries = a.blueprint().entries();
        for (int i = 0; i < entries.size(); i++) {
            Role r = a.roles()[i];
            if (r == null || !r.restyled) {
                continue;
            }
            Block block = entries.get(i).state().getBlock();
            String name = Materials.of(block).map(m -> m.getKey().name())
                    .orElse(BuiltInRegistries.BLOCK.getKey(block).getPath());
            counts.computeIfAbsent(r, k -> new HashMap<>()).merge(name, 1, Integer::sum);
        }
        Map<Role, String> out = new EnumMap<>(Role.class);
        counts.forEach((r, c) -> out.put(r, c.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey()));
        return out;
    }

    /** The building with each restyled role in its new material (roles left out of {@code materials} stay as they are). */
    public static Blueprint restyle(Analysis a, Map<Role, Materials.Material> materials) {
        List<Blueprint.Entry> entries = a.blueprint().entries();
        List<Blueprint.Entry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Blueprint.Entry e = entries.get(i);
            Role r = a.roles()[i];
            Materials.Material to = r == null ? null : materials.get(r);
            out.add(to == null ? e : new Blueprint.Entry(e.pos(), restyle(e.state(), to)));
        }
        return a.blueprint().withEntries(out);
    }

    /** One block in another material, same shape and facing; unchanged when the material has no such shape. */
    public static BlockState restyle(BlockState s, Materials.Material to) {
        Materials.Shape shape = shape(s);
        if (shape == null) {
            return s;
        }
        Block target = to.block(shape);
        return target == null || target == s.getBlock() ? s : Materials.copyProperties(s, target);
    }
}
