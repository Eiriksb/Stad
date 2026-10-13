package dev.eiriksb.stad.client.architect;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.eiriksb.stad.Stad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a {@link DesignModel} in the table's 3D view. Each layer of blocks is meshed once into vertex buffers (one
 * per render type) and only meshed again when an edit touches it or the layers next to it. With the view cut off
 * above a layer, that layer is drawn from a separate mesh made as if nothing stood on it, so you see its top.
 * Blocks drawn by a block entity renderer (chests, beds, signs...) are drawn every frame. Render thread.
 */
final class DesignRenderer implements AutoCloseable {
    private static final float DIM = 0.55f;

    private final DesignModel model;
    private final DesignLevel level;
    private final List<RenderType> types = RenderType.chunkBufferLayers();
    private final Map<RenderType, ByteBufferBuilder> bytes = new HashMap<>();
    private final List<Map<RenderType, VertexBuffer>> layers = new ArrayList<>();
    private final List<List<BlockPos>> entities = new ArrayList<>();
    private final boolean[] dirty;
    private Map<RenderType, VertexBuffer> cap = new HashMap<>();
    private List<BlockPos> capEntities = List.of();
    private int capY = -1;
    private boolean capDirty = true;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Set<BlockPos> failedEntities = new HashSet<>();
    /** the highest layer shown */
    int cut;

    DesignRenderer(DesignModel model, String biome) {
        this.model = model;
        this.level = new DesignLevel(model, biome);
        for (RenderType t : types) {
            bytes.put(t, new ByteBufferBuilder(t.bufferSize()));
        }
        for (int y = 0; y < model.gh(); y++) {
            layers.add(new HashMap<>());
            entities.add(List.of());
        }
        dirty = new boolean[model.gh()];
        cut = model.gh() - 1;
        markAll();
    }

    boolean cutting() {
        return cut < model.gh() - 1;
    }

    void markAll() {
        java.util.Arrays.fill(dirty, true);
        capDirty = true;
        blockEntities.clear();
    }

    /** A block at grid layer {@code y} changed. */
    void mark(int y) {
        for (int k = y - 1; k <= y + 1; k++) {
            if (k >= 0 && k < dirty.length) {
                dirty[k] = true;
            }
        }
        if (Math.abs(capY - y) <= 1) {
            capDirty = true;
        }
    }

    /** Meshes whatever changed (call before drawing). */
    void update() {
        for (int y = 0; y < dirty.length; y++) {
            if (dirty[y]) {
                dirty[y] = false;
                level.cut = Integer.MAX_VALUE;
                entities.set(y, build(y, layers.get(y)));
            }
        }
        if (cutting() && (capDirty || capY != cut)) {
            capY = cut;
            capDirty = false;
            level.cut = cut;
            capEntities = build(cut, cap);
            level.cut = Integer.MAX_VALUE;
        }
    }

