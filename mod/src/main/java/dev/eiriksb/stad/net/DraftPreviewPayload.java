package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server to client: the player's draft building, shown as a ghost where it would go, with what it takes. An empty
 * title clears it.
 *
 * @param positions block positions ({@link net.minecraft.core.BlockPos#asLong}) of the ghost blocks
 * @param states    their block state ids ({@link net.minecraft.world.level.block.Block#getId})
 * @param problem   why it can't be built there, or ""
 */
public record DraftPreviewPayload(String title, long[] positions, int[] states, List<Material> materials, String problem)
        implements CustomPacketPayload {
    public record Material(String item, int needed, int have) {}

    public static final DraftPreviewPayload CLEAR = new DraftPreviewPayload("", new long[0], new int[0], List.of(), "");

    public static final Type<DraftPreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "draft_preview"));
    public static final StreamCodec<FriendlyByteBuf, DraftPreviewPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeUtf(p.title);
        buf.writeLongArray(p.positions);
        buf.writeVarIntArray(p.states);
        buf.writeCollection(p.materials, (b, m) -> {
            b.writeUtf(m.item());
            b.writeVarInt(m.needed());
            b.writeVarInt(m.have());
        });
        buf.writeUtf(p.problem);
    }, buf -> new DraftPreviewPayload(buf.readUtf(), buf.readLongArray(), buf.readVarIntArray(),
            buf.readList(b -> new Material(b.readUtf(), b.readVarInt(), b.readVarInt())), buf.readUtf()));

    public boolean active() {
        return !title.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
