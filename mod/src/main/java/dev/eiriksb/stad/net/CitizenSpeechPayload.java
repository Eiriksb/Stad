package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server to client: a citizen said something, and how they felt saying it ({@code emotion}, one of They Will Talk's),
 * for the players near enough to see their face: it shows how they feel, and their mouth says the line when there's
 * no voice to move it ({@link dev.eiriksb.stad.client.LipSync}).
 */
public record CitizenSpeechPayload(UUID citizen, String text, String emotion) implements CustomPacketPayload {
    public static final Type<CitizenSpeechPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "citizen_speech"));
    public static final StreamCodec<FriendlyByteBuf, CitizenSpeechPayload> CODEC = StreamCodec.of((buf, p) -> {
        UUIDUtil.STREAM_CODEC.encode(buf, p.citizen);
        buf.writeUtf(p.text, 4096);
        buf.writeUtf(p.emotion);
    }, buf -> new CitizenSpeechPayload(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(4096), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
