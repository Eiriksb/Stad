package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.block.ArchitectTableBlockEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.TablePayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Free build for an architect's table's site (PLAN.md §6.8): the player builds in creative, but in the drafting world,
 * an empty void with a copy of the site and the land round it (as the table's 3D view shows it). The real world is
 * never touched. What they build becomes the table's design; their inventory, game mode and place come back when they
 * leave. Each player gets their own stretch of the drafting world, in an invisible box of barriers. The session is
 * saved with the world, so it carries on after a logout or a restart. Server thread.
 */
public final class FreeBuild {
    private FreeBuild() {}

    /** the drafting world: void, always noon */
    public static final ResourceKey<Level> DRAFTING = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "drafting"));

    /** how far apart the players' stretches of the drafting world are, and the height a copied site starts at */
    static final int SPACING = 1024, BASE_Y = 64;
    /** room to move round the copied land, inside the box */
    static final int ROOM = 12;

    /** blocks free build won't place, besides what a design can't have */
    private static final Set<Block> REFUSED = Set.of(Blocks.TNT);

    /** setBlock flags: tell clients, don't update shapes round it, never drop anything */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** A player building a table's design in the drafting world, and what to give back. */
    static final class Session {
        final UUID player;
        final int slot;
        final ResourceKey<Level> tableDimension;
        final BlockPos table;
        final String name;
        /** the site in the real world */
        final BoundingBox site;
        final Land land;
        final ListTag inventory;
        final GameType previous;
        final ResourceKey<Level> backDimension;
        final Vec3 back;
        final float yRot, xRot;

        Session(UUID player, int slot, ResourceKey<Level> tableDimension, BlockPos table, String name, BoundingBox site, Land land,
                ListTag inventory, GameType previous, ResourceKey<Level> backDimension, Vec3 back, float yRot, float xRot) {
            this.player = player;
            this.slot = slot;
            this.tableDimension = tableDimension;
            this.table = table;
            this.name = name;
            this.site = site;
            this.land = land;
            this.inventory = inventory;
            this.previous = previous;
            this.backDimension = backDimension;
            this.back = back;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        /** Where the site's lowest corner is in the drafting world. */
        BlockPos origin() {
            return new BlockPos(slot * SPACING, BASE_Y + Project.MARGIN, 0);
        }

        /** The site, in the drafting world. */
        BoundingBox draftSite() {
            BlockPos o = origin();
            return new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + site.getXSpan() - 1, o.getY() + site.getYSpan() - 1,
                    o.getZ() + site.getZSpan() - 1);
        }

        /** The site and the land copied round it. */
        BoundingBox copy() {
            return draftSite().inflatedBy(Project.MARGIN);
        }

        /** Half the width of the room's square. */
        int half() {
            BoundingBox c = copy();
            return Math.max(c.getXSpan(), c.getZSpan()) / 2 + ROOM;
        }

        /** Everything that's theirs, with the invisible box round it on its faces: the floor, the walls and the ceiling. */
        BoundingBox room() {
            BoundingBox c = copy();
            int cx = (c.minX() + c.maxX()) / 2, cz = (c.minZ() + c.maxZ()) / 2, h = half();
            return new BoundingBox(cx - h, c.minY() - 1, cz - h, cx + h, c.maxY() + 24, cz + h);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Player", player);
            t.putInt("Slot", slot);
            t.putString("TableDimension", tableDimension.location().toString());
            t.put("Table", NbtUtils.writeBlockPos(table));
            t.putString("Name", name);
            t.putIntArray("Site", new int[]{site.minX(), site.minY(), site.minZ(), site.maxX(), site.maxY(), site.maxZ()});
            t.put("Land", land.save());
            t.put("Inventory", inventory);
            t.putString("Previous", previous.getName());
            t.putString("BackDimension", backDimension.location().toString());
            t.putDouble("BackX", back.x);
            t.putDouble("BackY", back.y);
            t.putDouble("BackZ", back.z);
            t.putFloat("YRot", yRot);
            t.putFloat("XRot", xRot);
            return t;
        }

        static Session load(CompoundTag t) {
            int[] b = t.getIntArray("Site");
            return new Session(t.getUUID("Player"), t.getInt("Slot"), dimension(t.getString("TableDimension")),
                    NbtUtils.readBlockPos(t, "Table").orElse(BlockPos.ZERO), t.getString("Name"), new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]),
                    Land.load(t.getCompound("Land")), t.getList("Inventory", Tag.TAG_COMPOUND), GameType.byName(t.getString("Previous"),
                    GameType.SURVIVAL), dimension(t.getString("BackDimension")), new Vec3(t.getDouble("BackX"), t.getDouble("BackY"),
                    t.getDouble("BackZ")), t.getFloat("YRot"), t.getFloat("XRot"));
        }

        private static ResourceKey<Level> dimension(String id) {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
        }
    }

    /** The site's land as it was when they started: what a design is laid over. */
    record Land(int sx, int sy, int sz, BlockState[] states) {
        static Land of(Grid terrain, int sx, int sy, int sz) {
            BlockState[] states = new BlockState[sx * sy * sz];
            int m = Project.MARGIN;
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        states[(y * sz + z) * sx + x] = terrain.get(x + m, y + m, z + m);
                    }
                }
            }
            return new Land(sx, sy, sz, states);
        }

        BlockState at(int x, int y, int z) {
            return states[(y * sz + z) * sx + x];
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putIntArray("Size", new int[]{sx, sy, sz});
            List<BlockState> palette = new ArrayList<>();
            Map<BlockState, Integer> index = new LinkedHashMap<>();
            int[] cells = new int[states.length];
            for (int i = 0; i < states.length; i++) {
                cells[i] = index.computeIfAbsent(states[i], s -> {
                    palette.add(s);
                    return palette.size() - 1;
                });
            }
            ListTag p = new ListTag();
            palette.forEach(s -> p.add(NbtUtils.writeBlockState(s)));
            t.put("Palette", p);
            t.putIntArray("Cells", cells);
            return t;
        }

        static Land load(CompoundTag t) {
            int[] s = t.getIntArray("Size");
            ListTag p = t.getList("Palette", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < p.size(); i++) {
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), p.getCompound(i)));
            }
            int[] cells = t.getIntArray("Cells");
            BlockState[] states = new BlockState[s[0] * s[1] * s[2]];
            for (int i = 0; i < states.length; i++) {
                states[i] = i < cells.length && cells[i] < palette.size() ? palette.get(cells[i]) : Blocks.AIR.defaultBlockState();
            }
            return new Land(s[0], s[1], s[2], states);
        }
    }

    // ---- what's saved with the world ----------------------------------------------------------------------------

    public static final class Data extends SavedData {
        private static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, Data::load);
        final Map<UUID, Session> sessions = new LinkedHashMap<>();

        static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "stad_free_build");
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            sessions.values().forEach(s -> list.add(s.save()));
            tag.put("Sessions", list);
            return tag;
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            for (Tag t : tag.getList("Sessions", Tag.TAG_COMPOUND)) {
                try {
                    Session s = Session.load((CompoundTag) t);
                    d.sessions.put(s.player, s);
                } catch (RuntimeException e) {
                    Stad.LOGGER.error("Couldn't read a free build session", e);
                }
            }
            return d;
        }
    }

    /** the sessions going on now (online or not), by player */
    private static final Map<UUID, Session> active = new HashMap<>();
    /** players just back from building, to open the table for in a moment (ticks to wait, and what to say) */
    private static final Map<UUID, Reopen> reopen = new HashMap<>();

    private record Reopen(ResourceKey<Level> dimension, BlockPos table, String message, int at) {}

    public static boolean building(Player player) {
        return active.containsKey(player.getUUID());
    }

    private static boolean drafting(Level level) {
        return level.dimension().equals(DRAFTING) || level.getServer() instanceof GameTestServer && level.dimension().equals(Level.NETHER);
    }

    /** The drafting world (the game test server doesn't load data pack worlds, so its tests draft in the nether). */
    public static ServerLevel draftLevel(MinecraftServer server) {
        ServerLevel l = server.getLevel(DRAFTING);
        return l == null && server instanceof GameTestServer ? server.getLevel(Level.NETHER) : l;
    }

    // ---- starting and finishing ---------------------------------------------------------------------------------

    /** Starts free build for a table's site; returns why not, or null. */
    public static String start(ServerPlayer player, ServerLevel level, BlockPos table, Project p) {
        if (!p.hasSite()) {
            return "Choose a site first.";
        }
        if (building(player)) {
            return "You're already building somewhere: finish that first.";
        }
        if (!player.isAlive()) {
            return "You can't build while you're dead.";
        }
        ServerLevel draft = draftLevel(player.server);
        if (draft == null) {
            return "The drafting world is missing (is the mod's data pack turned off?).";
        }
        Optional<Colony> colony = ColonyManager.get(level.getServer()).at(level.dimension(), table);
        if (colony.isEmpty() || !colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers can build the design.";
        }
        Grid terrain = Tables.capture(level, p);
        Data data = Data.get(level.getServer());
        int slot = 0;
        while (slotTaken(data, slot)) {
            slot++;
        }
        Session s = new Session(player.getUUID(), slot, level.dimension(), table.immutable(), p.name, Tables.siteBox(p), Land.of(terrain, p.sx, p.sy, p.sz),
                player.getInventory().save(new ListTag()), player.gameMode.getGameModeForPlayer(), player.level().dimension(), player.position(),
                player.getYRot(), player.getXRot());
        data.sessions.put(player.getUUID(), s);
        data.setDirty();
        level.getServer().overworld().getDataStorage().save(); // their inventory is on disk before it's emptied
        active.put(player.getUUID(), s);
        build(draft, s, terrain, p, level);
        player.getInventory().clearContent();
        player.containerMenu.broadcastChanges();
        // in front of the door, a little above the floor, looking at the site
        BoundingBox site = s.draftSite();
        Direction door = p.door == null ? Direction.NORTH : p.door;
        double reach = (door.getAxis() == Direction.Axis.X ? site.getXSpan() : site.getZSpan()) / 2.0 + 6;
        double x = (site.minX() + site.maxX() + 1) / 2.0 + door.getStepX() * reach, z = (site.minZ() + site.maxZ() + 1) / 2.0 + door.getStepZ() * reach;
        double y = site.minY() + Math.max(0, p.floorY) + 5;
        player.teleportTo(draft, x, y, z, door.getOpposite().toYRot(), 25f);
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        show(player, s);
        player.sendSystemMessage(Component.literal("You're in the drafting world with a copy of the site: build it however you like. ")
                .withStyle(ChatFormatting.GOLD).append(buttons()));
        return null;
    }

    private static boolean slotTaken(Data data, int slot) {
        return data.sessions.values().stream().anyMatch(s -> s.slot == slot);
    }

    /**
     * Lays out a player's stretch of the drafting world: the land round the site, the design in it, a box round it all,
     * and the real place's biomes (for the grass, leaves and water colours).
     */
    private static void build(ServerLevel draft, Session s, Grid terrain, Project p, ServerLevel level) {
        BoundingBox room = s.room();
        clear(draft, room);
        BoundingBox copy = s.copy();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = 0; y < terrain.sy; y++) {
            for (int z = 0; z < terrain.sz; z++) {
                for (int x = 0; x < terrain.sx; x++) {
                    BlockState st = terrain.get(x, y, z);
                    if (!st.isAir()) {
                        draft.setBlock(at.set(copy.minX() + x, copy.minY() + y, copy.minZ() + z), st, QUIET);
                    }
                }
            }
        }
        BlockPos o = s.origin();
        for (Map.Entry<Integer, BlockState> e : p.design().entrySet()) {
            if (p.inside(e.getKey())) {
                draft.setBlock(o.offset(Project.kx(e.getKey()), Project.ky(e.getKey()), Project.kz(e.getKey())), e.getValue(), QUIET);
            }
        }
        // an invisible box round it all, to stand on and to keep them in
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        for (int y = room.minY(); y <= room.maxY(); y++) {
            boolean flat = y == room.minY() || y == room.maxY();
            for (int x = room.minX(); x <= room.maxX(); x++) {
                for (int z = room.minZ(); z <= room.maxZ(); z++) {
                    if (flat || x == room.minX() || x == room.maxX() || z == room.minZ() || z == room.maxZ()) {
                        draft.setBlock(at.set(x, y, z), barrier, QUIET);
                    }
                }
            }
        }
        biomes(draft, s, level);
        // water and lava at the edge of the copy stay in it
        for (int y = copy.minY(); y <= copy.maxY(); y++) {
            for (int z = copy.minZ(); z <= copy.maxZ(); z++) {
                for (int x = copy.minX(); x <= copy.maxX(); x++) {
                    if (draft.getFluidState(at.set(x, y, z)).isEmpty()) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = at.relative(d);
                        if (!copy.isInside(n)) {
                            draft.setBlock(n, barrier, QUIET);
                        }
                    }
                }
            }
        }
    }

    /** Copies the biomes of the site and the land round it into the room (the land's edge carries on out to the walls). */
    private static void biomes(ServerLevel draft, Session s, ServerLevel level) {
        BoundingBox room = s.room(), copy = s.copy();
        BlockPos o = s.origin(), real = new BlockPos(s.site.minX(), s.site.minY(), s.site.minZ());
        List<ChunkAccess> chunks = new ArrayList<>();
        for (int cx = SectionPos.blockToSectionCoord(room.minX()); cx <= SectionPos.blockToSectionCoord(room.maxX()); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(room.minZ()); cz <= SectionPos.blockToSectionCoord(room.maxZ()); cz++) {
                chunks.add(draft.getChunk(cx, cz));
            }
        }
        for (ChunkAccess chunk : chunks) {
            chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
                int x = QuartPos.toBlock(qx), y = QuartPos.toBlock(qy), z = QuartPos.toBlock(qz);
                if (!room.isInside(x, y, z)) {
                    return chunk.getNoiseBiome(qx, qy, qz);
                }
                x = Math.max(copy.minX(), Math.min(copy.maxX(), x));
                y = Math.max(copy.minY(), Math.min(copy.maxY(), y));
                z = Math.max(copy.minZ(), Math.min(copy.maxZ(), z));
                return level.getBiome(real.offset(x - o.getX(), y - o.getY(), z - o.getZ()));
            }, draft.getChunkSource().randomState().sampler());
            chunk.setUnsaved(true);
        }
        draft.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
    }

    /** Tells the player's game where the site is (for the grid), or that they're done. */
    private static void show(ServerPlayer player, Session s) {
        if (player.connection == null || !player.connection.hasChannel(TablePayloads.Drafting.TYPE)) {
            return;
        }
        BoundingBox b = s == null ? null : s.draftSite();
        PacketDistributor.sendToPlayer(player, b == null ? TablePayloads.Drafting.NONE
                : new TablePayloads.Drafting(s.origin(), b.getXSpan(), b.getYSpan(), b.getZSpan(), s.name));
    }

    /** Empties a box of the drafting world, top down, so nothing spills or hangs on what's gone. */
    private static void clear(ServerLevel draft, BoundingBox box) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = box.maxY() + 1; y >= box.minY() - 1; y--) {
            for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
                for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
                    if (!draft.getBlockState(at.set(x, y, z)).isAir()) {
                        Clearable.tryClear(draft.getBlockEntity(at));
                        draft.setBlock(at, air, QUIET);
                    }
                }
            }
        }
    }

    private static Component buttons() {
        return Component.literal("[Done]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/stad build done"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Keep what you built as the design"))))
                .append(" ").append(Component.literal("[Cancel]").withStyle(s -> s.withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/stad build cancel"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Throw away what you built")))));
    }

    /**
     * Ends a player's free build: {@code keep} makes what they built the table's design. They go back to where they
     * were, with their own things. Returns what happened, or null when they weren't building.
     */
    public static String finish(ServerPlayer player, boolean keep) {
        Session s = active.remove(player.getUUID());
        if (s == null) {
            return null;
        }
        ServerLevel draft = draftLevel(player.server);
        String result = "Threw away what you built: the design is as it was.";
        boolean kept = false;
        if (draft != null) {
            if (keep) {
                result = keep(player.server, draft, s);
                kept = result.startsWith("Kept");
            }
            clear(draft, s.room());
        }
        Data data = Data.get(player.server);
        data.sessions.remove(player.getUUID());
        data.setDirty();
        show(player, null);
        giveBack(player, s);
        if (kept) {
            reopen.put(player.getUUID(), new Reopen(s.tableDimension, s.table, result, player.tickCount + 10));
        }
        return result;
    }

    /** What they built, as the table's design (on top of the generated building). */
    private static String keep(MinecraftServer server, ServerLevel draft, Session s) {
        ServerLevel level = server.getLevel(s.tableDimension);
        if (level == null || !(level.getBlockEntity(s.table) instanceof ArchitectTableBlockEntity be) || !be.project().hasSite()
                || !Tables.siteBox(be.project()).equals(s.site)) {
            return "The table is gone (or has another site now), so what you built wasn't kept.";
        }
        Project p = be.project();
        BlockPos o = s.origin();
        int changed = 0;
        for (int y = 0; y < s.land.sy; y++) {
            for (int z = 0; z < s.land.sz; z++) {
                for (int x = 0; x < s.land.sx; x++) {
                    int k = Project.key(x, y, z);
                    BlockState was = s.land.at(x, y, z);
                    BlockState before = p.at(k) != null ? p.at(k) : was;
                    BlockState now = shut(draft.getBlockState(o.offset(x, y, z))); // a door they walked through stays shut
                    if (p.at(k) == null && settled(was, now) || !now.isAir() && !Tables.allowed(now)) {
                        now = before; // the land changing by itself, or water running in, isn't something they built
                    }
                    if (!now.equals(before)) {
                        changed++;
                    }
                    BlockState generated = p.generated.get(k);
                    if (now.equals(generated != null ? generated : was)) {
                        p.edits.remove(k);
                    } else {
                        p.edits.put(k, now);
                    }
                }
            }
        }
        be.setChanged();
        return changed == 0 ? "You didn't change anything." : "Kept what you built in the design (" + changed
                + (changed == 1 ? " block" : " blocks") + " changed).";
    }

    /** Doors, gates and trapdoors closed and unpowered (what a design keeps). */
    static BlockState shut(BlockState s) {
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            s = s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, false);
        }
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
            s = s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, false);
        }
        return s;
    }

    /** The land changing by itself: grass going to dirt under something, snow melting, water settling. */
    private static boolean settled(BlockState was, BlockState now) {
        return was.is(net.minecraft.tags.BlockTags.DIRT) && now.is(net.minecraft.tags.BlockTags.DIRT)
                || was.is(Blocks.SNOW) && now.isAir()
                || was.getBlock() instanceof LiquidBlock && (now.isAir() || now.getBlock() instanceof LiquidBlock || now.is(Blocks.ICE));
    }

    private static void giveBack(ServerPlayer player, Session s) {
        player.getInventory().clearContent();
        player.getInventory().load(s.inventory);
        player.setGameMode(s.previous);
        if (s.previous != GameType.CREATIVE && s.previous != GameType.SPECTATOR) {
            player.getAbilities().flying = false;
            player.getAbilities().mayfly = false;
            player.onUpdateAbilities();
        }
        player.containerMenu.broadcastChanges();
        ServerLevel to = player.server.getLevel(s.backDimension);
        if (to == null) {
            to = player.server.overworld();
        }
        player.teleportTo(to, s.back.x, s.back.y, s.back.z, s.yRot, s.xRot);
        player.fallDistance = 0;
    }

    // ---- the rules in the drafting world ------------------------------------------------------------------------

    public static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(FreeBuild::broke);
        bus.addListener(FreeBuild::placed);
        bus.addListener(FreeBuild::rightClickBlock);
        bus.addListener(FreeBuild::rightClickItem);
        bus.addListener(FreeBuild::entityInteract);
        bus.addListener(FreeBuild::entityInteractAt);
        bus.addListener(FreeBuild::attack);
        bus.addListener(FreeBuild::toss);
        bus.addListener(FreeBuild::joined);
        bus.addListener(FreeBuild::exploded);
        bus.addListener(FreeBuild::died);
        bus.addListener(FreeBuild::tick);
        bus.addListener(FreeBuild::loggedIn);
        bus.addListener(FreeBuild::started);
        bus.addListener(FreeBuild::stopping);
    }

    private static void deny(ServerPlayer p, String why) {
        p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
    }

    /** The player's session, when they're in the drafting world. */
    private static Session here(Level level, Player player) {
        return player == null || !drafting(level) ? null : active.get(player.getUUID());
    }

    private static void broke(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof Level level) || !drafting(level)) {
            return;
        }
        Session s = here(level, e.getPlayer());
        if (s == null || !s.draftSite().isInside(e.getPos())) {
            e.setCanceled(true);
            if (e.getPlayer() instanceof ServerPlayer sp) {
                deny(sp, "Only the site is yours to change: the land round it is there to look at.");
            }
        }
    }

    private static void placed(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof Level level) || !drafting(level) || !(e.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        Session s = here(level, sp);
        BlockState st = e.getPlacedBlock();
        if (s == null || !s.draftSite().isInside(e.getPos())) {
            e.setCanceled(true);
            deny(sp, "Only the site is yours to build in (the yellow box in the table's view).");
        } else if (!Tables.allowed(st) || REFUSED.contains(st.getBlock())) {
            e.setCanceled(true);
            deny(sp, st.getBlock().getName().getString() + " can't be part of a design.");
        }
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !drafting(p.level())) {
            return;
        }
        ItemStack held = e.getItemStack();
        if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) {
            e.setCanceled(true);
            deny(p, "While you design, only blocks: no items.");
            return;
        }
        BlockState target = p.level().getBlockState(e.getPos());
        if (p.level().getBlockEntity(e.getPos()) != null || target.getBlock() instanceof EnderChestBlock || target.getBlock() instanceof BedBlock
                || target.getBlock() instanceof RespawnAnchorBlock) {
            e.setUseBlock(TriState.FALSE); // no opening containers or sleeping: place against them instead
        }
    }

    private static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getEntity() instanceof ServerPlayer p && drafting(p.level()) && !(e.getItemStack().getItem() instanceof BlockItem)) {
            e.setCanceled(true);
            deny(p, "While you design, only blocks: no items.");
        }
    }

    private static void entityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p && drafting(p.level())) {
            e.setCanceled(true);
        }
    }

    private static void entityInteractAt(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getEntity() instanceof ServerPlayer p && drafting(p.level())) {
            e.setCanceled(true);
        }
    }

    private static void attack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && drafting(p.level())) {
            e.setCanceled(true);
        }
    }

    private static void toss(ItemTossEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && drafting(p.level())) {
            e.setCanceled(true);
        }
    }

    /** Nothing lives in the drafting world but the players designing there (sand and gravel still fall). */
    private static void joined(EntityJoinLevelEvent e) {
        if (drafting(e.getLevel()) && !(e.getEntity() instanceof Player) && !(e.getEntity() instanceof FallingBlockEntity)) {
            e.setCanceled(true);
        }
    }

    private static void exploded(ExplosionEvent.Detonate e) {
        if (drafting(e.getLevel())) {
            e.getAffectedBlocks().clear();
        }
    }

    /** Dying while designing (only /kill can do it) just ends the session. */
    private static void died(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            e.setCanceled(true);
            p.setHealth(p.getMaxHealth());
            finish(p, true);
        }
    }

    private static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Reopen r = reopen.get(p.getUUID());
        if (r != null && p.tickCount >= r.at) {
            reopen.remove(p.getUUID());
            ServerLevel level = p.server.getLevel(r.dimension);
            if (level != null && p.level() == level && p.blockPosition().closerThan(r.table, 16)) {
                Tables.open(p, level, r.table, r.message);
            }
        }
        if (p.tickCount % 5 != 0) {
            return;
        }
        Session s = active.get(p.getUUID());
        if (s == null) {
            return;
        }
        if (!drafting(p.level())) {
            String result = finish(p, true);
            p.sendSystemMessage(Component.literal(result).withStyle(ChatFormatting.GOLD));
            return;
        }
        if (!p.isCreative()) {
            p.setGameMode(GameType.CREATIVE);
        }
        // the box holds them in; this is for when they somehow get past it
        BoundingBox room = s.room();
        if (!room.inflatedBy(2).isInside(p.blockPosition())) {
            double x = Math.max(room.minX() + 1, Math.min(room.maxX(), p.getX()));
            double z = Math.max(room.minZ() + 1, Math.min(room.maxZ(), p.getZ()));
            double y = Math.max(room.minY() + 1, Math.min(room.maxY(), p.getY()));
            p.teleportTo(x, y, z);
        }
    }

    /** Back in the game: carry on designing (or, if they're somehow in the drafting world with nothing to design, out). */
    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Session s = active.get(p.getUUID());
        if (s != null && drafting(p.level())) {
            show(p, s);
            p.sendSystemMessage(Component.literal("You're still designing in the drafting world. ").withStyle(ChatFormatting.GOLD).append(buttons()));
        } else if (s != null) {
            finish(p, false); // they never got there (the server stopped as they set off)
        } else if (drafting(p.level())) {
            ServerLevel overworld = p.server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            p.getInventory().clearContent();
            p.setGameMode(p.server.getDefaultGameType());
            p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
            p.sendSystemMessage(Component.literal("You were in the drafting world with nothing to design, so you're back at spawn.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void started(ServerStartedEvent e) {
        active.clear();
        reopen.clear();
        active.putAll(Data.get(e.getServer()).sessions);
        ServerLevel draft = e.getServer().getLevel(DRAFTING);
        if (draft != null) {
            draft.setRainLevel(0); // it has no weather of its own, but takes whatever the overworld had when the server started
            draft.setThunderLevel(0);
        }
    }

    private static void stopping(ServerStoppingEvent e) {
        active.clear();
        reopen.clear();
    }

    /** For tests: the server stopped mid-build and started again, and the player comes back. */
    public static void restartForTest(ServerPlayer player) {
        active.clear();
        started(new ServerStartedEvent(player.server));
        loggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
    }

    /** For tests: the sites being designed now, in the drafting world. */
    public static List<BoundingBox> sites() {
        return active.values().stream().map(Session::draftSite).toList();
    }

    /** For tests: where a player's site is in the drafting world (its lowest corner), or null. */
    public static BlockPos origin(Player player) {
        Session s = active.get(player.getUUID());
        return s == null ? null : s.origin();
    }
}
