package dev.eiriksb.stad.architect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;
import java.util.Locale;

/**
 * What the architect's table generates for land rather than a building (PLAN.md §6.11): a field round water channels,
 * a fenced pen for animals, or a grove of saplings. Like a building's spec it's kept with the project, so changing an
 * option draws it again with the hand edits on top. The workers make the land part (the farmer tills and sows, the
 * lumberjack plants); the builders put up the fence, the path and the rest.
 *
 * @param crop    field: wheat, carrots, potatoes, beetroots or mixed
 * @param water   field: rows (channels) or holes (one in each 9 by 9)
 * @param fence   none, oak, spruce, birch, dark_oak or cobblestone (a wall)
 * @param path    a path round the inside of the fence (field), through the middle (grove)
 * @param animal  pen: sheep, cows, pigs or chickens (who the shepherd brings in)
 * @param tree    grove: oak, birch, spruce, acacia, cherry, jungle or mixed
 * @param spacing grove: blocks from one sapling to the next
 * @param extra   field: lanterns on the corner posts; pen: a shelter with hay and water
 */
public record Yard(String crop, String water, String fence, boolean path, String animal, String tree, int spacing, boolean extra) {
    public static final List<String> CROPS = List.of("wheat", "carrots", "potatoes", "beetroots", "mixed");
    public static final List<String> WATERS = List.of("rows", "holes");
    public static final List<String> FENCES = List.of("oak", "spruce", "birch", "dark_oak", "cobblestone", "none");
    public static final List<String> ANIMALS = List.of("sheep", "cows", "pigs", "chickens");
    public static final List<String> TREES = List.of("oak", "birch", "spruce", "acacia", "cherry", "jungle", "mixed");
    public static final int MIN_SPACING = 3, MAX_SPACING = 6;

    public static final Yard DEFAULT = new Yard("wheat", "rows", "oak", true, "sheep", "oak", 4, true);

    /** Whether a kind of building is land the table generates with a {@link Yard} rather than a {@link dev.eiriksb.stad.planner.BuildSpec}. */
    public static boolean is(ResourceLocation type) {
        return type.equals(BuildingDef.FIELD) || type.equals(BuildingDef.PEN) || type.equals(BuildingDef.GROVE);
    }

    public static boolean is(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id != null && is(id);
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("crop", crop);
        o.addProperty("water", water);
        o.addProperty("fence", fence);
        o.addProperty("path", path);
        o.addProperty("animal", animal);
        o.addProperty("tree", tree);
        o.addProperty("spacing", spacing);
        o.addProperty("extra", extra);
        return o.toString();
    }

