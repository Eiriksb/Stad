package dev.eiriksb.stad.pack;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Contents;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.colony.Adoption;
import dev.eiriksb.stad.net.EditorPayload;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pack editor's server side (PLAN.md §6.5): a building just scanned with the wand, or one already in a pack, is
 * shown with its guessed block roles; the player renames it, says what kind of building it is, fixes wrong guesses,
 * and saves it into My pack. Server thread.
 */
public final class PackEditor {
    private PackEditor() {}

    /** the kinds of building a pack building can be */
    public static final List<ResourceLocation> TYPES = List.of(BuildingDef.HOME, BuildingDef.BUILDER_HUT, BuildingDef.WAREHOUSE,
            Requirements.WORKSHOP, ResourceLocation.fromNamespaceAndPath("stad", "building"));

    /**
     * A building open in the editor.
     *
     * @param key   the pack building it is, or null for a new scan
     * @param spec  the builder's spec it came from (for designs), or null
     */
    public record Session(String token, ResourceLocation key, Blueprint blueprint, String name, ResourceLocation type, List<Roles.Fix> fixes,
                          String spec, String description) {
        public boolean editable() {
            return key == null || MyPack.contains(key);
        }
    }

    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public static Optional<Session> session(ServerPlayer player) {
        return Optional.ofNullable(sessions.get(player.getUUID()));
    }

