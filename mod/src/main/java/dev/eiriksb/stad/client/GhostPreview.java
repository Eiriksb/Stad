package dev.eiriksb.stad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.net.DraftPreviewPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's draft building, drawn as see-through blocks where it would go (only the blocks that aren't there
 * yet), with a list of what it takes in the corner of the screen.
 */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class GhostPreview {
    record Ghost(BlockPos pos, BlockState state) {}

    private static final float ALPHA = 0.45f;
    private static final double RANGE = 128;
    private static volatile List<Ghost> ghosts = List.of();
    private static volatile DraftPreviewPayload current = DraftPreviewPayload.CLEAR;
    private static boolean failed;

    private GhostPreview() {}

    static void set(DraftPreviewPayload p) {
        List<Ghost> g = new ArrayList<>(p.positions().length);
        for (int i = 0; i < p.positions().length; i++) {
            BlockState s = Block.stateById(p.states()[i]);
            if (!s.isAir() && s.getRenderShape() == RenderShape.MODEL) {
                g.add(new Ghost(BlockPos.of(p.positions()[i]), s));
            }
        }
        ghosts = List.copyOf(g);
        current = p;
        failed = false;
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ghosts = List.of();
        current = DraftPreviewPayload.CLEAR;
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ghosts.isEmpty() || failed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        try {
            draw(mc, event);
        } catch (RuntimeException e) {
            failed = true; // don't spam the log every frame; the next preview tries again
            Stad.LOGGER.warn("Couldn't draw the draft preview", e);
        }
    }

    private static void draw(Minecraft mc, RenderLevelStageEvent event) {
        draw(mc, event, ghosts, 1f, 1f, 1f);
    }

    /**
     * Draws blocks see-through where they'd go (not where the same block is already), tinted by {@code tr, tg, tb}.
     * Shared with {@link BuildingPlacer}.
     */
    static void draw(Minecraft mc, RenderLevelStageEvent event, List<Ghost> ghosts, float tr, float tg, float tb) {
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack() != null ? event.getPoseStack() : new PoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = Sheets.translucentCullBlockSheet();
        VertexConsumer see = new SeeThrough(buffers.getBuffer(type));
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();
        for (Ghost g : ghosts) {
            if (g.pos.distToCenterSqr(cam) > RANGE * RANGE || mc.level.getBlockState(g.pos).is(g.state.getBlock())) {
                continue;
            }
            BakedModel model = dispatcher.getBlockModel(g.state);
            int color = mc.getBlockColors().getColor(g.state, mc.level, g.pos, 0);
            float r = (color >> 16 & 255) / 255f, gr = (color >> 8 & 255) / 255f, b = (color & 255) / 255f;
            if (color == -1) {
                r = gr = b = 1f;
            }
            r *= tr;
            gr *= tg;
            b *= tb;
            pose.pushPose();
            pose.translate(g.pos.getX() - cam.x, g.pos.getY() - cam.y, g.pos.getZ() - cam.z);
            random.setSeed(42);
            for (RenderType rt : model.getRenderTypes(g.state, random, ModelData.EMPTY)) {
                dispatcher.getModelRenderer().renderModel(pose.last(), see, g.state, model, r, gr, b, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, ModelData.EMPTY, rt);
            }
            pose.popPose();
        }
        buffers.endBatch(type);
    }

    /** Passes vertices on with their alpha turned down. */
    private record SeeThrough(VertexConsumer to) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            to.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            to.setColor(red, green, blue, (int) (alpha * ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            to.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            to.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            to.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            to.setNormal(x, y, z);
            return this;
        }
    }

    /** The draft's name and what it takes (red: the town doesn't have enough), top right. */
    static void hud(GuiGraphics g, DeltaTracker delta) {
        DraftPreviewPayload p = current;
        Minecraft mc = Minecraft.getInstance();
        if (!p.active() || mc.options.hideGui || mc.screen != null) {
            return;
        }
        Font font = mc.font;
        int lines = Math.min(8, p.materials().size());
        int width = 150;
        int x = g.guiWidth() - width - 6, y = 6;
        int height = 24 + lines * 10 + (p.problem().isEmpty() ? 0 : 20) + (p.materials().size() > lines ? 10 : 0);
        g.fill(x - 4, y - 4, x + width + 2, y + height, 0x90000000);
        g.drawString(font, font.plainSubstrByWidth("Draft: " + p.title(), width), x, y, 0xFFD27F);
        y += 12;
        int total = p.materials().stream().mapToInt(DraftPreviewPayload.Material::needed).sum();
        g.drawString(font, total + " items  (\"build it\" to go ahead)", x, y, 0xA0A0A0);
        y += 12;
        for (int i = 0; i < lines; i++) {
            DraftPreviewPayload.Material m = p.materials().get(i);
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(m.item()));
            String name = font.plainSubstrByWidth(item.getDescription().getString(), width - 50);
            boolean enough = m.have() >= m.needed();
            g.drawString(font, name, x, y, 0xFFFFFF);
            String count = Math.min(m.have(), m.needed()) + "/" + m.needed();
            g.drawString(font, count, x + width - font.width(count), y, enough ? 0x7FFF7F : 0xFF7F7F);
            y += 10;
        }
        if (p.materials().size() > lines) {
            g.drawString(font, "and " + (p.materials().size() - lines) + " more", x, y, 0xA0A0A0);
            y += 10;
        }
        if (!p.problem().isEmpty()) {
            for (var line : font.split(net.minecraft.network.chat.Component.literal(p.problem()), width).subList(0, 1)) {
                g.drawString(font, line, x, y + 4, 0xFF5555);
            }
        }
    }
}
