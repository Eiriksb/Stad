package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/**
 * Server to client: a building from the building plans to place by hand (the whole of it, as it'll be built: in the
 * style chosen), or, with no blocks, only whether it fits where it's been moved to.
 *
 * @param blocks  the blueprint's blocks, each its position packed ({@link #pack}) and then its block state id
 * @param at      the block it's centred on (floor level with it), and its turn, that {@code problem} is for
 * @param problem why it can't go there, or ""
 * @param fitted  drawn up to fill the area marked out with the plans: it stays where it is, with {@code at} its lowest
 *                corner, and {@code rotation} only says which way its door faces (it's drawn up again when that changes)
 */
public record PlacePreviewPayload(int colony, String key, String palette, String name, int sx, int sy, int sz, int[] blocks, BlockPos at,
                                  Rotation rotation, String problem, boolean fitted) implements CustomPacketPayload {
    public static final Type<PlacePreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "place_preview"));
    public static final StreamCodec<FriendlyByteBuf, PlacePreviewPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeUtf(p.key);
        buf.writeUtf(p.palette);
        buf.writeUtf(p.name);
        buf.writeVarInt(p.sx);
        buf.writeVarInt(p.sy);
        buf.writeVarInt(p.sz);
        buf.writeVarIntArray(p.blocks);
        buf.writeBlockPos(p.at);
        buf.writeEnum(p.rotation);
        buf.writeUtf(p.problem);
        buf.writeBoolean(p.fitted);
    }, buf -> new PlacePreviewPayload(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarIntArray(), buf.readBlockPos(), buf.readEnum(Rotation.class), buf.readUtf(), buf.readBoolean()));

    /** A position in a blueprint, packed: 10 bits each of x, y and z. */
    public static int pack(BlockPos p) {
        return p.getX() | p.getY() << 10 | p.getZ() << 20;
    }

    public static BlockPos unpack(int v) {
        return new BlockPos(v & 1023, v >> 10 & 1023, v >> 20 & 1023);
    }

    /** Whether it carries the building itself, not just whether it fits. */
    public boolean full() {
        return blocks.length > 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
