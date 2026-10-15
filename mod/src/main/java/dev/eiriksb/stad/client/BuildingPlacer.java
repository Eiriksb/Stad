package dev.eiriksb.stad.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.net.PlacePreviewPayload;
import dev.eiriksb.stad.net.PlanActionPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Putting a building from the building plans down by hand, the way a Create schematic is placed: Preview shows the
 * whole building, see-through, where it would go. It follows where you look until you right-click to put it down;
 * then the arrow keys move it (away from you, back, left and right), Page Up and Down raise and lower it, R turns it,
 * right-click picks it up again, Enter plans it there and Backspace puts it away. The server says whether it fits
 * wherever it's moved to (red when it doesn't, and why). For an area marked out with the plans, it stays inside the
 * area; one drawn up to fill the area stays put, and R draws it up again with its door the next way round. The area
 * itself is drawn whenever the plans that marked it are in hand.
 */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class BuildingPlacer {
    private BuildingPlacer() {}

    private static final String CATEGORY = "key.categories.stad";
    public static final KeyMapping TURN = key("turn", GLFW.GLFW_KEY_R);
    public static final KeyMapping PLAN = key("plan_here", GLFW.GLFW_KEY_ENTER);
    public static final KeyMapping CANCEL = key("put_away", GLFW.GLFW_KEY_BACKSPACE);
    public static final KeyMapping UP = key("raise", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping DOWN = key("lower", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping AWAY = key("move_away", GLFW.GLFW_KEY_UP);
    public static final KeyMapping BACK = key("move_back", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping LEFT = key("move_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping RIGHT = key("move_right", GLFW.GLFW_KEY_RIGHT);
    public static final List<KeyMapping> KEYS = List.of(TURN, PLAN, CANCEL, UP, DOWN, AWAY, BACK, LEFT, RIGHT);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.stad.place." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    /** how far away it can follow where you look */
    static final double REACH = 48;

    /** what's being placed (null: nothing) */
    private static PlacePreviewPayload building;
    private static Blueprint blueprint;
    private static BlockPos at;
    private static Rotation rotation = Rotation.NONE;
    private static boolean following;
    private static List<GhostPreview.Ghost> ghosts = List.of();
    private static BoundingBox box;
    /** whether it fits where it is: "" yes, else why not (null: not known yet) */
    private static String problem;
    /** ticks till we ask the server whether it fits, since it last moved (-1: asked) */
    private static int ask = -1;
    /** the area the plans were opened for (null: none) */
    private static dev.eiriksb.stad.item.PlanArea area;

    /** The area the next preview is for (from the plans screen). */
    static void area(dev.eiriksb.stad.item.PlanArea a) {
        area = a != null && a.complete() ? a : null;
    }

    /** Whether it's drawn up to fill the area (so it stays where it is). */
    private static boolean fitted() {
        return building != null && building.fitted();
    }

    public static boolean active() {
        return building != null;
    }

    /** From the server: the building to place (the screen's Preview), or whether it fits where it is now. */
    static void receive(PlacePreviewPayload p) {
        if (p.full()) {
            List<Blueprint.Entry> entries = new ArrayList<>();
            for (int i = 0; i + 1 < p.blocks().length; i += 2) {
                entries.add(new Blueprint.Entry(PlacePreviewPayload.unpack(p.blocks()[i]), Block.stateById(p.blocks()[i + 1])));
            }
            building = p;
            blueprint = new Blueprint(new Vec3i(p.sx(), p.sy(), p.sz()), entries, List.of());
            rotation = p.rotation();
            following = false;
            at = null;
            move(p.at());
            problem = p.problem();
            ask = -1;
            Minecraft.getInstance().setScreen(null);
        } else if (building != null && p.at().equals(at) && p.rotation() == rotation && p.key().equals(building.key())) {
            problem = p.problem();
        }
    }

    static void stop() {
        building = null;
        area = null;
        blueprint = null;
        ghosts = List.of();
        box = null;
    }

    private static void move(BlockPos to) {
        to = inside(to);
        if (to.equals(at) && !ghosts.isEmpty()) {
            return;
        }
        at = to;
        rebuild();
    }

    private static Placement placement(BlockPos centre) {
        return fitted() ? Placement.of(blueprint, centre, Rotation.NONE) : Placement.of(blueprint, Placement.originFor(blueprint, centre, rotation), rotation);
    }

    /** Where it goes when it's moved to {@code to}: as near as it can be while inside the area (if there is one). */
    private static BlockPos inside(BlockPos to) {
        if (area == null || fitted()) {
            return to;
        }
        BoundingBox b = placement(to).bounds(), a = area.box();
        return to.offset(shift(b.minX(), b.maxX(), a.minX(), a.maxX()), 0, shift(b.minZ(), b.maxZ(), a.minZ(), a.maxZ()));
    }

    /** How far to move a span so it's inside another (or centred on it, when it's wider). */
    static int shift(int min, int max, int lo, int hi) {
        if (max - min > hi - lo) {
            return (lo + hi) / 2 - (min + max) / 2;
        }
        return min < lo ? lo - min : max > hi ? hi - max : 0;
    }

    private static void rebuild() {
        Placement placement = placement(at);
        List<GhostPreview.Ghost> g = new ArrayList<>();
        for (Placement.Step s : placement.steps()) {
            if (!s.clearing() && s.state().getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                g.add(new GhostPreview.Ghost(s.pos(), s.state()));
            }
        }
        ghosts = List.copyOf(g);
        box = placement.bounds();
        problem = null;
        ask = 6;
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (building == null) {
            KEYS.forEach(k -> {
                while (k.consumeClick()) {
                    // not placing: nothing to do with them
                }
            });
            return;
        }
        if (mc.player == null || mc.level == null) {
            stop();
            return;
        }
        if (following && !fitted()) {
            HitResult hit = mc.player.pick(REACH, 1f, false);
            if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
                move(b.getBlockPos());
            }
        }
        Direction away = mc.player.getDirection();
        while (AWAY.consumeClick()) {
            nudge(away);
        }
        while (BACK.consumeClick()) {
            nudge(away.getOpposite());
        }
        while (LEFT.consumeClick()) {
            nudge(away.getCounterClockWise());
        }
        while (RIGHT.consumeClick()) {
            nudge(away.getClockWise());
        }
        while (UP.consumeClick()) {
            nudge(Direction.UP);
        }
        while (DOWN.consumeClick()) {
            nudge(Direction.DOWN);
        }
        while (TURN.consumeClick()) {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
            if (fitted()) {
                problem = null; // it's drawn up again, and comes back as a new preview
                PacketDistributor.sendToServer(new PlanActionPayload(building.colony(), building.key(), at, rotation, true, "", false, area));
            } else {
                at = inside(at);
                rebuild();
            }
        }
        while (PLAN.consumeClick()) {
            if (building != null) {
                send(false);
                stop();
            }
        }
        while (CANCEL.consumeClick()) {
            stop();
        }
        if (building != null && ask > 0 && --ask == 0 && !fitted()) {
            send(true);
            ask = -1;
        }
    }

    private static void nudge(Direction d) {
        if (fitted()) {
            return;
        }
        following = false;
        move(at.relative(d));
    }

    private static void send(boolean check) {
        // a check is a preview that only asks whether it fits; otherwise it's planned
        if (check && fitted()) {
            return; // it came with whether it fits
        }
        PacketDistributor.sendToServer(new PlanActionPayload(building.colony(), building.key(), at, rotation, check, building.palette(), check, area));
    }

    /** Right-click picks it up (it follows where you look) or puts it down, instead of using what's in hand. */
    @SubscribeEvent
    static void click(InputEvent.InteractionKeyMappingTriggered event) {
        if (building == null || !event.isUseItem()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        following = !following && !fitted();
    }

    /** The area being marked out, or planned in: the one on the plans in hand, else the one being placed in. */
    private static dev.eiriksb.stad.item.PlanArea shownArea(Minecraft mc) {
        if (mc.player != null) {
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                var a = dev.eiriksb.stad.item.BuildingPlansItem.area(mc.player.getItemInHand(hand));
                if (a != null && mc.player.getItemInHand(hand).getItem() instanceof dev.eiriksb.stad.item.BuildingPlansItem) {
                    return a;
                }
            }
        }
        return area;
    }

    @SubscribeEvent
    static void renderArea(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        dev.eiriksb.stad.item.PlanArea a = shownArea(mc);
        if (a == null) {
            return;
        }
        boolean drawing = false;
        if (!a.complete() && building == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            a = dev.eiriksb.stad.item.PlanArea.of(a.first(), hit.getBlockPos()); // where it'd be with the corner looked at
            drawing = true;
        }
        BoundingBox b = a.box();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack() != null ? event.getPoseStack() : new PoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        // the ground the corners are on, and a frame above it
        LevelRenderer.renderLineBox(pose, lines, b.minX() - cam.x, b.minY() - cam.y, b.minZ() - cam.z, b.maxX() + 1 - cam.x,
                b.maxY() + (a.complete() ? 3 : 1) - cam.y, b.maxZ() + 1 - cam.z, 0.35f, 0.8f, 1f, drawing ? 0.5f : 1f);
        for (BlockPos c : drawing ? List.of(a.first()) : a.complete() ? List.of(a.first(), a.second().get()) : List.of(a.first())) {
            LevelRenderer.renderLineBox(pose, lines, c.getX() - 0.02 - cam.x, c.getY() - 0.02 - cam.y, c.getZ() - 0.02 - cam.z,
                    c.getX() + 1.02 - cam.x, c.getY() + 1.02 - cam.y, c.getZ() + 1.02 - cam.z, 1f, 1f, 1f, 1f);
        }
        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || building == null || box == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean bad = problem != null && !problem.isEmpty();
        try {
            GhostPreview.draw(mc, event, ghosts, 1f, bad ? 0.55f : 1f, bad ? 0.55f : 1f);
            Vec3 cam = event.getCamera().getPosition();
            PoseStack pose = event.getPoseStack() != null ? event.getPoseStack() : new PoseStack();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box.minX() - cam.x, box.minY() - cam.y, box.minZ() - cam.z,
                    box.maxX() + 1 - cam.x, box.maxY() + 1 - cam.y, box.maxZ() + 1 - cam.z, bad ? 1f : 0.45f, bad ? 0.35f : 0.9f, bad ? 0.35f : 0.5f, 1f);
            buffers.endBatch(RenderType.lines());
        } catch (RuntimeException e) {
            Stad.LOGGER.warn("Couldn't draw the building being placed", e);
            stop();
        }
    }

    /** What's being placed, whether it fits, and the keys, at the top of the screen. */
    static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) {
            return;
        }
        if (building == null) {
            areaSize(g, mc);
            return;
        }
        Font font = mc.font;
        List<String> lines = new ArrayList<>();
        if (fitted()) {
            lines.add("Drawn up to fill the marked area.");
            lines.add(name(TURN) + ": turn the door  ·  " + name(PLAN) + ": plan it  ·  " + name(CANCEL) + ": put it away");
        } else {
            lines.add((following ? "Right-click: put it down here" : "Right-click: pick it up (it follows where you look)")
                    + (area != null ? ", inside the area" : ""));
            boolean arrows = KEYS.subList(5, 9).stream().allMatch(KeyMapping::isDefault);
            lines.add((arrows ? "Arrow keys" : name(AWAY) + ", " + name(BACK) + ", " + name(LEFT) + ", " + name(RIGHT)) + ": move  ·  "
                    + name(UP) + ", " + name(DOWN) + ": up, down");
            lines.add(name(TURN) + ": turn  ·  " + name(PLAN) + ": plan it here  ·  " + name(CANCEL) + ": put it away");
        }
        String fits = problem == null ? "..." : problem.isEmpty() ? "It fits here." : problem;
        int width = Math.min(260, g.guiWidth() - 20);
        List<net.minecraft.util.FormattedCharSequence> why = font.split(net.minecraft.network.chat.Component.literal(fits), width);
        List<net.minecraft.util.FormattedCharSequence> keys = new ArrayList<>();
        lines.forEach(l -> keys.addAll(font.split(net.minecraft.network.chat.Component.literal(l), width)));
        int x = (g.guiWidth() - width) / 2, y = 6;
        int h = 12 + why.size() * 10 + keys.size() * 10 + 6;
        g.fill(x - 5, y - 4, x + width + 5, y + h, 0xA0000000);
        g.drawString(font, building.name() + "  (door: " + rotation.rotate(Direction.NORTH).getName() + ")", x, y, 0xFFD27F);
        y += 12;
        for (var line : why) {
            g.drawString(font, line, x, y, problem == null ? 0xA0A0A0 : problem.isEmpty() ? 0x7FFF7F : 0xFF7F7F);
            y += 10;
        }
        y += 4;
        for (var line : keys) {
            g.drawString(font, line, x, y, 0xC8C8C8);
            y += 10;
        }
    }

    /** While the second corner's being chosen: how big the area would be, under the crosshair. */
    private static void areaSize(GuiGraphics g, Minecraft mc) {
        dev.eiriksb.stad.item.PlanArea a = shownArea(mc);
        if (a == null || a.complete() || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        String size = dev.eiriksb.stad.item.PlanArea.of(a.first(), hit.getBlockPos()).size();
        g.drawCenteredString(mc.font, size, g.guiWidth() / 2, g.guiHeight() / 2 + 10, 0x8FD0FF);
    }

    private static String name(KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }
}
