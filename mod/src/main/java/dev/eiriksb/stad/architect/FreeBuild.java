package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.block.ArchitectTableBlockEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
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
 * Free build in an architect's table's site (PLAN.md §6.8): the player builds in creative, but only as a design. What
 * they build becomes the table's design and the site goes back to how it was; their inventory, game mode and place
 * come back. Nothing leaves the site: drops and falling blocks in it are cancelled, containers and entities can't be
 * used, only design blocks can be placed and only inside it, and nobody else can change it meanwhile. Everything needed
 * to put things back is saved with the world before anything changes, so a crash or a logout undoes it. Server thread.
 */
public final class FreeBuild {
    private FreeBuild() {}

    /** blocks free build won't place, besides what a design can't have: they move blocks or items out of the site */
    private static final Set<Block> MOVERS = Set.of(Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER,
            Blocks.CRAFTER, Blocks.TNT, Blocks.SCULK_CATALYST);

    /** A player building freely in a site, and what to put back. */
    static final class Session {
        final UUID player;
        final ResourceKey<Level> dimension;
        final BlockPos table;
        final BoundingBox site;
        final Snapshot snapshot;
        final ListTag inventory;
        final GameType previous;
        final Vec3 back;
        final float yRot, xRot;
        /** set once the site is put back (after a crash, before the player comes back for their things) */
        boolean siteRestored;

