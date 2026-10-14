package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: open the town hall screen, with what came of something done on it ({@code notice}, "" for nothing). */
public record ColonyInfoPayload(ColonyView view, String notice, boolean error) implements CustomPacketPayload {
    public static final Type<ColonyInfoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "colony_info"));
    public static final StreamCodec<FriendlyByteBuf, ColonyInfoPayload> CODEC = StreamCodec.of((buf, p) -> {
        p.view.write(buf);
        buf.writeUtf(p.notice, 2048);
        buf.writeBoolean(p.error);
    }, buf -> new ColonyInfoPayload(ColonyView.read(buf), buf.readUtf(2048), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
