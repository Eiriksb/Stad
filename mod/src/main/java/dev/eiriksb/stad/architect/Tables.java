package dev.eiriksb.stad.architect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.block.ArchitectTableBlockEntity;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Outlines;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderRecord;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.TablePayloads;
import dev.eiriksb.stad.pack.Palette;
import dev.eiriksb.stad.pack.Palettes;
import dev.eiriksb.stad.pack.Roles;
import dev.eiriksb.stad.pack.Scans;
import dev.eiriksb.stad.planner.Architect;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server side of the architect's table (PLAN.md §6.8): who has it open, and everything they do there. One player
 * edits a table at a time (officers and owners); the town's other members can watch. Every edit is checked: the
 * player's rank, that it's inside the site, and that the block is one a survival player could place. Server thread.
 */
public final class Tables {
    private Tables() {}

    /** A player with a table open, and the land of its site as it was when they opened it. */
    private static final class Session {
        final UUID player;
        final ServerLevel level;
        final BlockPos table;
        final boolean edit;
        Grid terrain;
        /** what's round the site ({@link dev.eiriksb.stad.building.Surroundings}) */
        Set<String> near = Set.of();

        Session(UUID player, ServerLevel level, BlockPos table, boolean edit) {
            this.player = player;
            this.level = level;
            this.table = table;
            this.edit = edit;
        }
    }

    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static final double REACH = 12;

    /** blocks a design may not use, besides game-master blocks (command blocks, structure blocks...) */
    private static final Set<Block> FORBIDDEN = Set.of(Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER,
            Blocks.VAULT, Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE, Blocks.BUDDING_AMETHYST, Blocks.STRUCTURE_VOID,
            Blocks.DRAGON_EGG, Blocks.PETRIFIED_OAK_SLAB, Blocks.FROGSPAWN, Blocks.INFESTED_STONE, Blocks.INFESTED_COBBLESTONE,
            Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS,
            Blocks.INFESTED_CHISELED_STONE_BRICKS, Blocks.INFESTED_DEEPSLATE);

    /** Whether a design may put {@code s} somewhere: air (clear it) or a block a survival player could place. */
    public static boolean allowed(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        Block b = s.getBlock();
        return b.asItem() != Items.AIR && !(b instanceof GameMasterBlock) && !FORBIDDEN.contains(b);
    }

    /** The kinds of building the table offers. */
    public static List<ResourceLocation> types() {
        List<ResourceLocation> t = new ArrayList<>(Requirements.types());
        t.add(ResourceLocation.fromNamespaceAndPath("stad", "building"));
        return t;
    }

    private static Optional<ArchitectTableBlockEntity> table(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ArchitectTableBlockEntity be ? Optional.of(be) : Optional.empty();
    }

