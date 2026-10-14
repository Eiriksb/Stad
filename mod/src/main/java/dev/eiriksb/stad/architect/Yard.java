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
 * a fenced pen for animals, a grove of saplings, a quarry, a dock, a guard post, a wall, market stalls or a road. Like a building's spec it's kept with the project, so changing an
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
 * @param extra   field: lanterns on the corner posts; pen: a shelter with hay and water; wall: watch posts on its corners;
 *                quarry and dock: lamps
 * @param depth   quarry: how deep the miner digs it ({@link dev.eiriksb.stad.building.Mine#BEDROCK}: down to bedrock)
 * @param wall    wall: logs, stone_bricks or cobblestone
 * @param height  wall: how high
 * @param paving  stalls and road: dirt_path, gravel, cobblestone, stone_bricks or planks
 * @param awning  stalls: the colour of the stalls' roofs (or striped, red and white)
 * @param width   road: how wide
 */
public record Yard(String crop, String water, String fence, boolean path, String animal, String tree, int spacing, boolean extra,
                   int depth, String wall, int height, String paving, String awning, int width) {
    public static final List<String> CROPS = List.of("wheat", "carrots", "potatoes", "beetroots", "mixed");
    public static final List<String> WATERS = List.of("rows", "holes");
    public static final List<String> FENCES = List.of("oak", "spruce", "birch", "dark_oak", "cobblestone", "none");
    public static final List<String> ANIMALS = List.of("sheep", "cows", "pigs", "chickens");
    public static final List<String> TREES = List.of("oak", "birch", "spruce", "acacia", "cherry", "jungle", "mixed");
    public static final int MIN_SPACING = 3, MAX_SPACING = 6;
    public static final List<Integer> DEPTHS = List.of(8, 16, 24, 32, 48, 64, dev.eiriksb.stad.building.Mine.BEDROCK); // the last: down to bedrock
    public static final List<String> WALLS = List.of("logs", "stone_bricks", "cobblestone");
    public static final int MIN_HEIGHT = 2, MAX_HEIGHT = 5;
    public static final List<String> PAVINGS = List.of("dirt_path", "gravel", "cobblestone", "stone_bricks", "planks");
    public static final List<String> AWNINGS = List.of("striped", "red", "yellow", "blue", "green", "white");
    public static final int MIN_WIDTH = 1, MAX_WIDTH = 4;

    public static final Yard DEFAULT = new Yard("wheat", "rows", "oak", true, "sheep", "oak", 4, true, 16, "logs", 3, "dirt_path", "striped", 3);

    /** The land kinds, and the job each is for. */
    public static final List<ResourceLocation> KINDS = List.of(BuildingDef.FIELD, BuildingDef.PEN, BuildingDef.GROVE, BuildingDef.QUARRY,
            BuildingDef.DOCK, BuildingDef.POST, BuildingDef.WALL, BuildingDef.STALLS, BuildingDef.APIARY, BuildingDef.ROAD);

    /** Whether a kind of building is land the table generates with a {@link Yard} rather than a {@link dev.eiriksb.stad.planner.BuildSpec}. */
    public static boolean is(ResourceLocation type) {
        return KINDS.contains(type);
    }

    /** Who works it: "farmer" for a field... (null for a road: it's everyone's) */
    public static String job(ResourceLocation type) {
        return switch (type.getPath()) {
            case "field" -> "farmer";
            case "pen" -> "shepherd";
            case "grove" -> "lumberjack";
            case "quarry" -> "miner";
            case "dock" -> "fisher";
            case "stalls" -> "merchant";
            case "apiary" -> "beekeeper";
            case "road" -> null;
            default -> "guard";
        };
    }

    /**
     * Whether a marker is one the table put there when it laid out a {@code type} (and so goes when it's laid out again,
     * or the kind changes). A road's {@code stad:road} markers are where it was drawn: they stay.
     */
    public static boolean laidOut(ResourceLocation type, String marker) {
        return marker.startsWith("stad:quarry") || marker.equals("stad:fish") || marker.equals("stad:post")
                || type.equals(BuildingDef.STALLS) && marker.equals("stad:work");
    }

    /** For tests: the same with another fence. */
    public Yard withFenceForTest(String f) {
        return withFence(f);
    }

    /** The same, with another fence (a pen has to have one). */
    Yard withFence(String f) {
        return new Yard(crop, water, f, path, animal, tree, spacing, extra, depth, wall, height, paving, awning, width);
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
        o.addProperty("depth", depth);
        o.addProperty("wall", wall);
        o.addProperty("height", height);
        o.addProperty("paving", paving);
        o.addProperty("awning", awning);
        o.addProperty("width", width);
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
                    : DEFAULT.spacing, o.has("extra") ? o.get("extra").getAsBoolean() : DEFAULT.extra,
                    o.has("depth") && DEPTHS.contains(o.get("depth").getAsInt()) ? o.get("depth").getAsInt() : DEFAULT.depth,
                    pick(o, "wall", WALLS, DEFAULT.wall),
                    o.has("height") ? Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, o.get("height").getAsInt())) : DEFAULT.height,
                    pick(o, "paving", PAVINGS, DEFAULT.paving), pick(o, "awning", AWNINGS, DEFAULT.awning),
                    o.has("width") ? Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, o.get("width").getAsInt())) : DEFAULT.width);
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
        int min = p.type.equals(BuildingDef.ROAD) ? 1 : p.type.equals(BuildingDef.PEN) || p.type.equals(BuildingDef.QUARRY)
                || p.type.equals(BuildingDef.WALL) || p.type.equals(BuildingDef.STALLS) || p.type.equals(BuildingDef.APIARY) ? 5 : 3;
        if (p.sx < min || p.sz < min) {
            return "The site is too small: make it at least " + min + " by " + min + " blocks.";
        }
        p.generated.clear();
        p.markers.values().removeIf(m -> laidOut(p.type, m));
        if (p.type.equals(BuildingDef.ROAD)) {
            return road(p, terrain);
        }
        if (p.type.equals(BuildingDef.QUARRY)) {
            return quarry(p, terrain);
        }
        if (p.type.equals(BuildingDef.DOCK)) {
            return dock(p, terrain);
        }
        if (p.type.equals(BuildingDef.POST)) {
            return post(p, terrain);
        }
        if (p.type.equals(BuildingDef.WALL)) {
            return wall(p, terrain);
        }
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
        level(p, terrain, !p.type.equals(BuildingDef.PEN));
        if (p.type.equals(BuildingDef.FIELD)) {
            field(p);
        } else if (p.type.equals(BuildingDef.STALLS)) {
            stalls(p);
        } else if (p.type.equals(BuildingDef.APIARY)) {
            apiary(p, terrain);
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
        Yard y = fence.equals("none") ? withFence("oak") : this; // a pen has to have a fence
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

    // ---- the miner's, the fisher's and the guards' ------------------------------------------------------------

    /**
     * An open pit for the miner: its corners marked ({@code stad:quarry_<depth>}) on the land as it is, a fence round it
     * so nobody walks in, lamps on the corners, and anything growing over the pit cleared. The miner digs it down a layer
     * at a time, leaving steps that spiral down its edge.
     */
    private String quarry(Project p, Grid terrain) {
        p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
        int f = p.floorY;
        boolean fenced = !fence.equals("none");
        int e = fenced ? 1 : 0;
        if (p.sx - 2 * e < 3 || p.sz - 2 * e < 3) {
            return "The site is too small for a quarry: make it at least " + (3 + 2 * e) + " by " + (3 + 2 * e) + " blocks.";
        }
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                boolean edge = x < e || z < e || x >= p.sx - e || z >= p.sz - e;
                int g = top(terrain, p, x, z);
                if (edge) {
                    if (g >= 0 && g + 1 < p.sy) {
                        p.generated.put(Project.key(x, g + 1, z), fenceState());
                    }
                    continue;
                }
                for (int y = f + 1; y < p.sy; y++) {
                    if (!land(terrain, x, y, z).isAir()) {
                        p.generated.put(Project.key(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        if (fenced) {
            int[] g = gate(p);
            int gy = top(terrain, p, g[0], g[1]);
            if (gy >= 0 && gy + 1 < p.sy) {
                p.generated.put(Project.key(g[0], gy + 1, g[1]), gateState(p.door));
            }
            if (extra) {
                for (int[] c : new int[][]{{0, 0}, {p.sx - 1, 0}, {0, p.sz - 1}, {p.sx - 1, p.sz - 1}}) {
                    int cy = top(terrain, p, c[0], c[1]);
                    if (cy >= 0 && cy + 2 < p.sy) {
                        p.generated.put(Project.key(c[0], cy + 2, c[1]), Blocks.LANTERN.defaultBlockState());
                    }
                }
            }
        }
        p.markers.put(Project.key(e, f, e), "stad:quarry_" + depth);
        p.markers.put(Project.key(p.sx - 1 - e, f, p.sz - 1 - e), "stad:quarry_" + depth);
        return null;
    }

    /**
     * A pier for the fisher: three planks wide at the water's level, from the side the door faces (the shore) out across
     * the site, on log posts down to the bottom; spots to fish from ({@code stad:fish}) along its sides and end, and
     * lamps at the end.
     */
    private String dock(Project p, Grid terrain) {
        java.util.Map<Integer, Integer> levels = new java.util.HashMap<>();
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                int g = top(terrain, p, x, z);
                if (g >= 0 && land(terrain, x, g, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                    levels.merge(g, 1, Integer::sum);
                }
            }
        }
        int wy = levels.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(-1);
        if (wy < 0 || levels.get(wy) < 8) {
            return "A dock needs water: put the site over the edge of a pond, a lake or a river, with the door on the shore's side.";
        }
        p.floorY = wy;
        boolean alongZ = p.door.getAxis() == net.minecraft.core.Direction.Axis.Z;
        int len = alongZ ? p.sz : p.sx, mid = (alongZ ? p.sx : p.sz) / 2;
        // the door faces the shore: the pier starts there and runs out
        boolean fromStart = p.door == net.minecraft.core.Direction.NORTH || p.door == net.minecraft.core.Direction.WEST;
        BlockState plank = woodState("planks"), post = woodState("log");
        int last = -1;
        for (int i = 0; i < len; i++) {
            int a = fromStart ? i : len - 1 - i;
            boolean wet = false;
            for (int w = -1; w <= 1; w++) {
                int x = alongZ ? mid + w : a, z = alongZ ? a : mid + w;
                if (x < 0 || z < 0 || x >= p.sx || z >= p.sz) {
                    continue;
                }
                BlockState here = land(terrain, x, wy, z);
                if (!here.getFluidState().isEmpty() || Fit.open(here)) {
                    p.generated.put(Project.key(x, wy, z), plank);
                    wet |= !here.getFluidState().isEmpty();
                    if (w != 0 && i % 4 == 2) {
                        for (int y = wy - 1; y >= 0 && !land(terrain, x, y, z).getFluidState().isEmpty(); y--) {
                            p.generated.put(Project.key(x, y, z), post);
                        }
                    }
                }
                for (int y = wy + 1; y < p.sy; y++) {
                    if (!land(terrain, x, y, z).isAir()) {
                        p.generated.put(Project.key(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            if (wet) {
                last = a;
                if (i % 2 == 1) { // spots along the sides, where there's water beside
                    for (int w : new int[]{-1, 1}) {
                        int x = alongZ ? mid + w : a, z = alongZ ? a : mid + w;
                        int ox = alongZ ? x + w : x, oz = alongZ ? z : z + w;
                        if (ox >= 0 && oz >= 0 && ox < p.sx && oz < p.sz && !land(terrain, ox, wy, oz).getFluidState().isEmpty()) {
                            p.markers.put(Project.key(x, wy, z), "stad:fish");
                        }
                    }
                }
            }
        }
        if (last < 0) {
            return "The pier doesn't reach the water: turn the door to face the shore.";
        }
        for (int w = -1; w <= 1; w++) { // the end of the pier
            int x = alongZ ? mid + w : last, z = alongZ ? last : mid + w;
            if (x >= 0 && z >= 0 && x < p.sx && z < p.sz) {
                p.markers.put(Project.key(x, wy, z), "stad:fish");
                if (extra && w != 0 && wy + 2 < p.sy) {
                    p.generated.put(Project.key(x, wy + 1, z), fenceState());
                    p.generated.put(Project.key(x, wy + 2, z), Blocks.LANTERN.defaultBlockState());
                    p.markers.remove(Project.key(x, wy, z));
                }
            }
        }
        return null;
    }

    /**
     * A watch post for the guards: a platform four blocks up on log posts, a ladder up the side the door faces, a
     * railing and a lamp, and a watch spot on top ({@code stad:post}) that the guards patrol to and keep watch from.
     */
    private String post(Project p, Grid terrain) {
        p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
        int f = p.floorY, cx = p.sx / 2, cz = p.sz / 2;
        if (f + 7 > p.sy) {
            return "There isn't room above the ground for the post: lower the site's bottom corner.";
        }
        BlockState log = woodState("log"), plank = woodState("planks"), rail = fenceState();
        int dx = p.door.getStepX(), dz = p.door.getStepZ();
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = f; y >= 0 && Fit.open(land(terrain, x, y, z)); y--) {
                    p.generated.put(Project.key(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                }
                for (int y = f + 1; y <= f + 3; y++) {
                    boolean pillar = Math.abs(x - cx) == 1 && Math.abs(z - cz) == 1 || x == cx && z == cz;
                    p.generated.put(Project.key(x, y, z), pillar ? log : Blocks.AIR.defaultBlockState());
                }
                p.generated.put(Project.key(x, f + 4, z), plank);
                boolean edge = x != cx || z != cz;
                p.generated.put(Project.key(x, f + 5, z), edge ? rail : Blocks.AIR.defaultBlockState());
                p.generated.put(Project.key(x, f + 6, z), Blocks.AIR.defaultBlockState());
            }
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, p.door);
        for (int y = f + 1; y <= f + 4; y++) {
            p.generated.put(Project.key(cx + dx, y, cz + dz), ladder);
        }
        p.generated.put(Project.key(cx + dx, f + 5, cz + dz), Blocks.AIR.defaultBlockState());
        p.generated.put(Project.key(cx - dx + dz, f + 6, cz - dz + dx), Blocks.LANTERN.defaultBlockState());
        p.markers.put(Project.key(cx, f + 4, cz), "stad:post");
        return null;
    }

    /**
     * A wall round the site, on the land as it is: logs (a palisade), stone bricks or cobblestone, {@code height} high,
     * with a door in the middle of the side the door faces; with {@code extra}, a watch spot ({@code stad:post}) on top
     * of each corner for the guards.
     */
    private String wall(Project p, Grid terrain) {
        p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
        BlockState stone = switch (wall) {
            case "stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
            default -> Blocks.SPRUCE_LOG.defaultBlockState();
        };
        int[] door = gate(p);
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                if (x != 0 && z != 0 && x != p.sx - 1 && z != p.sz - 1) {
                    continue;
                }
                int g = top(terrain, p, x, z);
                if (g < 0) {
                    continue;
                }
                for (int y = g + 1; y <= g + height && y < p.sy; y++) {
                    p.generated.put(Project.key(x, y, z), stone);
                }
                if (x == door[0] && z == door[1] && g + 2 < p.sy) {
                    BlockState d = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, p.door.getOpposite());
                    p.generated.put(Project.key(x, g + 1, z), d);
                    p.generated.put(Project.key(x, g + 2, z), d.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                }
            }
        }
        if (extra) {
            for (int[] c : new int[][]{{0, 0}, {p.sx - 1, 0}, {0, p.sz - 1}, {p.sx - 1, p.sz - 1}}) {
                int g = top(terrain, p, c[0], c[1]);
                if (g >= 0 && g + height < p.sy) {
                    p.markers.put(Project.key(c[0], g + height, c[1]), "stad:post");
                }
            }
        }
        return null;
    }

    // ---- the merchant's, and everyone's ------------------------------------------------------------------------

    /** The block a paving name lays: a dirt path, gravel, cobblestone, stone bricks or planks (of the fence's wood). */
    private BlockState pavingState() {
        return switch (paving) {
            case "gravel" -> Blocks.GRAVEL.defaultBlockState();
            case "cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
            case "stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "planks" -> woodState("planks");
            default -> Blocks.DIRT_PATH.defaultBlockState();
        };
    }

    /** What this paves with, the wood it builds in, its fence (for lamp posts): for roads laid outside a site ({@link TownRoads}). */
    public BlockState pavingBlock() {
        return pavingState();
    }

    public BlockState woodBlock(String what) {
        return woodState(what);
    }

    public BlockState fenceBlock() {
        return fenceState();
    }

    /** Whether a block is road paving (what {@link #PAVINGS} lay, any wood's planks). */
    public static boolean paving(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE_BRICKS) || s.is(BlockTags.PLANKS);
    }

    /** Whether a block is the ground a road can be laid on (not someone's chest, table or roof): earth, stone, sand, paving. */
    public static boolean groundLike(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA) || paving(s)
                || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.FARMLAND);
    }

    /** A stall's roof, at {@code a} (0 to 2) along its front: the awning's colour, or red and white stripes. */
    private BlockState awningState(int a) {
        String colour = awning.equals("striped") ? (a % 2 == 0 ? "red" : "white") : awning;
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(colour + "_wool"));
        return (b == Blocks.AIR ? Blocks.RED_WOOL : b).defaultBlockState();
    }

    /**
     * An open-air market for the merchants: the site levelled and paved, stalls round its edge facing in (three by three:
     * corner posts, an awning, a barrel at the back, a counter at the front and a spot for the merchant between, marked
     * {@code stad:work}), a way in on the door's side, and a bell in the middle of the square. With {@code extra}, a
     * lantern under each awning.
     */
    private void stalls(Project p) {
        int f = p.floorY;
        BlockState floor = pavingState();
        for (int x = 0; x < p.sx; x++) {
            for (int z = 0; z < p.sz; z++) {
                p.generated.put(Project.key(x, f, z), floor);
            }
        }
        // the square seen from its way in: u across, v from the door's side (0) to the back
        boolean across = p.door.getAxis() == Direction.Axis.Z;
        int w = across ? p.sx : p.sz, d = across ? p.sz : p.sx;
        java.util.function.BiFunction<Integer, Integer, int[]> at = (u, v) -> switch (p.door) {
            case SOUTH -> new int[]{p.sx - 1 - u, p.sz - 1 - v};
            case WEST -> new int[]{v, p.sz - 1 - u};
            case EAST -> new int[]{p.sx - 1 - v, u};
            default -> new int[]{u, v};
        };
        boolean front = d >= 11 && w >= 9, sides = w >= 11;
        int sideFrom = front ? 3 : 1, sideTo = d - 4;
        // each stall: where its back-left corner is, and which way u and v run along its front and into the square
        List<int[]> stalls = new java.util.ArrayList<>(); // u, v, du (along), dv (along), iu (in), iv (in)
        for (int s0 : starts(0, w - 1)) {
            stalls.add(new int[]{s0, d - 1, 1, 0, 0, -1});
        }
        if (front) {
            for (int s0 : starts(0, w / 2 - 2)) {
                stalls.add(new int[]{s0, 0, 1, 0, 0, 1});
            }
            for (int s0 : starts(w / 2 + 2, w - 1)) {
                stalls.add(new int[]{s0, 0, 1, 0, 0, 1});
            }
        }
        if (sides && sideTo - sideFrom >= 2) {
            for (int s0 : starts(sideFrom, sideTo)) {
                stalls.add(new int[]{0, s0, 0, 1, 1, 0});
                stalls.add(new int[]{w - 1, s0, 0, 1, -1, 0});
            }
        }
        BlockState post = fenceState(), counter = slabState().setValue(SlabBlock.TYPE, SlabType.TOP);
        for (int[] st : stalls) {
            int[] back = at.apply(st[0] + st[2], st[1] + st[3]), in = at.apply(st[0] + st[2] + st[4], st[1] + st[3] + st[5]);
            Direction facing = Direction.fromDelta(in[0] - back[0], 0, in[1] - back[1]);
            for (int a = 0; a < 3; a++) {
                for (int k = 0; k < 3; k++) {
                    int[] c = at.apply(st[0] + st[2] * a + st[4] * k, st[1] + st[3] * a + st[5] * k);
                    boolean corner = a != 1 && k != 1;
                    BlockState low = corner ? post : a == 1 && k == 0 ? Blocks.BARREL.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, facing != null ? facing : Direction.UP)
                            : a == 1 && k == 2 ? counter : Blocks.AIR.defaultBlockState();
                    p.generated.put(Project.key(c[0], f + 1, c[1]), low);
                    p.generated.put(Project.key(c[0], f + 2, c[1]), corner ? post : extra && a == 1 && k == 2
                            ? Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true)
                            : Blocks.AIR.defaultBlockState());
                    p.generated.put(Project.key(c[0], f + 3, c[1]), awningState(a));
                    if (a == 1 && k == 1) {
                        p.markers.put(Project.key(c[0], f + 1, c[1]), "stad:work");
                    }
                }
            }
        }
        int[] bell = at.apply(w / 2, ((front ? 3 : 0) + d - 4) / 2);
        p.generated.put(Project.key(bell[0], f + 1, bell[1]), Blocks.BELL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BellBlock.FACING, p.door)
                .setValue(net.minecraft.world.level.block.BellBlock.ATTACHMENT, net.minecraft.world.level.block.state.properties.BellAttachType.FLOOR));
    }

    /** The flowers an apiary is sown with, in turn. */
    public static final List<Block> FLOWERS = List.of(Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM, Blocks.AZURE_BLUET);

    /**
     * An apiary for the beekeeper: levelled grass inside a fence with a gate, beehives every three blocks along its
     * middle (facing the gate), a path from the gate to them when asked for, and flowers on every other block of the rest
     * (the beekeeper plants those: they're land, like a field's crops). Lamps on the corner posts with {@code extra}.
     */
    private void apiary(Project p, Grid terrain) {
        int f = p.floorY;
        Yard y = fence.equals("none") ? withFence("oak") : this; // the bees' meadow is fenced
        y.ring(p, f + 1);
        boolean alongX = p.sx >= p.sz;
        int len = alongX ? p.sx : p.sz, mid = (alongX ? p.sz : p.sx) / 2;
        java.util.Set<Integer> hives = new java.util.HashSet<>();
        for (int a = 2; a <= len - 3; a += 3) {
            int x = alongX ? a : mid, z = alongX ? mid : a;
            p.generated.put(Project.key(x, f + 1, z), Blocks.BEEHIVE.defaultBlockState().setValue(net.minecraft.world.level.block.BeehiveBlock.FACING, p.door));
            hives.add(Project.col(x, z));
        }
        java.util.Set<Integer> way = new java.util.HashSet<>();
        if (path) { // from the gate straight in to the row of hives
            int[] g = gate(p);
            int dx = p.door.getStepX(), dz = p.door.getStepZ();
            for (int x = g[0] - dx, z = g[1] - dz; x > 0 && z > 0 && x < p.sx - 1 && z < p.sz - 1 && (alongX ? z != mid : x != mid); x -= dx, z -= dz) {
                p.generated.put(Project.key(x, f, z), Blocks.DIRT_PATH.defaultBlockState());
                way.add(Project.col(x, z));
            }
        }
        int i = 0;
        for (int x = 1; x < p.sx - 1; x++) {
            for (int z = 1; z < p.sz - 1; z++) {
                int c = Project.col(x, z);
                if ((x + z) % 2 != 0 || hives.contains(c) || way.contains(c) || (alongX ? z == mid : x == mid)) {
                    continue;
                }
                if (!land(terrain, x, f, z).is(BlockTags.DIRT) || p.generated.containsKey(Project.key(x, f, z))) {
                    p.generated.put(Project.key(x, f, z), Blocks.GRASS_BLOCK.defaultBlockState());
                }
                p.generated.put(Project.key(x, f + 1, z), FLOWERS.get(i++ % FLOWERS.size()).defaultBlockState());
            }
        }
        if (extra && !fence.equals("none")) {
            for (int[] c : new int[][]{{0, 0}, {p.sx - 1, 0}, {0, p.sz - 1}, {p.sx - 1, p.sz - 1}}) {
                p.generated.put(Project.key(c[0], f + 2, c[1]), Blocks.LANTERN.defaultBlockState());
            }
        }
    }

    /** Where three-wide stalls start along {@code from} to {@code to} (inclusive): as many as fit a block apart, centred. */
    static List<Integer> starts(int from, int to) {
        int len = to - from + 1, n = (len + 1) / 4;
        List<Integer> out = new java.util.ArrayList<>();
        int first = from + (len - (n * 4 - 1)) / 2;
        for (int i = 0; i < n; i++) {
            out.add(first + i * 4);
        }
        return out;
    }

    /**
     * A road where it was drawn ({@code stad:road} markers, by hand or routed by the table: {@link Routes}), {@code width}
     * wide: the ground paved on the land as it is, what's growing on it cleared, planks over water. Only the ground: a
     * column whose top is something else (a chest, the town hall, a roof) is left alone, and so is where the town's
     * buildings stand ({@link Project#keepOut}). With {@code extra}, lamp posts beside it every eight blocks or so.
     */
    private String road(Project p, Grid terrain) {
        p.floorY = Fit.ground(terrain, p, 0, 0, p.sx, p.sz);
        java.util.Set<Integer> line = new java.util.HashSet<>();
        p.markers.forEach((k, m) -> {
            if (m.equals("stad:road")) {
                line.add(Project.colOf(k));
            }
        });
        java.util.Set<Integer> cells = new java.util.TreeSet<>();
        int lo = -(width - 1) / 2, hi = width / 2;
        for (int c : line) {
            for (int dx = lo; dx <= hi; dx++) {
                for (int dz = lo; dz <= hi; dz++) {
                    int x = Project.kx(c) + dx, z = Project.kz(c) + dz;
                    if (x >= 0 && z >= 0 && x < p.sx && z < p.sz) {
                        cells.add(Project.col(x, z));
                    }
                }
            }
        }
        BlockState floor = pavingState(), bridge = woodState("planks");
        cells.removeAll(p.keepOut); // it stops at the town's buildings
        for (int c : cells) {
            int x = Project.kx(c), z = Project.kz(c), g = top(terrain, p, x, z);
            if (g < 0) {
                continue;
            }
            BlockState here = land(terrain, x, g, z);
            if (here.getFluidState().isEmpty() && !groundLike(here)) {
                continue;
            }
            p.generated.put(Project.key(x, g, z), here.getFluidState().isEmpty() ? floor : bridge);
            for (int y = g + 1; y <= g + 2 && y < p.sy; y++) {
                if (!land(terrain, x, y, z).isAir()) {
                    p.generated.put(Project.key(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        if (extra) {
            List<int[]> lamps = new java.util.ArrayList<>();
            for (int c : cells) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    int x = Project.kx(c) + dir.getStepX(), z = Project.kz(c) + dir.getStepZ();
                    if (x < 0 || z < 0 || x >= p.sx || z >= p.sz || cells.contains(Project.col(x, z)) || p.keepOut.contains(Project.col(x, z))
                            || lamps.stream().anyMatch(l -> (l[0] - x) * (l[0] - x) + (l[1] - z) * (l[1] - z) < 64)) {
                        continue;
                    }
                    int g = top(terrain, p, x, z);
                    if (g >= 0 && g + 2 < p.sy && land(terrain, x, g, z).getFluidState().isEmpty() && !p.generated.containsKey(Project.key(x, g + 1, z))) {
                        p.generated.put(Project.key(x, g + 1, z), fenceState());
                        p.generated.put(Project.key(x, g + 2, z), Blocks.LANTERN.defaultBlockState());
                        lamps.add(new int[]{x, z});
                    }
                }
            }
        }
        return null;
    }

    /** A wooden block of the fence's wood ("planks", "log"); spruce for a stone fence or none. */
    private BlockState woodState(String what) {
        String wood = fence.equals("cobblestone") || fence.equals("none") ? "spruce" : fence;
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(wood + "_" + what));
        return (b == Blocks.AIR ? (what.equals("log") ? Blocks.SPRUCE_LOG : Blocks.SPRUCE_PLANKS) : b).defaultBlockState();
    }

    /** The top of the land in a column of the site (not logs or leaves), or -1. */
    static int top(Grid terrain, Project p, int x, int z) {
        for (int y = p.sy - 1; y >= 0; y--) {
            BlockState s = land(terrain, x, y, z);
            if (!s.getFluidState().isEmpty() || !Fit.open(s) && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS)) {
                return y;
            }
        }
        return -1;
    }
}