        Session(UUID player, ResourceKey<Level> dimension, BlockPos table, BoundingBox site, Snapshot snapshot, ListTag inventory,
                GameType previous, Vec3 back, float yRot, float xRot) {
            this.player = player;
            this.dimension = dimension;
            this.table = table;
            this.site = site;
            this.snapshot = snapshot;
            this.inventory = inventory;
            this.previous = previous;
            this.back = back;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        boolean inside(BlockPos p) {
            return site.isInside(p);
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Player", player);
            t.putString("Dimension", dimension.location().toString());
            t.put("Table", NbtUtils.writeBlockPos(table));
            t.putIntArray("Site", new int[]{site.minX(), site.minY(), site.minZ(), site.maxX(), site.maxY(), site.maxZ()});
            t.put("Snapshot", snapshot.save());
            t.put("Inventory", inventory);
            t.putString("Previous", previous.getName());
            t.putDouble("BackX", back.x);
            t.putDouble("BackY", back.y);
            t.putDouble("BackZ", back.z);
            t.putFloat("YRot", yRot);
            t.putFloat("XRot", xRot);
            t.putBoolean("SiteRestored", siteRestored);
            return t;
        }

        static Session load(CompoundTag t) {
            int[] b = t.getIntArray("Site");
            Session s = new Session(t.getUUID("Player"), ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("Dimension"))),
                    NbtUtils.readBlockPos(t, "Table").orElse(BlockPos.ZERO), new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]),
                    Snapshot.load(t.getCompound("Snapshot")), t.getList("Inventory", Tag.TAG_COMPOUND), GameType.byName(t.getString("Previous"),
                    GameType.SURVIVAL), new Vec3(t.getDouble("BackX"), t.getDouble("BackY"), t.getDouble("BackZ")), t.getFloat("YRot"), t.getFloat("XRot"));
            s.siteRestored = t.getBoolean("SiteRestored");
            return s;
        }
    }

    /** The site's blocks, with what's in its block entities. */
    record Snapshot(int sx, int sy, int sz, BlockState[] states, Map<Integer, CompoundTag> entities) {
        static Snapshot capture(ServerLevel level, BoundingBox box) {
            int sx = box.getXSpan(), sy = box.getYSpan(), sz = box.getZSpan();
            BlockState[] states = new BlockState[sx * sy * sz];
            Map<Integer, CompoundTag> entities = new HashMap<>();
            HolderLookup.Provider reg = level.registryAccess();
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        BlockPos p = new BlockPos(box.minX() + x, box.minY() + y, box.minZ() + z);
                        int i = (y * sz + z) * sx + x;
                        states[i] = level.getBlockState(p);
                        BlockEntity be = level.getBlockEntity(p);
                        if (be != null) {
                            entities.put(i, be.saveWithFullMetadata(reg));
                        }
                    }
                }
            }
            return new Snapshot(sx, sy, sz, states, entities);
        }

        BlockState at(int x, int y, int z) {
            return states[(y * sz + z) * sx + x];
        }

        /** Puts the site back as it was (top down, so nothing hangs on what's gone), block entities and all. */
        void restore(ServerLevel level, BoundingBox box) {
            HolderLookup.Provider reg = level.registryAccess();
            for (int y = sy - 1; y >= 0; y--) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        BlockPos p = new BlockPos(box.minX() + x, box.minY() + y, box.minZ() + z);
                        empty(level, p);
                        level.setBlock(p, states[(y * sz + z) * sx + x], QUIET);
                    }
                }
            }
            for (Map.Entry<Integer, CompoundTag> e : entities.entrySet()) {
                int i = e.getKey();
                BlockPos p = new BlockPos(box.minX() + i % sx, box.minY() + i / (sx * sz), box.minZ() + (i / sx) % sz);
                BlockEntity be = level.getBlockEntity(p);
                if (be != null) {
                    be.loadWithComponents(e.getValue(), reg);
                    be.setChanged();
                }
            }
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
            ListTag es = new ListTag();
            entities.forEach((i, tag) -> {
                CompoundTag e = new CompoundTag();
                e.putInt("Cell", i);
                e.put("Data", tag);
                es.add(e);
            });
            t.put("Entities", es);
            return t;
        }

        static Snapshot load(CompoundTag t) {
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
            Map<Integer, CompoundTag> entities = new HashMap<>();
            for (Tag tag : t.getList("Entities", Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) tag;
                entities.put(e.getInt("Cell"), e.getCompound("Data"));
            }
            return new Snapshot(s[0], s[1], s[2], states, entities);
        }
    }

    /** setBlock flags: tell clients, don't update shapes round it, never drop anything */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** Empties a container before it's replaced, so its contents don't spill (they're in the snapshot). */
    private static void empty(ServerLevel level, BlockPos p) {
        Clearable.tryClear(level.getBlockEntity(p));
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

    /** the sessions going on now (players who are online in them), by player */
    private static final Map<UUID, Session> active = new HashMap<>();

    public static boolean building(ServerPlayer player) {
        return active.containsKey(player.getUUID());
    }

    private static Optional<Session> siteAt(Level level, BlockPos pos) {
        for (Session s : active.values()) {
            if (s.dimension.equals(level.dimension()) && s.inside(pos)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static Optional<Session> near(Level level, Vec3 pos) {
        BlockPos p = BlockPos.containing(pos);
        for (Session s : active.values()) {
            if (s.dimension.equals(level.dimension()) && s.site.inflatedBy(1).isInside(p)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    // ---- starting and finishing ---------------------------------------------------------------------------------

    /** Starts free build in a table's site; returns why not, or null. */
    public static String start(ServerPlayer player, ServerLevel level, BlockPos table, Project p) {
        if (!p.hasSite()) {
            return "Choose a site first.";
        }
        if (building(player)) {
            return "You're already building somewhere: finish that first.";
        }
        BoundingBox site = Tables.siteBox(p);
        for (Session s : active.values()) {
            if (s.dimension.equals(level.dimension()) && s.site.intersects(site)) {
                return "Someone is building in that site already.";
            }
        }
        Optional<Colony> colony = ColonyManager.get(level.getServer()).at(level.dimension(), table);
        if (colony.isEmpty() || !colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers can build in the site.";
        }
        for (Building b : colony.get().buildings()) {
            if (!b.done() && b.placement().map(pl -> pl.bounds().intersects(site)).orElse(false)) {
                return "The builders have the " + b.name + " planned there: cancel it or let them finish first.";
            }
        }
        if (!level.getPlayers(o -> o != player && site.inflatedBy(1).isInside(o.blockPosition())).isEmpty()) {
            return "Someone is standing in the site.";
        }
        Session s = new Session(player.getUUID(), level.dimension(), table.immutable(), site, Snapshot.capture(level, site),
                player.getInventory().save(new ListTag()), player.gameMode.getGameModeForPlayer(), player.position(), player.getYRot(), player.getXRot());
        Data data = Data.get(level.getServer());
        data.sessions.put(player.getUUID(), s);
        data.setDirty();
        level.getServer().overworld().getDataStorage().save(); // on disk before anything changes
        active.put(player.getUUID(), s);
        // the design, built for real in the site
        for (Map.Entry<Integer, BlockState> e : p.design().entrySet()) {
            if (!p.inside(e.getKey())) {
                continue;
            }
            BlockPos at = p.min.offset(Project.kx(e.getKey()), Project.ky(e.getKey()), Project.kz(e.getKey()));
            empty(level, at);
            level.setBlock(at, e.getValue(), QUIET);
        }
        player.getInventory().clearContent();
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        // up at the top of the site, at its north edge, looking down over it
        player.teleportTo(level, site.minX() + site.getXSpan() / 2.0, site.maxY() - 1, site.minZ() + 1.0, 0f, 40f);
        player.sendSystemMessage(Component.literal("Building in the site: anything you can place, it's all yours to design. Nothing leaves it. ")
                .withStyle(ChatFormatting.GOLD).append(buttons()));
        return null;
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
     * Ends a player's free build: {@code keep} makes what they built the table's design. The site is put back and so
     * are they. Returns what happened, or null when they weren't building.
     */
    public static String finish(ServerPlayer player, boolean keep) {
        Session s = active.remove(player.getUUID());
        if (s == null) {
            return null;
        }
        ServerLevel level = player.server.getLevel(s.dimension);
        String result = "Put the site back as it was.";
        if (level != null) {
            if (keep) {
                result = keep(level, s);
            }
            s.snapshot.restore(level, s.site);
        }
        giveBack(player, s, level);
        Data data = Data.get(player.server);
        data.sessions.remove(player.getUUID());
        data.setDirty();
        return result;
    }

    /** What they built, as the table's design (on top of the generated building). */
    private static String keep(ServerLevel level, Session s) {
        if (!(level.getBlockEntity(s.table) instanceof ArchitectTableBlockEntity be) || !be.project().hasSite()
                || !Tables.siteBox(be.project()).equals(s.site)) {
            return "The table is gone (or has another site now), so what you built wasn't kept.";
        }
        Project p = be.project();
        int changed = 0;
        for (int y = 0; y < s.snapshot.sy; y++) {
            for (int z = 0; z < s.snapshot.sz; z++) {
                for (int x = 0; x < s.snapshot.sx; x++) {
                    int k = Project.key(x, y, z);
                    BlockState now = level.getBlockState(p.min.offset(x, y, z));
                    BlockState was = s.snapshot.at(x, y, z);
                    if (p.at(k) == null && settled(was, now)) {
                        now = was; // grass under a floor turning to dirt isn't something they built
                    }
                    now = shut(now); // a door they walked through stays shut in the design
                    BlockState generated = p.generated.get(k);
                    BlockState base = generated != null ? generated : s.snapshot.at(x, y, z);
                    BlockState before = p.at(k) != null ? p.at(k) : s.snapshot.at(x, y, z);
                    if (!now.equals(before)) {
                        changed++;
                    }
                    if (now.equals(base) || !Tables.allowed(now) && !now.isAir()) {
                        p.edits.remove(k);
                    } else {
                        p.edits.put(k, now);
                    }
                }
            }
        }
        be.setChanged();
        return changed == 0 ? "You didn't change anything." : "Kept what you built in the design (" + changed
                + (changed == 1 ? " block" : " blocks") + " changed). Open the table to see it, save it or plan it.";
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

    /** The land changing by itself: grass going to dirt under something, snow melting. */
    private static boolean settled(BlockState was, BlockState now) {
        return was.is(net.minecraft.tags.BlockTags.DIRT) && now.is(net.minecraft.tags.BlockTags.DIRT)
                || was.is(Blocks.SNOW) && now.isAir();
    }

    private static void giveBack(ServerPlayer player, Session s, ServerLevel level) {
        player.getInventory().clearContent();
        player.getInventory().load(s.inventory);
        player.setGameMode(s.previous);
        if (s.previous != GameType.CREATIVE && s.previous != GameType.SPECTATOR) {
            player.getAbilities().flying = false;
            player.getAbilities().mayfly = false;
            player.onUpdateAbilities();
        }
        player.containerMenu.broadcastChanges();
        ServerLevel to = level != null ? level : player.serverLevel();
        player.teleportTo(to, s.back.x, s.back.y, s.back.z, s.yRot, s.xRot);
        player.fallDistance = 0;
    }

    // ---- keeping everything in the site -------------------------------------------------------------------------

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
        bus.addListener(FreeBuild::loggedOut);
        bus.addListener(FreeBuild::started);
        bus.addListener(FreeBuild::stopping);
    }

    private static void deny(ServerPlayer p, String why) {
        p.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.RED), true);
    }

    private static void broke(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof Level level)) {
            return;
        }
        Session mine = e.getPlayer() == null ? null : active.get(e.getPlayer().getUUID());
        Optional<Session> there = siteAt(level, e.getPos());
        if (mine != null && (!mine.dimension.equals(level.dimension()) || !mine.inside(e.getPos()))) {
            e.setCanceled(true);
            deny((ServerPlayer) e.getPlayer(), "You're building in the site: you can't break anything outside it.");
        } else if (mine == null && there.isPresent()) {
            e.setCanceled(true);
            if (e.getPlayer() instanceof ServerPlayer sp) {
                deny(sp, "Someone is designing in this site right now.");
            }
        }
    }

    private static void placed(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof Level level)) {
            return;
        }
        Session mine = e.getEntity() instanceof ServerPlayer sp ? active.get(sp.getUUID()) : null;
        Optional<Session> there = siteAt(level, e.getPos());
        if (mine != null) {
            BlockState s = e.getPlacedBlock();
            if (!mine.dimension.equals(level.dimension()) || !mine.inside(e.getPos())) {
                e.setCanceled(true);
                deny((ServerPlayer) e.getEntity(), "You're building in the site: you can only place blocks inside it.");
            } else if (!Tables.allowed(s) || MOVERS.contains(s.getBlock())) {
                e.setCanceled(true);
                deny((ServerPlayer) e.getEntity(), s.getBlock().getName().getString() + " can't be part of a design.");
            }
        } else if (there.isPresent()) {
            e.setCanceled(true);
            if (e.getEntity() instanceof ServerPlayer sp) {
                deny(sp, "Someone is designing in this site right now.");
            }
        }
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !building(p)) {
            return;
        }
        ItemStack held = e.getItemStack();
        if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) {
            e.setCanceled(true);
            deny(p, "While you design, only blocks: no items.");
            return;
        }
        BlockState target = p.level().getBlockState(e.getPos());
        if (p.level().getBlockEntity(e.getPos()) != null || target.getBlock() instanceof EnderChestBlock) {
            e.setUseBlock(TriState.FALSE); // no opening containers: place against them instead
        }
    }

    private static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p) && !(e.getItemStack().getItem() instanceof BlockItem)) {
            e.setCanceled(true);
            deny(p, "While you design, only blocks: no items.");
        }
    }

    private static void entityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            e.setCanceled(true);
        }
    }

    private static void entityInteractAt(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            e.setCanceled(true);
        }
    }

    private static void attack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            e.setCanceled(true);
        }
    }

    private static void toss(ItemTossEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && building(p)) {
            e.setCanceled(true);
        }
    }

    /** Nothing becomes an item (or a falling block) in a site being designed. */
    private static void joined(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || active.isEmpty()) {
            return;
        }
        if ((e.getEntity() instanceof ItemEntity || e.getEntity() instanceof ExperienceOrb || e.getEntity() instanceof FallingBlockEntity)
                && near(e.getLevel(), e.getEntity().position()).isPresent()) {
            e.setCanceled(true);
        }
    }

    private static void exploded(ExplosionEvent.Detonate e) {
        if (!active.isEmpty()) {
            e.getAffectedBlocks().removeIf(p -> siteAt(e.getLevel(), p).isPresent());
        }
    }

    private static void died(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            finish(p, true);
        }
    }

    /** Flying out of the site puts you back in it. */
    private static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || active.isEmpty()) {
            return;
        }
        Session s = active.get(p.getUUID());
        if (s == null || p.tickCount % 5 != 0) {
            return;
        }
        if (!p.level().dimension().equals(s.dimension)) {
            String r = finish(p, true);
            p.sendSystemMessage(Component.literal(r).withStyle(ChatFormatting.GOLD));
            return;
        }
        if (!p.isCreative()) {
            p.setGameMode(GameType.CREATIVE);
        }
        BoundingBox room = s.site.inflatedBy(1);
        BlockPos at = p.blockPosition();
        if (!room.isInside(at) || !room.isInside(BlockPos.containing(p.getEyePosition()))) {
            double x = Math.max(s.site.minX() + 0.5, Math.min(s.site.maxX() + 0.5, p.getX()));
            double y = Math.max(s.site.minY(), Math.min(s.site.maxY() - 1, p.getY()));
            double z = Math.max(s.site.minZ() + 0.5, Math.min(s.site.maxZ() + 0.5, p.getZ()));
            p.teleportTo(x, y, z);
            p.displayClientMessage(Component.literal("You're designing in the site: Done or Cancel (in chat) to leave.").withStyle(ChatFormatting.YELLOW), true);
        }
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && building(p)) {
            finish(p, true); // before the player is saved
        }
    }

    /** Back after a crash: their things and the site are put back. */
    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Data data = Data.get(p.server);
        Session s = data.sessions.remove(p.getUUID());
        if (s == null) {
            return;
        }
        ServerLevel level = p.server.getLevel(s.dimension);
        if (level != null && !s.siteRestored) {
            s.snapshot.restore(level, s.site);
        }
        giveBack(p, s, level);
        data.setDirty();
        p.sendSystemMessage(Component.literal("Your last free build didn't finish (the server stopped): the site is as it was, and you have your things back.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** After a crash: put every site back now; players get their things when they come back. */
    private static void started(ServerStartedEvent e) {
        active.clear();
        Data data = Data.get(e.getServer());
        for (Session s : data.sessions.values()) {
            ServerLevel level = e.getServer().getLevel(s.dimension);
            if (level != null && !s.siteRestored) {
                s.snapshot.restore(level, s.site);
                s.siteRestored = true;
                data.setDirty();
                Stad.LOGGER.info("Put back a free build site at {} after the server stopped mid-build", s.site);
            }
        }
    }

    private static void stopping(ServerStoppingEvent e) {
        for (ServerPlayer p : List.copyOf(e.getServer().getPlayerList().getPlayers())) {
            if (building(p)) {
                finish(p, true);
            }
        }
        active.clear();
    }

    /** For tests: the server stopped mid-build and started again, and the player comes back. */
    public static void restartForTest(ServerPlayer player) {
        active.clear();
        started(new ServerStartedEvent(player.server));
        loggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
    }

    /** For tests: the sites being designed in now. */
    public static List<BoundingBox> sites() {
        return active.values().stream().map(s -> s.site).toList();
    }
}
