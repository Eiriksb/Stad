package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.agent.Leadership;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderRecord;
import dev.eiriksb.stad.net.DraftPreviewPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buildings players are drawing up with a builder (PLAN.md §6.4): one draft per player, shown to them as a ghost
 * where it would go, until they approve it (it becomes a planned building), drop it, or leave it for half an hour.
 * Server thread.
 */
public final class Drafts {
    /** Where a draft should go. */
    public enum Where { SAME, AUTO, HERE, NEAR, TURN }

    public static final class Draft {
        public final UUID player;
        public final int colony;
        /** the builder drawing it up (null when drafted by command with no builder about) */
        public UUID builder;
        public Design design;
        /** the ground block it's centred on */
        public BlockPos site;
        public Rotation rotation = Rotation.NONE;
        /** the place it was put by ("the water"), if it was */
        public String landmark;
        long touched;

        Draft(UUID player, int colony) {
            this.player = player;
            this.colony = colony;
        }

        public BlockPos origin() {
            return Placement.originFor(design.blueprint(), site, rotation);
        }

        public Placement placement() {
            return Placement.of(design.blueprint(), origin(), rotation);
        }
    }

    /** How an approval went: like an order, with the planned building when it went ahead. */
    public record Approval(Order.Outcome outcome, String reason, Building building) {}

    private static final long EXPIRE_TICKS = 30 * 60 * 20;
    private static final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    private Drafts() {}

    public static Optional<Draft> get(UUID player) {
        return Optional.ofNullable(drafts.get(player));
    }

    /** Draws up {@code spec} for {@code player} (a new draft, or a revision of theirs) and shows it. Returns the draft. */
    public static Draft draw(ServerPlayer player, Colony colony, UUID builder, BuildSpec spec, Where where, String near) {
        Draft old = drafts.get(player.getUUID());
        Draft d = old != null && old.colony == colony.id ? old : new Draft(player.getUUID(), colony.id);
        d.builder = builder != null ? builder : d.builder;
        d.design = Design.make(spec);
        d.touched = player.level().getGameTime();
        drafts.put(player.getUUID(), d);
        if (d.site == null) {
            place(player, colony, d, where == Where.SAME || where == Where.TURN ? Where.AUTO : where, near);
        } else if (where != Where.SAME) {
            place(player, colony, d, where, near);
        }
        sync(player);
        return d;
    }

    /** Moves a player's draft; returns whether there was one to move. */
    public static boolean move(ServerPlayer player, Where where, String near) {
        Draft d = drafts.get(player.getUUID());
        Colony colony = d == null ? null : ColonyManager.get(player.server).byId(d.colony).orElse(null);
        if (colony == null) {
            return false;
        }
        place(player, colony, d, where, near);
        d.touched = player.level().getGameTime();
        sync(player);
        return true;
    }

