package dev.eiriksb.stad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws citizens with the player model (slim arms for women, wide for men) in a skin put together from their looks
 * ({@link CitizenSkins}), and their face over it ({@link CitizenFace}).
 */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, PlayerModel<CitizenEntity>> {
    private final PlayerModel<CitizenEntity> wide;
    private final PlayerModel<CitizenEntity> slim;

    public CitizenRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        wide = model;
        slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
        addLayer(new CitizenFace(this));
    }

    @Override
    public void render(CitizenEntity citizen, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        model = citizen.isFemale() ? slim : wide;
        super.render(citizen, yaw, partialTicks, pose, buffers, light);
    }

    /** how near you have to be to see what they're doing under their name */
    private static final double STATUS_RANGE = 12;

    /** Their name, and under it what they're doing (in red when something's stopping them) when you're near. */
    @Override
    protected void renderNameTag(CitizenEntity citizen, net.minecraft.network.chat.Component name, PoseStack pose, MultiBufferSource buffers,
                                 int light, float partialTick) {
        super.renderNameTag(citizen, name, pose, buffers, light, partialTick);
        String status = citizen.shownStatus();
        if (status.isEmpty() || entityRenderDispatcher.distanceToSqr(citizen) > STATUS_RANGE * STATUS_RANGE) {
            return;
        }
        pose.pushPose();
        pose.translate(0, -0.26, 0); // a line lower
        super.renderNameTag(citizen, net.minecraft.network.chat.Component.literal(status)
                .withStyle(citizen.shownProblem() ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GRAY), pose, buffers, light, partialTick);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity citizen) {
        return CitizenSkins.get(citizen.shownLooks(), citizen.isFemale(), citizen.shownJob(), citizen.isBaby()).skin();
    }
}
