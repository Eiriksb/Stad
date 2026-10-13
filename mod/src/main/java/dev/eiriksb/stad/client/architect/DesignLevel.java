package dev.eiriksb.stad.client.architect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * The design as a world the block renderer can draw: what's at each grid position, lit full bright, tinted for the
 * site's biome. With a {@code cut}, everything above that layer is air (so the top of the cut gets its faces).
 */
final class DesignLevel implements BlockAndTintGetter {
    private final DesignModel model;
    private final Biome biome;
    int cut = Integer.MAX_VALUE;

    DesignLevel(DesignModel model, String biomeId) {
        this.model = model;
        Biome b = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            var reg = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
            ResourceLocation id = ResourceLocation.tryParse(biomeId);
            b = id == null ? null : reg.get(id);
            if (b == null) {
                b = reg.get(Biomes.PLAINS);
            }
        }
        this.biome = b;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (pos.getY() > cut) {
            return DesignModel.air();
        }
        return model.at(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return model.gh();
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            default -> 0.6f;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null; // never asked: brightness is answered below
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return biome == null ? 0x7FB238 : resolver.getColor(biome, pos.getX() + model.min.getX(), pos.getZ() + model.min.getZ());
    }
}
