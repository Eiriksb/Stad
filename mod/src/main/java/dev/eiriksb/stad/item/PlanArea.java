package dev.eiriksb.stad.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Optional;

/**
 * The area marked out with the building plans (sneak and right-click two corners): kept on the plans themselves, so
 * the player sees it wherever they are and it's still there after a restart. The second corner is empty while only
 * the first is marked.
 */
public record PlanArea(BlockPos first, Optional<BlockPos> second) {
    public static final Codec<PlanArea> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("first").forGetter(PlanArea::first),
            BlockPos.CODEC.optionalFieldOf("second").forGetter(PlanArea::second)).apply(i, PlanArea::new));
    public static final StreamCodec<ByteBuf, PlanArea> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlanArea::first, ByteBufCodecs.optional(BlockPos.STREAM_CODEC), PlanArea::second, PlanArea::new);

    public static PlanArea of(BlockPos a, BlockPos b) {
        return new PlanArea(a.immutable(), Optional.of(b.immutable()));
    }

    public boolean complete() {
        return second.isPresent();
    }

    /** The corners' box (both corners in it); only the first corner's block while the second isn't marked. */
    public BoundingBox box() {
        BlockPos b = second.orElse(first);
        return BoundingBox.fromCorners(first, b);
    }

    public int sizeX() {
        return box().getXSpan();
    }

    public int sizeZ() {
        return box().getZSpan();
    }

    /** Whether a column is inside it (at any height). */
    public boolean covers(int x, int z) {
        BoundingBox b = box();
        return x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ();
    }

    /** The column in its middle, at height {@code y}. */
    public BlockPos middle(int y) {
        BoundingBox b = box();
        return new BlockPos((b.minX() + b.maxX()) / 2, y, (b.minZ() + b.maxZ()) / 2);
    }

    public String size() {
        return sizeX() + " × " + sizeZ();
    }
}
