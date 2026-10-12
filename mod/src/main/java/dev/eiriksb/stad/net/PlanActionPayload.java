package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/** Client to server: preview or plan a building from the building plans screen, in a palette's style ("" for its own). */
public record PlanActionPayload(int colony, String key, BlockPos at, Rotation rotation, boolean preview, String palette) implements CustomPacketPayload {
    public static final Type<PlanActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "plan_action"));
    public static final StreamCodec<FriendlyByteBuf, PlanActionPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeUtf(p.key);
        buf.writeBlockPos(p.at);
        buf.writeEnum(p.rotation);
        buf.writeBoolean(p.preview);
        buf.writeUtf(p.palette);
    }, buf -> new PlanActionPayload(buf.readVarInt(), buf.readUtf(), buf.readBlockPos(), buf.readEnum(Rotation.class), buf.readBoolean(),
            buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
