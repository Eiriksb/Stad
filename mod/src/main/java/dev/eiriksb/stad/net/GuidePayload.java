package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the getting-started guide for the player's town (the town hall's steps), for the corner of the
 * screen: how many of them are done, the next one, and the one just done, if one was (for a toast). No town: total 0.
 */
public record GuidePayload(String town, int done, int total, String next, String justDone) implements CustomPacketPayload {
    public static final Type<GuidePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "guide"));
    public static final StreamCodec<FriendlyByteBuf, GuidePayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeUtf(p.town);
        b.writeVarInt(p.done);
        b.writeVarInt(p.total);
        b.writeUtf(p.next);
        b.writeUtf(p.justDone);
    }, b -> new GuidePayload(b.readUtf(), b.readVarInt(), b.readVarInt(), b.readUtf(), b.readUtf()));

    public static final GuidePayload NONE = new GuidePayload("", 0, 0, "", "");

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
