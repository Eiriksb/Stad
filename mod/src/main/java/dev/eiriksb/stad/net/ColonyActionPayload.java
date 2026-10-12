package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: something done on the town hall screen. */
public record ColonyActionPayload(int colony, Action action, String text) implements CustomPacketPayload {
    public enum Action { RENAME, OPEN_STORAGE }

    public static final Type<ColonyActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "colony_action"));
    public static final StreamCodec<FriendlyByteBuf, ColonyActionPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeEnum(p.action);
        buf.writeUtf(p.text, 64);
    }, buf -> new ColonyActionPayload(buf.readVarInt(), buf.readEnum(Action.class), buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
