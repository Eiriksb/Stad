package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

import java.util.List;

/**
 * Server to client: open the building plans for a spot (the buildings that can be planned there), or for the area
 * marked out with them ({@code area}, else null).
 */
public record PlansPayload(int colony, BlockPos at, Rotation rotation, List<Option> options, List<String> palettes,
                           dev.eiriksb.stad.item.PlanArea area) implements CustomPacketPayload {
    /**
     * A building to choose: {@code group} is what kind it is ("Homes", "Workplaces"...), {@code purpose} what it's for, in
     * a line; {@code locked} the research it needs first ("" for none). With an area, {@code fit} is how it goes in it:
     * {@link #FILLS} (it's drawn up to fill it), {@link #FITS} (whichever way it's turned), {@link #STRAIGHT} (only with its
     * door north or south), {@link #TURNED} (only east or west), or why it doesn't; "" without an area.
     */
    public record Option(String key, String name, String description, int sizeX, int sizeY, int sizeZ, String materials, String group,
                         String purpose, String locked, String fit) {
        /** Whether it goes in the area (or there's none), with its door turned by {@code r}. */
        public boolean goesIn(Rotation r) {
            boolean quarter = r == Rotation.CLOCKWISE_90 || r == Rotation.COUNTERCLOCKWISE_90;
            return fit.isEmpty() || fit.equals(FILLS) || fit.equals(FITS) || fit.equals(quarter ? TURNED : STRAIGHT);
        }

        public boolean goesIn() {
            return goesIn(Rotation.NONE) || goesIn(Rotation.CLOCKWISE_90);
        }
    }

    public static final String FILLS = "fills", FITS = "fits", STRAIGHT = "straight", TURNED = "turned";

    public static final Type<PlansPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "plans"));
    public static final StreamCodec<FriendlyByteBuf, PlansPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.colony);
        buf.writeBlockPos(p.at);
        buf.writeEnum(p.rotation);
        buf.writeCollection(p.options, (b, o) -> {
            b.writeUtf(o.key());
            b.writeUtf(o.name());
            b.writeUtf(o.description());
            b.writeVarInt(o.sizeX());
            b.writeVarInt(o.sizeY());
            b.writeVarInt(o.sizeZ());
            b.writeUtf(o.materials());
            b.writeUtf(o.group());
            b.writeUtf(o.purpose());
            b.writeUtf(o.locked());
            b.writeUtf(o.fit());
        });
        buf.writeCollection(p.palettes, FriendlyByteBuf::writeUtf);
        buf.writeOptional(java.util.Optional.ofNullable(p.area), dev.eiriksb.stad.item.PlanArea.STREAM_CODEC::encode);
    }, buf -> new PlansPayload(buf.readVarInt(), buf.readBlockPos(), buf.readEnum(Rotation.class),
            buf.readList(b -> new Option(b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(),
                    b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf())),
            buf.readList(FriendlyByteBuf::readUtf), buf.readOptional(dev.eiriksb.stad.item.PlanArea.STREAM_CODEC::decode).orElse(null)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