    private static Optional<Colony> colonyAt(ServerLevel level, BlockPos pos) {
        return ColonyManager.get(level.getServer()).at(level.dimension(), pos);
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private static boolean canSend(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(TablePayloads.Open.TYPE);
    }

    // ---- opening ------------------------------------------------------------------------------------------------

    /** A player right-clicks a table (with the scan wand: makes what they marked its site first). */
    public static void use(ServerPlayer player, ServerLevel level, BlockPos pos, boolean wand) {
        Optional<Colony> colony = colonyAt(level, pos);
        Optional<ArchitectTableBlockEntity> be = table(level, pos);
        if (be.isEmpty()) {
            return;
        }
        if (FreeBuild.building(player)) {
            tell(player, "You're building in a site: finish (Done in chat) before using a table.", ChatFormatting.YELLOW);
            return;
        }
        if (colony.isEmpty()) {
            tell(player, "An architect's table works on a town's land: put it inside a town.", ChatFormatting.RED);
            return;
        }
        Rank rank = colony.get().rank(player.getUUID());
        if (!rank.atLeast(Rank.MEMBER)) {
            tell(player, "Only members of " + colony.get().name() + " can use its architect's table.", ChatFormatting.RED);
            return;
        }
        String message = "";
        if (wand) {
            Optional<Scans.Selection> sel = Scans.get(player).filter(Scans.Selection::complete);
            if (sel.isEmpty()) {
                tell(player, "Mark the site's corners with the scan wand first: left-click one, right-click the opposite one.", ChatFormatting.YELLOW);
                return;
            }
            if (!rank.atLeast(Rank.OFFICER)) {
                tell(player, "Only officers can choose the table's site.", ChatFormatting.RED);
                return;
            }
            String problem = Project.siteProblem(level, colony.get(), sel.get().a(), sel.get().b());
            if (problem != null) {
                tell(player, problem, ChatFormatting.RED);
                return;
            }
            Project p = be.get().project();
            p.setSite(sel.get().a(), sel.get().b());
            be.get().setChanged();
            message = "New site: " + p.sx + " by " + p.sz + " blocks, " + p.sy + " high.";
        }
        open(player, level, pos, message);
    }

    /** Opens the table's screen for a player (to edit when they may and nobody else is, else to watch). */
    public static void open(ServerPlayer player, ServerLevel level, BlockPos pos, String message) {
        Optional<Colony> colony = colonyAt(level, pos);
        Optional<ArchitectTableBlockEntity> be = table(level, pos);
        if (colony.isEmpty() || be.isEmpty() || !canSend(player)) {
            return;
        }
        Session other = sessions.values().stream().filter(s -> s.edit && s.table.equals(pos) && s.level == level
                && !s.player.equals(player.getUUID())).findFirst().orElse(null);
        boolean officer = colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER);
        Session s = new Session(player.getUUID(), level, pos.immutable(), officer && other == null);
        sessions.put(player.getUUID(), s);
        Project p = be.get().project();
        if (p.hasSite()) {
            s.terrain = capture(level, p);
            s.near = dev.eiriksb.stad.building.Surroundings.of(level, siteBox(p));
            if (p.spec != null) {
                int sy = p.sy;
                if (Fit.generate(p, s.terrain) == null && p.sy != sy) {
                    s.terrain = capture(level, p);
                    be.get().setChanged();
                }
            }
            Outlines.preview(player, new BoundingBox(p.min.getX(), p.min.getY(), p.min.getZ(), p.min.getX() + p.sx - 1,
                    p.min.getY() + p.sy - 1, p.min.getZ() + p.sz - 1), 100);
        }
        String editor = other == null ? "" : Optional.ofNullable(player.server.getPlayerList().getPlayer(other.player))
                .map(o -> o.getGameProfile().getName()).orElse("someone");
        PacketDistributor.sendToPlayer(player, openPayload(player, level, colony.get(), pos, p, s, editor, message));
    }

    static BoundingBox siteBox(Project p) {
        return new BoundingBox(p.min.getX(), p.min.getY(), p.min.getZ(), p.min.getX() + p.sx - 1, p.min.getY() + p.sy - 1, p.min.getZ() + p.sz - 1);
    }

    private static Grid capture(ServerLevel level, Project p) {
        int m = Project.MARGIN;
        return Grid.capture(level, p.min.offset(-m, -m, -m), p.sx + 2 * m, p.sy + 2 * m, p.sz + 2 * m);
    }

    private static TablePayloads.Open openPayload(ServerPlayer player, ServerLevel level, Colony colony, BlockPos pos, Project p, Session s,
                                                  String editor, String message) {
        List<TablePayloads.Style> styles = new ArrayList<>();
        for (Palette pal : Palettes.all()) {
            styles.add(new TablePayloads.Style(pal.name(), pal.material(Roles.Role.WALL, "oak"), pal.material(Roles.Role.FRAME, "none"),
                    pal.material(Roles.Role.ROOF, "spruce"), pal.material(Roles.Role.FLOOR, "oak")));
        }
        Map<Item, Integer> stock = stock(level, colony);
        List<String> items = new ArrayList<>();
        int[] counts = new int[stock.size()];
        int i = 0;
        for (Map.Entry<Item, Integer> e : stock.entrySet()) {
            items.add(BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            counts[i++] = e.getValue();
        }
        List<BlockPos> corners = Scans.get(player).filter(Scans.Selection::complete).map(sel -> List.of(sel.a(), sel.b())).orElse(List.of());
        String biome = p.hasSite() ? level.getBiome(p.min.offset(p.sx / 2, 0, p.sz / 2)).unwrapKey().map(k -> k.location().toString())
                .orElse("minecraft:plains") : "minecraft:plains";
        return new TablePayloads.Open(pos, colony.name(), s.edit, editor, types().stream().map(ResourceLocation::toString).toList(), styles,
                p.min, p.sx, p.sy, p.sz, p.hasSite() ? s.terrain : null, design(p), items, counts, biome, corners, List.copyOf(s.near), message);
    }

    /** What the town has in its stores (the town hall, builder's huts and warehouses), by item. */
    private static Map<Item, Integer> stock(ServerLevel level, Colony colony) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        List<ColonyStorage.Box> boxes = new ArrayList<>(ColonyStorage.supply(level, colony).boxes());
        boxes.addAll(ColonyStorage.warehouses(level, colony).boxes());
        for (ColonyStorage.Box b : boxes) {
            for (int i = 0; i < b.container().getContainerSize(); i++) {
                ItemStack st = b.container().getItem(i);
                if (!st.isEmpty()) {
                    m.merge(st.getItem(), st.getCount(), Integer::sum);
                }
            }
        }
        return m;
    }

