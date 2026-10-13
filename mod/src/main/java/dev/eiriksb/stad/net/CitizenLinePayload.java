package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Both ways: a line in a citizen's chat screen. Client to server it's something the player typed (to say to the
 * citizen); server to client it's something said (a reply, or the player's own words when spoken aloud).
 */
public record CitizenLinePayload(UUID citizen, boolean fromPlayer, String speaker, String text) implements CustomPacketPayload {
    public static final Type<CitizenLinePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "citizen_line"));
    public static final StreamCodec<FriendlyByteBuf, CitizenLinePayload> CODEC = StreamCodec.of((buf, p) -> {
        UUIDUtil.STREAM_CODEC.encode(buf, p.citizen);
        buf.writeBoolean(p.fromPlayer);
        buf.writeUtf(p.speaker);
        buf.writeUtf(p.text, 4096);
    }, buf -> new CitizenLinePayload(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readUtf(), buf.readUtf(4096)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
