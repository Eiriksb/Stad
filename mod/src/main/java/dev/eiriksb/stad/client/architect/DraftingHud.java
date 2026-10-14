package dev.eiriksb.stad.client.architect;

import dev.eiriksb.stad.client.ui.StadButton;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.net.TablePayloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

/**
 * While designing in the drafting world: a reminder at the top of the screen, a key to finish, and the site drawn as a
 * grid (where you can build).
 */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class DraftingHud {
    private DraftingHud() {}

    private static final ResourceLocation DRAFTING = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "drafting");

    public static final KeyMapping FINISH = new KeyMapping("key.stad.finish_design", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
            "key.categories.stad");

    /** where the site is in the drafting world, from the server */
    private static TablePayloads.Drafting site = TablePayloads.Drafting.NONE;

    public static void set(TablePayloads.Drafting p) {
        site = p;
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        site = TablePayloads.Drafting.NONE;
    }

    public static boolean drafting(Minecraft mc) {
        return mc.level != null && mc.level.dimension().location().equals(DRAFTING);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (FINISH.consumeClick()) {
            if (drafting(mc) && mc.screen == null) {
                mc.setScreen(new FinishScreen());
            }
        }
    }

    public static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!drafting(mc) || mc.options.hideGui || mc.screen != null) {
            return;
        }
        String text = "Designing" + (site.name().isEmpty() ? "" : " " + site.name()) + "  ·  " + FINISH.getTranslatedKeyMessage().getString() + ": finish";
        dev.eiriksb.stad.client.ui.Theme.header(g, mc.font, Component.literal(text), g.guiWidth() / 2, 3);
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || site.sx() <= 0 || !drafting(mc) || mc.options.hideGui) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack() != null ? event.getPoseStack() : new PoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        int x0 = site.min().getX(), y0 = site.min().getY(), z0 = site.min().getZ();
        int x1 = x0 + site.sx(), y1 = y0 + site.sy(), z1 = z0 + site.sz();
        PoseStack.Pose p = pose.last();
        // a faint grid on every face of the site, a block apart
        float a = 0.2f;
        for (int x = x0 + 1; x < x1; x++) {
            line(p, lines, x, y0, z0, x, y1, z0, a);
            line(p, lines, x, y0, z1, x, y1, z1, a);
            line(p, lines, x, y0, z0, x, y0, z1, a);
            line(p, lines, x, y1, z0, x, y1, z1, a);
        }
        for (int z = z0 + 1; z < z1; z++) {
            line(p, lines, x0, y0, z, x0, y1, z, a);
            line(p, lines, x1, y0, z, x1, y1, z, a);
            line(p, lines, x0, y0, z, x1, y0, z, a);
            line(p, lines, x0, y1, z, x1, y1, z, a);
        }
        for (int y = y0 + 1; y < y1; y++) {
            line(p, lines, x0, y, z0, x1, y, z0, a);
            line(p, lines, x0, y, z1, x1, y, z1, a);
            line(p, lines, x0, y, z0, x0, y, z1, a);
            line(p, lines, x1, y, z0, x1, y, z1, a);
        }
        // and its edges, as the table shows them
        LevelRenderer.renderLineBox(pose, lines, x0, y0, z0, x1, y1, z1, 1f, 0.85f, 0.2f, 1f);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void line(PoseStack.Pose p, VertexConsumer c, float x1, float y1, float z1, float x2, float y2, float z2, float alpha) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= len;
        dy /= len;
        dz /= len;
        c.addVertex(p, x1, y1, z1).setColor(1f, 1f, 1f, alpha).setNormal(p, dx, dy, dz);
        c.addVertex(p, x2, y2, z2).setColor(1f, 1f, 1f, alpha).setNormal(p, dx, dy, dz);
    }

    /** Keep what was built, throw it away, or carry on. */
    static final class FinishScreen extends Screen {
        FinishScreen() {
            super(Component.literal("Finish designing"));
        }

        @Override
        protected void init() {
            int cx = width / 2, y = height / 2 - 10;
            addRenderableWidget(StadButton.of(Component.literal("Keep it"), b -> command("stad build done"))
                    .bounds(cx - 118, y, 76, 20).build());
            addRenderableWidget(StadButton.of(Component.literal("Throw away"), b -> command("stad build cancel"))
                    .bounds(cx - 38, y, 76, 20).build());
            addRenderableWidget(StadButton.of(Component.literal("Keep building"), b -> onClose())
                    .bounds(cx + 42, y, 76, 20).build());
        }

        private void command(String c) {
            onClose();
            if (minecraft.player != null) {
                minecraft.player.connection.sendCommand(c);
            }
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            int cx = width / 2, y = height / 2 - 10;
            dev.eiriksb.stad.client.ui.Theme.panel(g, cx - 130, y - 40, 260, 72);
            dev.eiriksb.stad.client.ui.Theme.header(g, font, Component.literal("Done designing?"), cx, y - 34);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int cx = width / 2, y = height / 2 - 10;
            g.drawCenteredString(font, "Keep it makes what you built the table's design.", cx, y - 13, dev.eiriksb.stad.client.ui.Theme.TEXT);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
