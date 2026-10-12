package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server to client: open the pack editor for a building (just scanned, or one of a pack's).
 *
 * @param needs    for each of {@code types}, what it lacks to be one ("" when it has everything)
 * @param editable false for another pack's building: saving makes a copy in My pack
 */
public record EditorPayload(String token, String name, String kind, List<String> types, List<String> needs, boolean editable,
                            int sizeX, int sizeY, int sizeZ, List<Row> rows, String contents, String markers) implements CustomPacketPayload {
    /** The blocks of one kind with one guessed role, and the role they have now. */
    public record Row(String block, String guessed, String role, int count) {}

    public static final Type<EditorPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "editor"));

    static void writeRow(FriendlyByteBuf b, Row r) {
        b.writeUtf(r.block());
        b.writeUtf(r.guessed());
        b.writeUtf(r.role());
        b.writeVarInt(r.count());
    }

    static Row readRow(FriendlyByteBuf b) {
        return new Row(b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt());
    }

    public static final StreamCodec<FriendlyByteBuf, EditorPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeUtf(p.token);
        buf.writeUtf(p.name);
        buf.writeUtf(p.kind);
        buf.writeCollection(p.types, FriendlyByteBuf::writeUtf);
        buf.writeCollection(p.needs, FriendlyByteBuf::writeUtf);
        buf.writeBoolean(p.editable);
        buf.writeVarInt(p.sizeX);
        buf.writeVarInt(p.sizeY);
        buf.writeVarInt(p.sizeZ);
        buf.writeCollection(p.rows, EditorPayload::writeRow);
        buf.writeUtf(p.contents);
        buf.writeUtf(p.markers);
    }, buf -> new EditorPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf),
            buf.readList(FriendlyByteBuf::readUtf), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readList(EditorPayload::readRow), buf.readUtf(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