    private static void place(ServerPlayer player, Colony colony, Draft d, Where where, String near) {
        ServerLevel level = player.serverLevel();
        if (where != Where.TURN) {
            d.landmark = null;
        }
        Blueprint bp = d.design.blueprint();
        switch (where) {
            case TURN -> d.rotation = d.rotation.getRotated(Rotation.CLOCKWISE_90);
            case HERE -> {
                // where they're looking, unless that's right by their feet: then far enough ahead not to build on top of them
                HitResult hit = player.pick(64, 1f, false);
                int ahead = Math.max(bp.size().getX(), bp.size().getZ()) / 2 + 3;
                BlockPos target;
                if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                        && b.getBlockPos().distToCenterSqr(player.position()) >= ahead * ahead) {
                    target = b.getBlockPos();
                } else {
                    Vec3 look = player.getLookAngle().multiply(1, 0, 1).normalize();
                    target = BlockPos.containing(player.position().add(look.scale(ahead)));
                }
                BlockPos ground = ground(level, target.getX(), target.getZ());
                d.site = ground != null ? ground : target;
                d.rotation = Placement.facingViewer(player.getDirection());
                if (problem(d, colony, level) != null) {
                    site(level, colony, d, d.site, player.position(), 0); // the nearest spot that works
                }
            }
            case NEAR -> {
                BlockPos anchor = anchor(colony, near);
                Vec3 face = player.position();
                if (anchor == null) { // a place rather than a building: by the lake, facing it
                    Optional<Landmarks.Landmark> place = Landmarks.find(level, colony, near, player.blockPosition());
                    if (place.isPresent()) {
                        anchor = place.get().pos();
                        face = Vec3.atCenterOf(anchor);
                        d.landmark = place.get().name();
                    }
                }
                if (anchor == null || !site(level, colony, d, anchor, face, true)) {
                    if (d.site == null) {
                        site(level, colony, d, player.blockPosition(), player.position(), false);
                    }
                }
            }
            case AUTO -> site(level, colony, d, player.blockPosition(), player.position(), false);
            default -> {
            }
        }
        if (d.site == null) {
            d.site = player.blockPosition().below();
            d.rotation = Placement.facingViewer(player.getDirection());
        }
    }

    /** The building (or the town hall) a player means by {@code name}. */
    static BlockPos anchor(Colony colony, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT).replace("the ", "").strip();
        if (n.contains("hall") || n.contains("town")) {
            return colony.center;
        }
        for (Building b : colony.buildings()) {
            String bn = b.name.toLowerCase(Locale.ROOT);
            if (bn.contains(n) || n.contains(bn)) {
                return b.placement().map(p -> p.bounds().getCenter()).orElse(b.origin);
            }
        }
        return null;
    }

    /**
     * Finds somewhere fairly flat on the town's land, clear of other buildings, near {@code around} (next to it when
     * {@code adjacent}, else a little way off so the viewer can see it), with its front towards the viewer.
     */
    static boolean site(ServerLevel level, Colony colony, Draft d, BlockPos around, Vec3 viewer, boolean adjacent) {
        int half = Math.max(d.design.blueprint().size().getX(), d.design.blueprint().size().getZ()) / 2;
        return site(level, colony, d, around, viewer, adjacent ? half + 4 : half + 5);
    }

    /** As above, as close as possible to {@code preferred} blocks from {@code around}. */
    static boolean site(ServerLevel level, Colony colony, Draft d, BlockPos around, Vec3 viewer, double preferred) {
        Blueprint bp = d.design.blueprint();
        BlockPos best = null;
        Rotation bestRot = null;
        double bestScore = Double.MAX_VALUE;
        int r = colony.radius;
        for (int x = colony.center.getX() - r; x <= colony.center.getX() + r; x += 2) {
            for (int z = colony.center.getZ() - r; z <= colony.center.getZ() + r; z += 2) {
                double dist = Math.sqrt((x - around.getX()) * (double) (x - around.getX()) + (z - around.getZ()) * (double) (z - around.getZ()));
                double score = Math.abs(dist - preferred);
                if (score >= bestScore || !level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                BlockPos ground = ground(level, x, z);
                if (ground == null || !level.getFluidState(ground.above()).isEmpty()) {
                    continue;
                }
                Direction facing = Direction.getNearest(x - viewer.x, 0, z - viewer.z);
                Rotation rot = Placement.facingViewer(facing);
                BlockPos origin = Placement.originFor(bp, ground, rot);
                if (Planning.problem(colony, bp, origin, rot) != null) {
                    continue;
                }
                BoundingBox box = Placement.of(bp, origin, rot).bounds();
                if (box.isInside(BlockPos.containing(viewer)) || !flat(level, box, ground.getY()) || built(level, box) > 0) {
                    continue;
                }
                best = ground;
                bestRot = rot;
                bestScore = score;
            }
        }
        if (best == null) {
            return false;
        }
        d.site = best;
        d.rotation = bestRot;
        return true;
    }

    /** The top block of real ground (terrain, not trees, roofs or plants) at x, z; null over water or the void. */
    static BlockPos ground(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below().mutable();
        for (int i = 0; i < 48 && p.getY() > level.getMinBuildHeight(); i++, p.move(0, -1, 0)) {
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) {
                return null;
            }
            if (natural(s) && !s.canBeReplaced() && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && s.isSolid()) {
                return p.immutable();
            }
        }
        return null;
    }

    private static boolean flat(ServerLevel level, BoundingBox box, int y) {
        int[][] corners = {{box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}};
        for (int[] c : corners) {
            if (!level.hasChunkAt(new BlockPos(c[0], 0, c[1]))) {
                return false;
            }
            BlockPos g = ground(level, c[0], c[1]);
            if (g == null || Math.abs(g.getY() - y) > 2) {
                return false;
            }
        }
        return true;
    }

    /** Where the draft is, for the builder to say: "about 12 blocks north-east of you" (", by the water, facing it"). */
    public static String where(Draft d, ServerPlayer player) {
        return whereFrom(d, player) + (d.landmark == null ? "" : ", by " + d.landmark + " with its door facing it");
    }

    private static String whereFrom(Draft d, ServerPlayer player) {
        BlockPos c = d.placement().bounds().getCenter();
        double dx = c.getX() - player.getX(), dz = c.getZ() - player.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 4) {
            return "right where " + player.getGameProfile().getName() + " is standing";
        }
        String ns = dz < -dist * 0.38 ? "north" : dz > dist * 0.38 ? "south" : "";
        String ew = dx > dist * 0.38 ? "east" : dx < -dist * 0.38 ? "west" : "";
        String dir = ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
        return "about " + dist + " blocks " + dir + " of " + player.getGameProfile().getName();
    }

    /** Blocks in {@code box} that look built by someone (anything but terrain, trees and plants): the builder would tear them down. */
    public static java.util.Set<String> builtKinds(ServerLevel level, BoundingBox box) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !natural(s)) {
                out.add(s.getBlock().toString());
            }
        }
        return out;
    }

    static int built(ServerLevel level, BoundingBox box) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !natural(s)) {
                n++;
            }
        }
        return n;
    }

    private static boolean natural(BlockState s) {
        return s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CORAL_PLANTS) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(Blocks.BEE_NEST)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || !s.getFluidState().isEmpty();
    }

    /** Why it can't go where it is (including tearing down what's already built there), or null. */
    public static String problem(Draft d, Colony colony, ServerLevel level) {
        String p = Planning.problem(colony, d.design.blueprint(), d.origin(), d.rotation);
        if (p != null) {
            return p;
        }
        int built = built(level, d.placement().bounds());
        return built > 0 ? "That would tear down " + built + (built == 1 ? " block" : " blocks") + " of something already built there." : null;
    }

    /** What it takes, item by item, with what the town has. */
    public static List<DraftPreviewPayload.Material> materials(Draft d, ServerLevel level, Colony colony) {
        ColonyStorage storage = ColonyStorage.supply(level, colony);
        List<DraftPreviewPayload.Material> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : d.placement().materials().entrySet()) {
            out.add(new DraftPreviewPayload.Material(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue(), storage.count(e.getKey())));
        }
        out.sort((a, b) -> Integer.compare(b.needed(), a.needed()));
        return out;
    }

    /** "It takes 214 blocks: 96 stone bricks, 40 spruce stairs ... The town has all of it / is short of ..." */
    public static String materialsSentence(Draft d, ServerLevel level, Colony colony) {
        List<DraftPreviewPayload.Material> m = materials(d, level, colony);
        int total = m.stream().mapToInt(DraftPreviewPayload.Material::needed).sum();
        List<String> big = new ArrayList<>();
        for (int i = 0; i < Math.min(4, m.size()); i++) {
            big.add(m.get(i).needed() + " " + itemName(m.get(i).item()));
        }
        List<String> short_ = new ArrayList<>();
        for (DraftPreviewPayload.Material x : m) {
            if (x.have() < x.needed() && short_.size() < 4) {
                short_.add((x.needed() - x.have()) + " " + itemName(x.item()));
            }
        }
        return "It takes " + total + " items (" + Design.join(big) + (m.size() > 4 ? ", and more" : "") + "). "
                + (short_.isEmpty() ? "The town has everything it needs." : "The town is short of " + Design.join(short_)
                + (m.stream().filter(x -> x.have() < x.needed()).count() > 4 ? " and more" : "") + ".");
    }

    private static String itemName(String id) {
        Item item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
        return item.getDescription().getString().toLowerCase(Locale.ROOT);
    }

    private static void send(ServerPlayer player, DraftPreviewPayload p) {
        if (player.connection != null && player.connection.hasChannel(DraftPreviewPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, p); // not to players without Stad on their client (or test players)
        }
    }

    /** Sends the player their draft's ghost (or clears it). */
    public static void sync(ServerPlayer player) {
        Draft d = drafts.get(player.getUUID());
        Colony colony = d == null ? null : ColonyManager.get(player.server).byId(d.colony).orElse(null);
        if (colony == null) {
            send(player, DraftPreviewPayload.CLEAR);
            return;
        }
        Placement p = d.placement();
        List<Placement.Step> solid = p.steps().stream().filter(s -> !s.clearing()).toList();
        long[] positions = new long[solid.size()];
        int[] states = new int[solid.size()];
        for (int i = 0; i < solid.size(); i++) {
            positions[i] = solid.get(i).pos().asLong();
            states[i] = Block.getId(solid.get(i).state());
        }
        String problem = problem(d, colony, player.serverLevel());
        send(player, new DraftPreviewPayload(d.design.name(), positions, states,
                materials(d, player.serverLevel(), colony), problem == null ? "" : problem));
    }

    /** A chat line with buttons: build it, move it here, turn it, save it, drop it. */
    public static void sendControls(ServerPlayer player) {
        Draft d = drafts.get(player.getUUID());
        if (d == null) {
            return;
        }
        MutableComponent line = Component.literal("Draft: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(d.design.name()).withStyle(ChatFormatting.WHITE)).append(" ");
        line.append(button("Build it", "/stad draft approve", "Plan it for the builders", ChatFormatting.GREEN));
        line.append(button("Move here", "/stad draft here", "Put it where you're looking", ChatFormatting.AQUA));
        line.append(button("Turn", "/stad draft turn", "Turn it a quarter round", ChatFormatting.AQUA));
        line.append(button("Save", "/stad draft save", "Keep the design in My pack, for the Building Plans", ChatFormatting.YELLOW));
        line.append(button("Drop", "/stad draft cancel", "Throw the draft away", ChatFormatting.RED));
        player.sendSystemMessage(line);
    }

    private static Component button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal("[" + label + "] ").withStyle(s -> s.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /**
     * The player approves their draft: like any plan it needs an officer and a free site, and the builder has to be
     * willing (a resented leader's builder may refuse). On success the draft becomes a planned building.
     */
    public static Approval approve(ServerPlayer player) {
        Draft d = drafts.get(player.getUUID());
        Colony colony = d == null ? null : ColonyManager.get(player.server).byId(d.colony).orElse(null);
        if (colony == null) {
            return new Approval(Order.Outcome.INVALID, "There's no draft to build.", null);
        }
        ServerLevel level = player.serverLevel();
        String name = d.design.name();
        CitizenEntity builder = d.builder != null && level.getEntity(d.builder) instanceof CitizenEntity c ? c : null;
        Leadership.Willingness w = null;
        String problem = problem(d, colony, level);
        if (Planning.canPlan(colony, player) && problem != null) {
            return new Approval(Order.Outcome.INVALID, problem, null);
        }
        if (Planning.canPlan(colony, player) && builder != null) {
            w = Leadership.willingness(builder, player, colony, OrderKind.PLAN_BUILDING, name);
            if (w.outcome() == Order.Outcome.REFUSED) {
                String reason = builder.getName().getString() + " won't: " + w.reason() + ".";
                colony.log(new OrderRecord(level.getGameTime(), player.getUUID(), player.getGameProfile().getName(), builder.getUUID(),
                        builder.getName().getString(), OrderKind.PLAN_BUILDING, Order.Outcome.REFUSED, reason));
                return new Approval(Order.Outcome.REFUSED, reason, null);
            }
        }
        Planning.Result r = Planning.plan(player, level, colony, d.design, d.origin(), d.rotation);
        if (r.building() == null) {
            return new Approval(Planning.canPlan(colony, player) ? Order.Outcome.INVALID : Order.Outcome.NOT_ALLOWED, r.problem(), null);
        }
        drafts.remove(player.getUUID());
        sync(player);
        if (w != null && w.outcome() == Order.Outcome.GRUDGING) {
            colony.citizen(builder.getUUID()).ifPresent(rec -> rec.grudgingUntil = level.getGameTime() + 24000);
            return new Approval(Order.Outcome.GRUDGING, w.reason(), r.building());
        }
        return new Approval(Order.Outcome.ACCEPTED, null, r.building());
    }

    public static boolean cancel(ServerPlayer player) {
        boolean had = drafts.remove(player.getUUID()) != null;
        sync(player);
        return had;
    }

    /** Every few seconds: forgets drafts nobody has touched for a while. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        drafts.values().removeIf(d -> {
            ServerPlayer p = server.getPlayerList().getPlayer(d.player);
            if (p == null || now - d.touched > EXPIRE_TICKS) {
                if (p != null) {
                    send(p, DraftPreviewPayload.CLEAR);
                }
                return true;
            }
            return false;
        });
    }

    public static void clearAll() {
        drafts.clear();
    }
}