    /** Whether the player may change My pack (a server setting; operators always may). */
    public static boolean mayEdit(ServerPlayer player) {
        return StadConfig.EVERYONE_EDITS_PACKS.get() || player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /** Scans what the player marked with the wand and opens it in the editor. */
    public static Session openScan(ServerPlayer player) {
        Scans.Selection sel = Scans.get(player).orElse(null);
        String problem = Scans.problem(sel);
        if (problem != null) {
            player.displayClientMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
            return null;
        }
        Session s = scan(player, sel.box());
        open(player, s);
        return s;
    }

    /** A building in the world, turned so its front door faces north as pack buildings do, as a new session. */
    public static Session scan(ServerPlayer player, BoundingBox box) {
        Blueprint raw = Blueprint.capture(player.level(), box);
        Vec3 viewer = player.position().subtract(box.minX(), box.minY(), box.minZ());
        Direction front = front(raw, viewer);
        Blueprint bp = raw.rotated(toNorth(front));
        ResourceLocation type = Requirements.guess(Contents.of(bp)).orElse(TYPES.getLast());
        Session s = new Session(UUID.randomUUID().toString(), null, bp, Adoption.name(type, null),
                type, List.of(), null, "");
        sessions.put(player.getUUID(), s);
        return s;
    }

    /** Opens a pack's building in the editor. */
    public static Session openExisting(ServerPlayer player, BuildingDef def) {
        Session s = new Session(UUID.randomUUID().toString(), def.key(), def.blueprint(), def.name(), def.type(), def.fixes(), def.spec(),
                def.description());
        sessions.put(player.getUUID(), s);
        open(player, s);
        return s;
    }

    /** Which side of a scanned building is its front: where a door opens onto open air, else the side the viewer is on. */
    static Direction front(Blueprint bp, Vec3 viewer) {
        Map<BlockPos, BlockState> at = new HashMap<>();
        bp.entries().forEach(e -> at.put(e.pos(), e.state()));
        Direction best = null;
        int bestY = Integer.MAX_VALUE;
        for (Blueprint.Entry e : bp.entries()) {
            BlockState s = e.state();
            if (!(s.getBlock() instanceof DoorBlock) || s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || e.pos().getY() >= bestY) {
                continue;
            }
            Direction facing = s.getValue(DoorBlock.FACING);
            for (Direction d : new Direction[]{facing, facing.getOpposite()}) {
                if (openTo(at, bp, e.pos(), d)) {
                    best = d;
                    bestY = e.pos().getY();
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        double dx = viewer.x - bp.size().getX() / 2.0, dz = viewer.z - bp.size().getZ() / 2.0;
        return Direction.getNearest(dx, 0, dz);
    }

    /** Whether you can walk straight out of the building from {@code pos} going {@code d}. */
    private static boolean openTo(Map<BlockPos, BlockState> at, Blueprint bp, BlockPos pos, Direction d) {
        BlockPos p = pos.relative(d);
        while (p.getX() >= 0 && p.getZ() >= 0 && p.getX() < bp.size().getX() && p.getZ() < bp.size().getZ()) {
            BlockState s = at.get(p);
            if (s != null && !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return false;
            }
            p = p.relative(d);
        }
        return true;
    }

    /** The turn that brings {@code front} round to the north. */
    static Rotation toNorth(Direction front) {
        return switch (front) {
            case SOUTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** What the editor shows. */
    public static EditorPayload payload(Session s) {
        Roles.Analysis a = Roles.analyse(s.blueprint(), s.fixes());
        List<EditorPayload.Row> rows = new ArrayList<>();
        for (Roles.Group g : Roles.groups(a)) {
            rows.add(new EditorPayload.Row(BuiltInRegistries.BLOCK.getKey(g.block()).toString(), g.guessed().id(), g.role().id(), g.count()));
        }
        Contents c = Contents.of(s.blueprint());
        List<String> types = TYPES.stream().map(ResourceLocation::toString).toList();
        List<String> needs = TYPES.stream().map(t -> Design.join(Requirements.missing(t, c))).toList();
        Map<String, Integer> markers = new TreeMap<>();
        s.blueprint().markers().forEach(m -> markers.merge(m.name().replace("stad:", ""), 1, Integer::sum));
        List<String> ms = new ArrayList<>();
        markers.forEach((name, n) -> ms.add(name + (n > 1 ? " x" + n : "")));
        var size = s.blueprint().size();
        return new EditorPayload(s.token(), s.name(), s.type().toString(), types, needs, s.editable(), size.getX(), size.getY(), size.getZ(),
                rows, contents(c), ms.isEmpty() ? "none" : String.join(", ", ms));
    }

    /** "2 beds, a door, 3 chests and a crafting table" */
    public static String contents(Contents c) {
        List<String> parts = new ArrayList<>();
        count(parts, c.count(Contents.Thing.BED), "bed");
        count(parts, c.count(Contents.Thing.DOOR), "door");
        count(parts, c.count(Contents.Thing.STORAGE), "chest");
        count(parts, c.count(Contents.Thing.CRAFTING_TABLE), "crafting table");
        count(parts, c.count(Contents.Thing.COOKING), "stove");
        count(parts, c.count(Contents.Thing.WORKSTATION), "workstation");
        count(parts, c.count(Contents.Thing.LIGHT), "light");
        return parts.isEmpty() ? "nothing to use" : Design.join(parts);
    }

    private static void count(List<String> parts, int n, String thing) {
        if (n > 0) {
            parts.add((n == 1 ? "a" : String.valueOf(n)) + " " + thing + (n == 1 ? "" : "s"));
        }
    }

    private static void open(ServerPlayer player, Session s) {
        EditorPayload p = payload(s);
        if (player.connection != null && player.connection.hasChannel(EditorPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, p);
            return;
        }
        // no Stad on their client: the gist in chat, and the commands do the rest
        player.sendSystemMessage(Component.literal("Scanned " + p.sizeX() + " x " + p.sizeY() + " x " + p.sizeZ() + ": " + p.contents()
                + ". Save it with /stad scan save <name>.").withStyle(ChatFormatting.AQUA));
    }

    /** The editor's Save: corrections from the rows (where the role differs from the guess), the name and kind. */
    public static ResourceLocation save(ServerPlayer player, String token, String name, String type, List<EditorPayload.Row> rows) throws Exception {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.token().equals(token)) {
            throw new IllegalStateException("That editor has closed; open it again.");
        }
        List<Roles.Fix> fixes = new ArrayList<>();
        for (EditorPayload.Row r : rows) {
            if (!r.role().equals(r.guessed())) {
                Optional<Block> block = Optional.ofNullable(ResourceLocation.tryParse(r.block())).flatMap(BuiltInRegistries.BLOCK::getOptional);
                Optional<Roles.Role> from = Roles.Role.of(r.guessed()), to = Roles.Role.of(r.role());
                if (block.isPresent() && from.isPresent() && to.isPresent()) {
                    fixes.add(new Roles.Fix(block.get(), from.get(), to.get()));
                }
            }
        }
        ResourceLocation t = ResourceLocation.tryParse(type);
        return save(player, s, name, t != null && TYPES.contains(t) ? t : s.type(), fixes);
    }

    /** Saves a session into My pack (over the building it came from, when that's one of My pack's). */
    public static ResourceLocation save(ServerPlayer player, Session s, String name, ResourceLocation type, List<Roles.Fix> fixes) throws Exception {
        if (!mayEdit(player)) {
            throw new IllegalStateException("Only operators can change this world's building pack.");
        }
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.length() < 2 || clean.length() > 40) {
            throw new IllegalArgumentException("Give it a name of 2 to 40 letters.");
        }
        String id = s.key() != null && MyPack.contains(s.key()) ? s.key().getPath().substring(s.key().getPath().indexOf('/') + 1)
                : MyPack.freeId(player.server, clean);
        Contents c = Contents.of(s.blueprint());
        var size = s.blueprint().size();
        String description = s.description() != null && !s.description().isBlank() ? s.description()
                : "Scanned by " + player.getGameProfile().getName() + ": " + contents(c) + "; " + size.getX() + " x " + size.getZ() + ", "
                + size.getY() + " high.";
        ResourceLocation key = MyPack.save(player.server, id, clean, type, description, s.blueprint(), s.spec(), fixes,
                List.of("custom", type.getPath()), player.getGameProfile().getName());
        sessions.remove(player.getUUID());
        Stad.LOGGER.info("{} saved {} ({}) in My pack", player.getGameProfile().getName(), clean, key);
        return key;
    }

    public static void close(ServerPlayer player) {
        sessions.remove(player.getUUID());
    }

    public static void forget(UUID player) {
        sessions.remove(player);
    }
}
