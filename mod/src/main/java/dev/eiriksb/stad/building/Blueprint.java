package dev.eiriksb.stad.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks of a building, read from a vanilla structure template (what a structure block saves). Positions run
 * from 0 to size - 1; the front (the door) faces north (z = 0). Air means "clear this"; structure void is left out.
 */
public record Blueprint(Vec3i size, List<Entry> entries) {
    public record Entry(BlockPos pos, BlockState state) {}

    /** Reads a structure template's NBT (first palette). */
    public static Blueprint read(CompoundTag tag, HolderGetter<Block> blocks) {
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        Vec3i size = new Vec3i(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST) ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getList(0);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }
        List<Entry> entries = new ArrayList<>();
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockState state = palette.get(b.getInt("state"));
            if (!state.is(Blocks.STRUCTURE_VOID)) {
                entries.add(new Entry(new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)), state));
            }
        }
        return new Blueprint(size, List.copyOf(entries));
    }

    public long solidBlocks() {
        return entries.stream().filter(e -> !e.state().isAir()).count();
    }
}
