package dev.eiriksb.stad.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.eiriksb.stad.citizen.CitizenLooks;
import dev.eiriksb.stad.war.RaiderEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BowItem;

/** Draws raiders with the player model in their faction's gear, bow drawn when they're shooting. */
public final class RaiderRenderer extends HumanoidMobRenderer<RaiderEntity, PlayerModel<RaiderEntity>> {
    public RaiderRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
    }

    @Override
    public void render(RaiderEntity raider, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        boolean aiming = raider.isAggressive() && raider.getMainHandItem().getItem() instanceof BowItem;
        model.rightArmPose = aiming ? HumanoidModel.ArmPose.BOW_AND_ARROW : HumanoidModel.ArmPose.EMPTY;
        super.render(raider, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(RaiderEntity raider) {
        return CitizenLooks.texture(raider.skin(), false);
    }
}
