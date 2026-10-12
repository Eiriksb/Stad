package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: open the town hall screen. */
public record ColonyInfoPayload(ColonyView view) implements CustomPacketPayload {
    public static final Type<ColonyInfoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "colony_info"));
    public static final StreamCodec<FriendlyByteBuf, ColonyInfoPayload> CODEC =
            StreamCodec.of((buf, p) -> p.view.write(buf), buf -> new ColonyInfoPayload(ColonyView.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
