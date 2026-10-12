package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Client to server: save what was set in the pack editor (every row, with the role it has now). */
public record EditorSavePayload(String token, String name, String kind, List<EditorPayload.Row> rows) implements CustomPacketPayload {
    public static final Type<EditorSavePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "editor_save"));
    public static final StreamCodec<FriendlyByteBuf, EditorSavePayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeUtf(p.token);
        buf.writeUtf(p.name);
        buf.writeUtf(p.kind);
        buf.writeCollection(p.rows, EditorPayload::writeRow);
    }, buf -> new EditorSavePayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readList(EditorPayload::readRow)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