    private List<BlockPos> build(int y, Map<RenderType, VertexBuffer> into) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        Map<RenderType, BufferBuilder> building = new LinkedHashMap<>();
        List<BlockPos> withEntities = new ArrayList<>();
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ModelBlockRenderer.enableCaching();
        try {
            for (int z = 0; z < model.gd(); z++) {
                for (int x = 0; x < model.gw(); x++) {
                    pos.set(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (s.isAir()) {
                        continue;
                    }
                    boolean dim = !model.inSite(x, y, z);
                    BlockPos at = pos.immutable();
                    if (s.hasBlockEntity()) {
                        withEntities.add(at);
                    }
                    FluidState fluid = s.getFluidState();
                    if (!fluid.isEmpty()) {
                        RenderType t = ItemBlockRenderTypes.getRenderLayer(fluid);
                        VertexConsumer c = new Shifted(begin(building, t), x - (x & 15), y - (y & 15), z - (z & 15), dim ? DIM : 1f);
                        dispatcher.renderLiquid(at, level, c, s, fluid);
                    }
                    if (s.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BakedModel m = dispatcher.getBlockModel(s);
                    random.setSeed(s.getSeed(at));
                    for (RenderType t : m.getRenderTypes(s, random, ModelData.EMPTY)) {
                        VertexConsumer c = begin(building, t);
                        if (dim) {
                            c = new Shifted(c, 0, 0, 0, DIM);
                        }
                        pose.pushPose();
                        pose.translate(x, y, z);
                        dispatcher.renderBatched(s, at, level, pose, c, true, random, ModelData.EMPTY, t);
                        pose.popPose();
                    }
                }
            }
        } catch (RuntimeException e) {
            Stad.LOGGER.warn("Couldn't draw layer {} of the architect's table's view", y, e);
        } finally {
            ModelBlockRenderer.clearCache();
        }
        for (RenderType t : types) {
            BufferBuilder b = building.get(t);
            MeshData mesh = b == null ? null : b.build();
            VertexBuffer vb = into.get(t);
            if (mesh == null) {
                if (vb != null) {
                    vb.close();
                    into.remove(t);
                }
                continue;
            }
            if (vb == null) {
                vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                into.put(t, vb);
            }
            vb.bind();
            vb.upload(mesh);
            VertexBuffer.unbind();
        }
        return withEntities;
    }

    private BufferBuilder begin(Map<RenderType, BufferBuilder> building, RenderType t) {
        RenderType key = bytes.containsKey(t) ? t : RenderType.solid();
        return building.computeIfAbsent(key, k -> new BufferBuilder(bytes.get(k), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK));
    }

    /** Draws the meshes; {@code view} and {@code projection} are already set up for the viewport. */
    void draw(Matrix4f view, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        int top = cutting() ? cut - 1 : model.gh() - 1;
        for (RenderType t : types) {
            t.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS, view, projection, mc.getWindow());
                shader.apply();
                if (shader.CHUNK_OFFSET != null) {
                    shader.CHUNK_OFFSET.set(0f, 0f, 0f);
                    shader.CHUNK_OFFSET.upload();
                }
                for (int y = 0; y <= top; y++) {
                    VertexBuffer vb = layers.get(y).get(t);
                    if (vb != null) {
                        vb.bind();
                        vb.draw();
                    }
                }
                if (cutting()) {
                    VertexBuffer vb = cap.get(t);
                    if (vb != null) {
                        vb.bind();
                        vb.draw();
                    }
                }
                shader.clear();
            }
            VertexBuffer.unbind();
            t.clearRenderState();
        }
    }

    /** Draws chests, beds, signs and the like (the model view matrix must be the camera's). */
    void drawEntities(MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Lighting.setupLevel();
        PoseStack pose = new PoseStack();
        int top = cutting() ? cut - 1 : model.gh() - 1;
        List<BlockPos> all = new ArrayList<>();
        for (int y = 0; y <= top; y++) {
            all.addAll(entities.get(y));
        }
        if (cutting()) {
            all.addAll(capEntities);
        }
        for (BlockPos p : all) {
            if (failedEntities.contains(p)) {
                continue;
            }
            BlockState s = model.at(p.getX(), p.getY(), p.getZ());
            BlockEntity be = blockEntities.get(p);
            if (be == null || be.getBlockState() != s) {
                if (!(s.getBlock() instanceof EntityBlock eb)) {
                    continue;
                }
                be = eb.newBlockEntity(new BlockPos(p.getX(), mc.level.getMinBuildHeight() - 64, p.getZ()), s);
                if (be == null) {
                    failedEntities.add(p);
                    continue;
                }
                be.setLevel(mc.level);
                blockEntities.put(p, be);
            }
            BlockEntityRenderer<BlockEntity> r = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (r == null) {
                continue;
            }
            pose.pushPose();
            pose.translate(p.getX(), p.getY(), p.getZ());
            try {
                r.render(be, 0f, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException e) {
                failedEntities.add(p);
            }
            pose.popPose();
        }
        buffers.endBatch();
        Lighting.setupFor3DItems();
    }

    @Override
    public void close() {
        layers.forEach(m -> m.values().forEach(VertexBuffer::close));
        cap.values().forEach(VertexBuffer::close);
        bytes.values().forEach(ByteBufferBuilder::close);
    }

    /** Passes vertices on, moved (the liquid renderer draws relative to a 16-block section) and darkened. */
    private record Shifted(VertexConsumer to, float dx, float dy, float dz, float shade) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            to.addVertex(x + dx, y + dy, z + dz);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            to.setColor((int) (red * shade), (int) (green * shade), (int) (blue * shade), alpha);
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
}
