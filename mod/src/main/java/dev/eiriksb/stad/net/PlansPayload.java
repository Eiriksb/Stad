package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/** Server to client: open the building plans for a spot (the buildings that can be planned there). */
public record PlansPayload(int colony, BlockPos at, Rotation rotation, List<Option> options) implements CustomPacketPayload {
    public record Option(String key, String name, String description, int sizeX, int sizeY, int sizeZ, String materials) {}

    public static final Type<PlansPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "plans"));
    public static final StreamCodec<FriendlyByteBuf, PlansPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeBlockPos(p.at);
        buf.writeEnum(p.rotation);
        buf.writeCollection(p.options, (b, o) -> {
            b.writeUtf(o.key());
            b.writeUtf(o.name());
            b.writeUtf(o.description());
            b.writeVarInt(o.sizeX());
            b.writeVarInt(o.sizeY());
            b.writeVarInt(o.sizeZ());
            b.writeUtf(o.materials());
        });
    }, buf -> new PlansPayload(buf.readVarInt(), buf.readBlockPos(), buf.readEnum(Rotation.class),
            buf.readList(b -> new Option(b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf()))));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