    static TablePayloads.Design design(Project p) {
        Map<Integer, BlockState> d = p.design();
        int[] keys = new int[d.size()], states = new int[d.size()];
        int i = 0;
        for (Map.Entry<Integer, BlockState> e : d.entrySet()) {
            keys[i] = e.getKey();
            states[i++] = Block.getId(e.getValue());
        }
        int[] mk = new int[p.markers.size()];
        List<String> mn = new ArrayList<>();
        i = 0;
        for (Map.Entry<Integer, String> e : p.markers.entrySet()) {
            mk[i++] = e.getKey();
            mn.add(e.getValue());
        }
        return new TablePayloads.Design(p.name, p.type.toString(), p.spec == null ? "" : p.spec.toJson(), p.door.getSerializedName(), p.floorY,
                keys, states, mk, mn);
    }

    private static void update(ServerPlayer player, Session s, Project p, String message, boolean error) {
        if (canSend(player)) {
            PacketDistributor.sendToPlayer(player, new TablePayloads.Update(s.table, design(p), message, error));
        }
    }

    private static void status(ServerPlayer player, Session s, String message, boolean error) {
        if (canSend(player)) {
            PacketDistributor.sendToPlayer(player, new TablePayloads.Update(s.table, null, message, error));
        }
    }

    /** What an action came to: what to tell the player, and whether it went wrong. */
    public record Said(String message, boolean error) {}

    // ---- editing ------------------------------------------------------------------------------------------------

