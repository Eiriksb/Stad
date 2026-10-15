package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/**
 * Client to server: preview or plan a building from the building plans screen, in a palette's style ("" for its own).
 * A preview sends back the building to place by hand ({@link PlacePreviewPayload}); {@code check} only whether it fits
 * where it's been moved to. {@code area} is the area the plans were opened for (null: none); a building drawn up to fit
 * it ({@link dev.eiriksb.stad.architect.AreaFit}) goes there.
 */
public record PlanActionPayload(int colony, String key, BlockPos at, Rotation rotation, boolean preview, String palette, boolean check,
                                dev.eiriksb.stad.item.PlanArea area) implements CustomPacketPayload {
    public static final Type<PlanActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "plan_action"));
    public static final StreamCodec<FriendlyByteBuf, PlanActionPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeUtf(p.key);
        buf.writeBlockPos(p.at);
        buf.writeEnum(p.rotation);
        buf.writeBoolean(p.preview);
        buf.writeUtf(p.palette);
        buf.writeBoolean(p.check);
        buf.writeOptional(java.util.Optional.ofNullable(p.area), dev.eiriksb.stad.item.PlanArea.STREAM_CODEC::encode);
    }, buf -> new PlanActionPayload(buf.readVarInt(), buf.readUtf(), buf.readBlockPos(), buf.readEnum(Rotation.class), buf.readBoolean(),
            buf.readUtf(), buf.readBoolean(), buf.readOptional(dev.eiriksb.stad.item.PlanArea.STREAM_CODEC::decode).orElse(null)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
