package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client, dev only: clicks, keys and screenshots for whatever screen is open ("click 10 20; shot name"). */
public record DevUiPayload(String script) implements CustomPacketPayload {
    public static final Type<DevUiPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "dev_ui"));
    public static final StreamCodec<FriendlyByteBuf, DevUiPayload> CODEC = StreamCodec.of((b, p) -> b.writeUtf(p.script),
            b -> new DevUiPayload(b.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
