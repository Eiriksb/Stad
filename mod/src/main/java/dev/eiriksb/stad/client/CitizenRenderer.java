package dev.eiriksb.stad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.CitizenLooks;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/** Draws citizens with the player model: slim arms for women, wide for men. */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, PlayerModel<CitizenEntity>> {
    private final PlayerModel<CitizenEntity> wide;
    private final PlayerModel<CitizenEntity> slim;

    public CitizenRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        wide = model;
        slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
    }

    @Override
    public void render(CitizenEntity citizen, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        model = citizen.isFemale() ? slim : wide;
        super.render(citizen, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity citizen) {
        return CitizenLooks.texture(citizen.skin(), citizen.isFemale());
    }
}
