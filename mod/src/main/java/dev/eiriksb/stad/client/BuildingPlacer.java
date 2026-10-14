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
 * wherever it's moved to (red when it doesn't, and why).
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
        blueprint = null;
        ghosts = List.of();
        box = null;
    }

    private static void move(BlockPos to) {
        if (to.equals(at) && !ghosts.isEmpty()) {
            return;
        }
        at = to;
        rebuild();
    }

    private static void rebuild() {
        Placement placement = Placement.of(blueprint, Placement.originFor(blueprint, at, rotation), rotation);
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
        if (following) {
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
            rebuild();
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
        if (building != null && ask > 0 && --ask == 0) {
            send(true);
            ask = -1;
        }
    }

    private static void nudge(Direction d) {
        following = false;
        move(at.relative(d));
    }

    private static void send(boolean check) {
        // a check is a preview that only asks whether it fits; otherwise it's planned
        PacketDistributor.sendToServer(new PlanActionPayload(building.colony(), building.key(), at, rotation, check, building.palette(), check));
    }

    /** Right-click picks it up (it follows where you look) or puts it down, instead of using what's in hand. */
    @SubscribeEvent
    static void click(InputEvent.InteractionKeyMappingTriggered event) {
        if (building == null || !event.isUseItem()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        following = !following;
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
        if (building == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        Font font = mc.font;
        List<String> lines = new ArrayList<>();
        lines.add(following ? "Right-click: put it down here" : "Right-click: pick it up (it follows where you look)");
        boolean arrows = KEYS.subList(5, 9).stream().allMatch(KeyMapping::isDefault);
        lines.add((arrows ? "Arrow keys" : name(AWAY) + ", " + name(BACK) + ", " + name(LEFT) + ", " + name(RIGHT)) + ": move  ·  "
                + name(UP) + ", " + name(DOWN) + ": up, down");
        lines.add(name(TURN) + ": turn  ·  " + name(PLAN) + ": plan it here  ·  " + name(CANCEL) + ": put it away");
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

    private static String name(KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }
}