    /** The session a player edits {@code table} in, if they still may; tells them why not otherwise. */
    private static Session editing(ServerPlayer player, BlockPos table) {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.table.equals(table) || s.level != player.level()) {
            return null;
        }
        if (!s.edit || player.distanceToSqr(table.getCenter()) > REACH * REACH) {
            status(player, s, s.edit ? "You're too far from the table." : "You can only watch: someone else is using the table.", true);
            return null;
        }
        Optional<Colony> colony = colonyAt(s.level, table);
        if (colony.isEmpty() || !colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            status(player, s, "Only officers can change the design.", true);
            return null;
        }
        return s;
    }

    /** Hand edits from the screen. */
    public static void edit(ServerPlayer player, TablePayloads.Edit e) {
        Session s = editing(player, e.table());
        Optional<ArchitectTableBlockEntity> be = s == null ? Optional.empty() : table(s.level, s.table);
        if (be.isEmpty() || !be.get().project().hasSite()) {
            return;
        }
        Project p = be.get().project();
        int refused = 0;
        for (int i = 0; i < Math.min(e.keys().length, e.states().length); i++) {
            int key = e.keys()[i];
            BlockState st = Block.stateById(e.states()[i]);
            if (key < 0 || !p.inside(key) || !allowed(st)) {
                refused++;
                continue;
            }
            if (st.equals(p.generated.get(key))) {
                p.edits.remove(key);
            } else {
                p.edits.put(key, st);
            }
        }
        for (int i = 0; i < Math.min(e.markerKeys().length, e.markerNames().size()); i++) {
            int key = e.markerKeys()[i];
            String name = e.markerNames().get(i);
            if (key < 0 || !p.inside(key)) {
                continue;
            }
            if (name.isEmpty()) {
                p.markers.remove(key);
            } else if (Blueprint.MARKERS.contains(name)) {
                p.markers.put(key, name);
            }
        }
        be.get().setChanged();
        if (refused > 0) {
            update(player, s, p, refused + (refused == 1 ? " block wasn't" : " blocks weren't") + " allowed in a design.", true);
        }
    }

    /** Everything else from the screen. */
    public static void action(ServerPlayer player, TablePayloads.Action a) {
        if (a.kind() == TablePayloads.Action.Kind.CLOSE) {
            Session s = sessions.get(player.getUUID());
            if (s != null && s.table.equals(a.table())) {
                sessions.remove(player.getUUID());
            }
            return;
        }
        Session s = editing(player, a.table());
        Optional<ArchitectTableBlockEntity> be = s == null ? Optional.empty() : table(s.level, s.table);
        if (be.isEmpty()) {
            return;
        }
        Project p = be.get().project();
        Colony colony = colonyAt(s.level, s.table).orElse(null);
        if (colony == null) {
            return;
        }
        try {
            switch (a.kind()) {
                case SITE -> site(player, s, p, colony, a.text());
                case GENERATE -> generate(player, s, p, a.text());
                case DESCRIBE -> describe(player, s, p, a.text());
                case FLATTEN -> {
                    p.edits.putAll(p.design());
                    p.generated.clear();
                    p.spec = null;
                    update(player, s, p, "It's all hand edits now: generating again starts afresh.", false);
                }
                case START_OVER -> {
                    p.spec = null;
                    p.edits.clear();
                    p.markers.clear();
                    p.generated.clear();
                    p.floorY = -1;
                    update(player, s, p, "Cleared: the site is as it stands.", false);
                }
                case RENAME -> {
                    String name = a.text().strip();
                    if (name.length() >= 1 && name.length() <= 40) {
                        p.name = name;
                    }
                }
                case TYPE -> {
                    ResourceLocation t = ResourceLocation.tryParse(a.text());
                    if (t != null && types().contains(t)) {
                        p.type = t;
                    }
                }
                case SAVE -> {
                    Said said = save(player, p, a.text());
                    status(player, s, said.message(), said.error());
                }
                case PLAN -> {
                    Said said = plan(player, s, p, colony);
                    status(player, s, said.message(), said.error());
                }
                case FREE_BUILD -> {
                    String problem = FreeBuild.start(player, s.level, s.table, p);
                    if (problem != null) {
                        player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
                    } else {
                        sessions.remove(player.getUUID());
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            Stad.LOGGER.warn("Architect's table action {} failed", a.kind(), ex);
            status(player, s, "That didn't work: " + ex.getMessage(), true);
        }
        be.get().setChanged();
    }

    private static void site(ServerPlayer player, Session s, Project p, Colony colony, String text) {
        String[] n = text.trim().split("[\\s,]+");
        if (n.length != 6) {
            status(player, s, "Give both corners as x y z.", true);
            return;
        }
        int[] v = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                v[i] = Integer.parseInt(n[i]);
            }
        } catch (NumberFormatException e) {
            status(player, s, "The corners have to be whole numbers: x y z.", true);
            return;
        }
        BlockPos a = new BlockPos(v[0], v[1], v[2]), b = new BlockPos(v[3], v[4], v[5]);
        String problem = Project.siteProblem(s.level, colony, a, b);
        if (problem != null) {
            status(player, s, problem, true);
            return;
        }
        p.setSite(a, b);
        table(s.level, s.table).ifPresent(ArchitectTableBlockEntity::setChanged);
        open(player, s.level, s.table, "New site: " + p.sx + " by " + p.sz + " blocks, " + p.sy + " high.");
    }

    /** {"spec": {...}, "door": "north"}: generates the building in the site. */
    private static void generate(ServerPlayer player, Session s, Project p, String json) {
        if (!p.hasSite()) {
            return;
        }
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        BuildSpec spec = Fit.prepare(BuildSpec.parse(o.get("spec").toString()), p.type);
        Direction door = Direction.byName(o.has("door") ? o.get("door").getAsString() : "north");
        regenerate(player, s, p, spec, door != null && door.getAxis().isHorizontal() ? door : Direction.NORTH, null);
    }

    private static void regenerate(ServerPlayer player, Session s, Project p, BuildSpec spec, Direction door, String done) {
        BuildSpec oldSpec = p.spec;
        Direction oldDoor = p.door;
        int sy = p.sy;
        p.spec = spec;
        p.door = door;
        if (p.name.equals(new Project().name) || oldSpec != null && p.name.equals(oldSpec.name())) {
            p.name = spec.name().isBlank() || spec.name().equals(new Project().name) || spec.name().equals("Building")
                    ? Fit.defaultName(p.type) : spec.name();
        }
        String problem = Fit.generate(p, s.terrain);
        if (problem != null) {
            p.spec = oldSpec;
            p.door = oldDoor;
            if (oldSpec != null) {
                Fit.generate(p, s.terrain);
            }
            status(player, s, problem, true);
            return;
        }
        String message = done != null ? done : "Generated a " + p.spec.width() + " by " + p.spec.depth() + " "
                + (p.spec.floors() == 1 ? "one" : p.spec.floors() == 2 ? "two" : "three") + "-storey building"
                + (p.edits.isEmpty() ? "." : ", with your hand edits on top.");
        if (p.sy != sy) {
            s.terrain = capture(s.level, p);
            Optional<Colony> colony = colonyAt(s.level, s.table);
            colony.ifPresent(c -> PacketDistributor.sendToPlayer(player, openPayload(player, s.level, c, s.table, p, s, "",
                    message + " The site is " + p.sy + " high now to make room for it.")));
            return;
        }
        update(player, s, p, message, false);
    }

    /** {"text": "...", "spec": {...}, "door": "north"}: the planner model revises the spec from what the player wrote. */
    private static void describe(ServerPlayer player, Session s, Project p, String json) {
        if (!p.hasSite()) {
            return;
        }
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String text = o.get("text").getAsString().strip();
        if (text.isEmpty()) {
            return;
        }
        BuildSpec current = p.spec != null ? p.spec : Fit.prepare(BuildSpec.parse(o.get("spec").toString()), p.type);
        Direction door = p.door;
        String ask = text + " (This is for a site of " + p.sx + " by " + p.sz + " blocks: the building's size is set by the site, so keep "
                + "the width and depth. It is a " + Requirements.word(p.type) + ".)";
        status(player, s, "Drawing it up...", false);
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        Architect.spec(id, player.getGameProfile().getName(), ask, current.toJson()).whenComplete((spec, err) -> server.execute(() -> {
            ServerPlayer pl = server.getPlayerList().getPlayer(id);
            Session now = sessions.get(id);
            if (pl == null || now != s) {
                return;
            }
            if (err != null || spec == null) {
                status(pl, s, "The architect couldn't draw that up" + (err != null && err.getMessage() != null ? ": " + err.getMessage() : "."), true);
                return;
            }
            table(s.level, s.table).ifPresent(be -> {
                regenerate(pl, s, be.project(), Fit.prepare(spec, be.project().type), door, "Drew it up: " + spec.name() + ".");
                be.setChanged();
            });
        }));
    }

    // ---- saving and planning ------------------------------------------------------------------------------------

    /**
     * The design as a building: everything it puts on the site, cropped to what it touches. For planning,
     * {@code world} drops what's already there; for My pack the cells below the floor (the foundation for this
     * site) are left out and it's turned so its door faces north, as pack buildings do.
     */
    record Drawn(Blueprint blueprint, BlockPos origin) {}

    static Drawn draw(Project p, ServerLevel world, boolean forPack) {
        Map<Integer, BlockState> d = p.design();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = -1, maxY = -1, maxZ = -1;
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        int floor = forPack && p.floorY >= 0 ? p.floorY : 0;
        for (Map.Entry<Integer, BlockState> e : d.entrySet()) {
            int x = Project.kx(e.getKey()), y = Project.ky(e.getKey()), z = Project.kz(e.getKey());
            if (!p.inside(e.getKey()) || y < floor) {
                continue;
            }
            if (world != null) {
                BlockState there = world.getBlockState(p.min.offset(x, y, z));
                if (there.equals(e.getValue()) || e.getValue().isAir() && there.isAir()) {
                    continue;
                }
            }
            cells.put(new BlockPos(x, y, z), e.getValue());
        }
        for (Map.Entry<Integer, String> m : p.markers.entrySet()) {
            if (p.inside(m.getKey()) && Project.ky(m.getKey()) >= floor) {
                cells.putIfAbsent(new BlockPos(Project.kx(m.getKey()), Project.ky(m.getKey()), Project.kz(m.getKey())), null);
            }
        }
        for (BlockPos c : cells.keySet()) {
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            minZ = Math.min(minZ, c.getZ());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
            maxZ = Math.max(maxZ, c.getZ());
        }
        if (cells.isEmpty()) {
            return null;
        }
        if (forPack && p.floorY >= 0) {
            minY = p.floorY;
        }
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> c : cells.entrySet()) {
            if (c.getValue() != null) {
                entries.add(new Blueprint.Entry(c.getKey().offset(-minX, -minY, -minZ), c.getValue()));
            }
        }
        List<Blueprint.Marker> markers = new ArrayList<>();
        for (Map.Entry<Integer, String> m : p.markers.entrySet()) {
            BlockPos at = new BlockPos(Project.kx(m.getKey()), Project.ky(m.getKey()), Project.kz(m.getKey()));
            if (p.inside(m.getKey()) && at.getY() >= minY) {
                markers.add(new Blueprint.Marker(at.offset(-minX, -minY, -minZ), m.getValue()));
            }
        }
        Blueprint bp = new Blueprint(new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1), entries, markers);
        if (forPack) {
            Rotation back = switch (Fit.rotation(p.door)) {
                case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
                case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
                default -> Fit.rotation(p.door);
            };
            bp = bp.rotated(back);
        }
        return new Drawn(bp, p.min.offset(minX, minY, minZ));
    }

    static Design designOf(Project p, Blueprint bp) {
        Design d = Design.asBuilt(p.name, p.type, bp, "new");
        return p.spec == null ? d : new Design(p.name, p.type, p.spec, bp, null, d.beds(), Map.of(), List.of());
    }

    private static Said save(ServerPlayer player, Project p, String name) {
        if (!name.isBlank()) {
            p.name = name.strip().length() > 40 ? name.strip().substring(0, 40) : name.strip();
        }
        Drawn drawn = p.hasSite() ? draw(p, null, true) : null;
        if (drawn == null || drawn.blueprint().solidBlocks() == 0) {
            return new Said("There's nothing to save yet: generate a building or put some blocks down.", true);
        }
        try {
            ResourceLocation key = MyPack.save(player.server, designOf(p, drawn.blueprint()), player.getGameProfile().getName());
            return new Said("Saved " + p.name + " in My pack (" + key.getPath() + "). It's in the Building Plans now.", false);
        } catch (java.io.IOException e) {
            return new Said("Couldn't save it: " + e.getMessage(), true);
        }
    }

    /** Plans the design where it is: an order to the town's builders, rank-checked and logged like any other. */
    private static Said plan(ServerPlayer player, Session s, Project p, Colony colony) {
        if (!p.hasSite()) {
            return new Said("Choose a site first.", true);
        }
        s.terrain = capture(s.level, p);
        if (p.spec != null) {
            Fit.generate(p, s.terrain);
        }
        DesignCheck.Result check = DesignCheck.check(p.type, s.terrain, p.sx, p.sy, p.sz, p.design(), p.markers,
                dev.eiriksb.stad.building.Surroundings.of(s.level, siteBox(p)));
        if (!check.ready()) {
            return new Said("It isn't a " + Requirements.word(p.type) + " yet: it needs " + Design.join(check.missing()) + ".", true);
        }
        Drawn drawn = draw(p, s.level, false);
        if (drawn == null) {
            return new Said("There's nothing to build: the design is what's already there.", true);
        }
        Placement placement = Placement.of(drawn.blueprint(), drawn.origin(), Rotation.NONE);
        BoundingBox box = placement.bounds();
        BoundingBox site = new BoundingBox(p.min.getX(), p.min.getY(), p.min.getZ(), p.min.getX() + p.sx - 1, p.min.getY() + p.sy - 1,
                p.min.getZ() + p.sz - 1);
        String problem = null;
        List<Building> replaced = new ArrayList<>();
        if (box.isInside(colony.center)) {
            problem = "That would build over the town hall.";
        }
        for (Building b : colony.buildings()) {
            BoundingBox other = b.placement().map(Placement::bounds).orElse(null);
            if (problem != null || other == null || !other.intersects(box)) {
                continue;
            }
            if (b.done() && site.isInside(other.minX(), other.minY(), other.minZ()) && site.isInside(other.maxX(), other.maxY(), other.maxZ())) {
                replaced.add(b); // redesigning a building that stands on the site
            } else {
                problem = "That overlaps the " + b.name + (b.done() ? "." : ", which isn't finished yet.");
            }
        }
        if (!Planning.canPlan(colony, player)) {
            problem = "Only officers of " + colony.name() + " can plan buildings.";
        }
        long now = s.level.getGameTime();
        if (problem != null) {
            colony.log(new OrderRecord(now, player.getUUID(), player.getGameProfile().getName(), null, p.name, OrderKind.PLAN_BUILDING,
                    Order.Outcome.INVALID, problem));
            return new Said(problem, true);
        }
        replaced.forEach(b -> colony.removeBuilding(b.id));
        Design design = designOf(p, drawn.blueprint());
        Building planned = colony.addBuilding(ResourceLocation.fromNamespaceAndPath("stad", "design/" + design.slug()), p.name, drawn.origin(),
                Rotation.NONE, now, design);
        planned.outlineUntil = now + 200;
        colony.log(new OrderRecord(now, player.getUUID(), player.getGameProfile().getName(), null, p.name, OrderKind.PLAN_BUILDING,
                Order.Outcome.ACCEPTED, null));
        boolean builder = colony.citizens().stream().anyMatch(c -> Jobs.BUILDER.equals(c.job));
        int items = placement.materials().values().stream().mapToInt(Integer::intValue).sum();
        return new Said("Planned the " + p.name + " (" + items + " items" + (replaced.isEmpty() ? "" : ", in place of the "
                + Design.join(replaced.stream().map(b -> b.name).toList())) + "). " + (builder ? "A builder will start on it."
                : "Make someone the builder and they'll start on it."), false);
    }

    /** Plans a table's design without its screen (for tests). */
    public static Said planNow(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Optional<ArchitectTableBlockEntity> be = table(level, pos);
        Optional<Colony> colony = colonyAt(level, pos);
        if (be.isEmpty() || colony.isEmpty()) {
            return new Said("No table in a town there.", true);
        }
        return plan(player, new Session(player.getUUID(), level, pos, true), be.get().project(), colony.get());
    }

    /** Saves a table's design into My pack without its screen (for tests). */
    public static Said saveNow(ServerPlayer player, ServerLevel level, BlockPos pos, String name) {
        return table(level, pos).map(be -> save(player, be.project(), name)).orElse(new Said("No table there.", true));
    }

    // ---- housekeeping -------------------------------------------------------------------------------------------

    /** Every second: lets go of tables whose players went away. */
    public static void tick(MinecraftServer server) {
        sessions.values().removeIf(s -> {
            ServerPlayer p = server.getPlayerList().getPlayer(s.player);
            return p == null || p.level() != s.level || p.distanceToSqr(s.table.getCenter()) > (REACH + 8) * (REACH + 8)
                    || table(s.level, s.table).isEmpty();
        });
    }

    public static void forget(UUID player) {
        sessions.remove(player);
    }

    public static void clearAll() {
        sessions.clear();
    }

    /** For tests: the project of the table at {@code pos}. */
    public static Optional<Project> project(ServerLevel level, BlockPos pos) {
        return table(level, pos).map(ArchitectTableBlockEntity::project);
    }

    /** For tests: generates a project's building without a screen. */
    public static String generate(ServerLevel level, Project p, BuildSpec spec, Direction door) {
        p.spec = Fit.prepare(spec, p.type);
        p.door = door;
        return Fit.generate(p, capture(level, p));
    }

    /** For tests: the blueprint and origin a project would be planned with. */
    public static Optional<Map.Entry<Blueprint, BlockPos>> drawn(ServerLevel level, Project p, boolean forPack) {
        Drawn d = draw(p, forPack ? null : level, forPack);
        return d == null ? Optional.empty() : Optional.of(Map.entry(d.blueprint(), d.origin()));
    }

    /** For tests: checks a project against its type's needs, against the world as it is. */
    public static DesignCheck.Result check(ServerLevel level, Project p) {
        return DesignCheck.check(p.type, capture(level, p), p.sx, p.sy, p.sz, p.design(), p.markers,
                dev.eiriksb.stad.building.Surroundings.of(level, siteBox(p)));
    }

    /** Applies edits as the screen would, for tests (no rank or session checks). */
    public static int applyForTest(Project p, Map<Integer, BlockState> edits) {
        int refused = 0;
        for (Map.Entry<Integer, BlockState> e : edits.entrySet()) {
            if (!p.inside(e.getKey()) || !allowed(e.getValue())) {
                refused++;
            } else {
                p.edits.put(e.getKey(), e.getValue());
            }
        }
        return refused;
    }
}