    /** Reads one back; anything missing or unknown is the default. */
    public static Yard parse(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return new Yard(pick(o, "crop", CROPS, DEFAULT.crop), pick(o, "water", WATERS, DEFAULT.water), pick(o, "fence", FENCES, DEFAULT.fence),
                    o.has("path") ? o.get("path").getAsBoolean() : DEFAULT.path, pick(o, "animal", ANIMALS, DEFAULT.animal),
                    pick(o, "tree", TREES, DEFAULT.tree), o.has("spacing") ? Math.max(MIN_SPACING, Math.min(MAX_SPACING, o.get("spacing").getAsInt()))
                    : DEFAULT.spacing, o.has("extra") ? o.get("extra").getAsBoolean() : DEFAULT.extra);
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    private static String pick(JsonObject o, String key, List<String> allowed, String fallback) {
        String v = o.has(key) ? o.get(key).getAsString().toLowerCase(Locale.ROOT) : fallback;
        return allowed.contains(v) ? v : fallback;
    }

    // ---- drawing it up ------------------------------------------------------------------------------------------

    /** The crop block a crop name sows (beetroots for "beetroots"...). */
    public static Block cropBlock(String crop) {
        return switch (crop) {
            case "carrots" -> Blocks.CARROTS;
            case "potatoes" -> Blocks.POTATOES;
            case "beetroots" -> Blocks.BEETROOTS;
            default -> Blocks.WHEAT;
        };
    }

    /** The crop name of a crop block ("wheat", "carrots"...), or null when it isn't one we sow. */
    public static String cropOf(BlockState s) {
        if (!(s.getBlock() instanceof CropBlock)) {
            return null;
        }
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return CROPS.contains(id) ? id : null;
    }

    /** The sapling for a kind of tree. */
    public static Block sapling(String tree) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(tree + "_sapling"));
        return b == Blocks.AIR ? Blocks.OAK_SAPLING : b;
    }

    /** "oak" for an oak sapling, or null when it isn't a sapling. */
    public static String treeOf(BlockState s) {
        if (!s.is(BlockTags.SAPLINGS)) {
            return null;
        }
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return id.endsWith("_sapling") ? id.substring(0, id.length() - "_sapling".length()) : null;
    }

    private BlockState fenceState() {
        Block b = fence.equals("cobblestone") ? Blocks.COBBLESTONE_WALL
                : BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(fence + "_fence"));
        return (b == Blocks.AIR ? Blocks.OAK_FENCE : b).defaultBlockState();
    }

    private BlockState gateState(Direction door) {
        String wood = fence.equals("cobblestone") || fence.equals("none") ? "spruce" : fence;
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_fence_gate"));
        return (b == Blocks.AIR ? Blocks.OAK_FENCE_GATE : b).defaultBlockState().setValue(FenceGateBlock.FACING, door);
    }

    private BlockState slabState() {
        String wood = fence.equals("cobblestone") || fence.equals("none") ? "spruce" : fence;
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_slab"));
        return (b == Blocks.AIR ? Blocks.SPRUCE_SLAB : b).defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    /**
     * Draws the yard into {@code p.generated}, fitted to the site: fields and pens are levelled at the site's most
     * common ground height (filled in with dirt below, cleared above), groves follow the land. Returns why it can't, or
     * null.
     */
    public String generate(Project p, Grid terrain) {
        if (!p.hasSite()) {
            return "There's nothing to generate.";
        }
        int min = p.type.equals(BuildingDef.FIELD) ? 3 : p.type.equals(BuildingDef.PEN) ? 4 : 3;
        if (p.sx < min || p.sz < min) {
            return "The site is too small: make it at least " + min + " by " + min + " blocks.";
        }
        p.generated.clear();
        if (p.type.equals(BuildingDef.GROVE)) {
            grove(p, terrain);
            p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
            return null;
        }
        if (p.floorY < 0) {
            p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
        }
        if (p.floorY + 4 > p.sy) {
            return "There isn't room above the ground: lower the site's bottom corner.";
        }
        level(p, terrain, p.type.equals(BuildingDef.FIELD));
        if (p.type.equals(BuildingDef.FIELD)) {
            field(p);
        } else {
            pen(p);
        }
        return null;
    }

    private static BlockState land(Grid terrain, int x, int y, int z) {
        return terrain.get(x + Project.MARGIN, y + Project.MARGIN, z + Project.MARGIN);
    }

    /** The site made level at its floor: dirt where the land dips, the land above cleared ({@code all}: plants too). */
    private static void level(Project p, Grid terrain, boolean all) {
        int f = p.floorY;
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                for (int y = f - 1; y >= 0 && Fit.open(land(terrain, x, y, z)); y--) {
                    p.generated.put(Project.key(x, y, z), Blocks.DIRT.defaultBlockState());
                }
                if (Fit.open(land(terrain, x, f, z))) {
                    p.generated.put(Project.key(x, f, z), (all ? Blocks.DIRT : Blocks.GRASS_BLOCK).defaultBlockState());
                }
                for (int y = f + 1; y < p.sy; y++) {
                    BlockState s = land(terrain, x, y, z);
                    if (!s.isAir() && (all || !s.canBeReplaced() || !s.getFluidState().isEmpty())) {
                        p.generated.put(Project.key(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /** A ring at the site's edge with a gate in the middle of the door's side; returns whether there's a fence. */
    private boolean ring(Project p, int y) {
        if (fence.equals("none")) {
            return false;
        }
        BlockState post = fenceState();
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                if (x == 0 || z == 0 || x == p.sx - 1 || z == p.sz - 1) {
                    p.generated.put(Project.key(x, y, z), post);
                }
            }
        }
        int[] g = gate(p);
        p.generated.put(Project.key(g[0], y, g[1]), gateState(p.door));
        return true;
    }

    /** Where the gate goes: the middle of the side the door faces. */
    static int[] gate(Project p) {
        return switch (p.door) {
            case SOUTH -> new int[]{p.sx / 2, p.sz - 1};
            case EAST -> new int[]{p.sx - 1, p.sz / 2};
            case WEST -> new int[]{0, p.sz / 2};
            default -> new int[]{p.sx / 2, 0};
        };
    }

    /**
     * Farmland round water: channels along the field's long side, nine blocks apart so every plot is within four blocks
     * of water (or a hole in the middle of each 9 by 9), and the crop on top so it shows. A fence and a path round
     * the edge when asked for, and lanterns on the corner posts.
     */
    private void field(Project p) {
        int f = p.floorY;
        boolean fenced = ring(p, f + 1);
        int edge = fenced ? 1 : 0;
        if (path) {
            for (int x = edge; x < p.sx - edge; x++) {
                for (int z = edge; z < p.sz - edge; z++) {
                    if (x == edge || z == edge || x == p.sx - 1 - edge || z == p.sz - 1 - edge) {
                        p.generated.put(Project.key(x, f, z), Blocks.DIRT_PATH.defaultBlockState());
                    }
                }
            }
            edge++;
        }
        int x0 = edge, z0 = edge, w = p.sx - 2 * edge, d = p.sz - 2 * edge;
        if (w <= 0 || d <= 0) {
            return;
        }
        boolean[][] wet = new boolean[w][d];
        if (water.equals("holes") || w <= 9 && d <= 9) {
            for (int a : spots(w)) {
                for (int b : spots(d)) {
                    wet[a][b] = true;
                }
            }
        } else if (w >= d) {
            for (int b : spots(d)) {
                for (int a = 0; a < w; a++) {
                    wet[a][b] = true;
                }
            }
        } else {
            for (int a : spots(w)) {
                for (int b = 0; b < d; b++) {
                    wet[a][b] = true;
                }
            }
        }
        BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE);
        List<String> mix = List.of("wheat", "carrots", "potatoes", "beetroots");
        for (int a = 0; a < w; a++) {
            for (int b = 0; b < d; b++) {
                int x = x0 + a, z = z0 + b;
                if (wet[a][b]) {
                    p.generated.put(Project.key(x, f, z), Blocks.WATER.defaultBlockState());
                    if (f > 0) {
                        p.generated.putIfAbsent(Project.key(x, f - 1, z), Blocks.DIRT.defaultBlockState());
                    }
                    continue;
                }
                p.generated.put(Project.key(x, f, z), farmland);
                String c = crop.equals("mixed") ? mix.get(((w >= d ? b : a) / 9) % mix.size()) : crop;
                p.generated.put(Project.key(x, f + 1, z), cropBlock(c).defaultBlockState());
            }
        }
        if (fenced && extra) {
            for (int[] c : new int[][]{{0, 0}, {p.sx - 1, 0}, {0, p.sz - 1}, {p.sx - 1, p.sz - 1}}) {
                p.generated.put(Project.key(c[0], f + 2, c[1]), Blocks.LANTERN.defaultBlockState());
            }
        }
    }

    /** Where the water goes along a side of {@code n} blocks: as few rows as keep every plot within four blocks, spread evenly. */
    static List<Integer> spots(int n) {
        List<Integer> out = new java.util.ArrayList<>();
        int m = (n + 8) / 9;
        for (int i = 0; i < m; i++) {
            out.add(Math.min(n - 1, (int) Math.round((i + 0.5) * n / m - 0.5)));
        }
        return out;
    }


    /**
     * A fenced pen, levelled, with a gate on the door's side; with {@code extra} a shelter in the far corner (a slab
     * roof on posts over a hay bale) and a cauldron of water, both kept two blocks from the fence so nothing jumps out.
     */
    private void pen(Project p) {
        int f = p.floorY;
        Yard y = fence.equals("none") ? new Yard(crop, water, "oak", path, animal, tree, spacing, extra) : this; // a pen has to have a fence
        y.ring(p, f + 1);
        if (!extra || p.sx < 8 || p.sz < 8) {
            return;
        }
        // the shelter in a back corner (away from the gate), the water in the other
        int far = 4; // the shelter is 4 by 4
        int cx, cz, wx, wz;
        switch (p.door) {
            case SOUTH -> { cx = 1; cz = 1; wx = p.sx - 3; wz = 2; }
            case EAST -> { cx = 1; cz = 1; wx = 2; wz = p.sz - 3; }
            case WEST -> { cx = p.sx - 1 - far; cz = 1; wx = p.sx - 3; wz = p.sz - 3; }
            default -> { cx = 1; cz = p.sz - 1 - far; wx = p.sx - 3; wz = p.sz - 3; }
        }
        BlockState post = y.fenceState(), slab = y.slabState();
        for (int x = cx; x < cx + 4; x++) {
            for (int z = cz; z < cz + 4; z++) {
                boolean corner = (x == cx || x == cx + 3) && (z == cz || z == cz + 3);
                if (corner) {
                    p.generated.put(Project.key(x, f + 1, z), post);
                    p.generated.put(Project.key(x, f + 2, z), post);
                }
                p.generated.put(Project.key(x, f + 3, z), slab);
            }
        }
        p.generated.put(Project.key(cx + 2, f + 1, cz + 2), Blocks.HAY_BLOCK.defaultBlockState());
        p.generated.put(Project.key(wx, f + 1, wz), Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
    }

    /**
     * Saplings in rows {@code spacing} apart, centred on the site, each on the land as it is (dirt put under where
     * the ground isn't); a path through the middle when asked for. Spots in water, on rock or by a tree are skipped.
     */
    private void grove(Project p, Grid terrain) {
        List<String> mix = List.of("oak", "birch", "spruce", "cherry");
        int ox = (p.sx - 1) % spacing / 2, oz = (p.sz - 1) % spacing / 2;
        int i = 0;
        for (int x = ox; x < p.sx; x += spacing) {
            for (int z = oz; z < p.sz; z += spacing) {
                int g = top(terrain, p, x, z);
                if (g < 0 || g + 1 >= p.sy) {
                    continue;
                }
                BlockState ground = land(terrain, x, g, z), above = land(terrain, x, g + 1, z);
                if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LOGS) || !above.isAir() && !above.canBeReplaced()) {
                    continue;
                }
                if (!ground.is(BlockTags.DIRT)) {
                    p.generated.put(Project.key(x, g, z), Blocks.DIRT.defaultBlockState());
                }
                String t = tree.equals("mixed") ? mix.get(i++ % mix.size()) : tree;
                p.generated.put(Project.key(x, g + 1, z), sapling(t).defaultBlockState());
            }
        }
        if (path) {
            boolean alongX = p.sx >= p.sz;
            int mid = alongX ? p.sz / 2 : p.sx / 2;
            for (int a = 0; a < (alongX ? p.sx : p.sz); a++) {
                int x = alongX ? a : mid, z = alongX ? mid : a;
                int g = top(terrain, p, x, z);
                if (g >= 0 && !p.generated.containsKey(Project.key(x, g + 1, z)) && land(terrain, x, g, z).is(BlockTags.DIRT)) {
                    p.generated.put(Project.key(x, g, z), Blocks.DIRT_PATH.defaultBlockState());
                }
            }
        }
    }

    /** The top of the land in a column of the site (not logs or leaves), or -1. */
    private static int top(Grid terrain, Project p, int x, int z) {
        for (int y = p.sy - 1; y >= 0; y--) {
            BlockState s = land(terrain, x, y, z);
            if (!s.getFluidState().isEmpty() || !Fit.open(s) && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS)) {
                return y;
            }
        }
        return -1;
    }
}
