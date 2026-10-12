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

    /** Compact NBT for saving with a town: a palette and one index per position (-1: left alone). */
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putIntArray("Size", new int[]{size.getX(), size.getY(), size.getZ()});
        List<BlockState> palette = new ArrayList<>();
        java.util.Map<BlockState, Integer> index = new java.util.HashMap<>();
        int[] cells = new int[size.getX() * size.getY() * size.getZ()];
        java.util.Arrays.fill(cells, -1);
        for (Entry e : entries) {
            int i = index.computeIfAbsent(e.state(), st -> {
                palette.add(st);
                return palette.size() - 1;
            });
            cells[cell(e.pos())] = i;
        }
        ListTag p = new ListTag();
        palette.forEach(st -> p.add(NbtUtils.writeBlockState(st)));
        t.put("Palette", p);
        t.putIntArray("Cells", cells);
        return t;
    }

    public static Blueprint load(CompoundTag t, HolderGetter<Block> blocks) {
        int[] s = t.getIntArray("Size");
        Vec3i size = new Vec3i(s[0], s[1], s[2]);
        ListTag p = t.getList("Palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, p.getCompound(i)));
        }
        int[] cells = t.getIntArray("Cells");
        List<Entry> entries = new ArrayList<>();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int i = cells[(y * size.getZ() + z) * size.getX() + x];
                    if (i >= 0 && i < palette.size()) {
                        entries.add(new Entry(new BlockPos(x, y, z), palette.get(i)));
                    }
                }
            }
        }
        return new Blueprint(size, List.copyOf(entries));
    }

    private int cell(BlockPos pos) {
        return (pos.getY() * size.getZ() + pos.getZ()) * size.getX() + pos.getX();
    }

    /** As a vanilla structure template (what a structure block saves), for data packs. */
    public CompoundTag toStructure() {
        CompoundTag t = new CompoundTag();
        t.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        ListTag sizeTag = new ListTag();
        sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.getX()));
        sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.getY()));
        sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.getZ()));
        t.put("size", sizeTag);
        List<BlockState> palette = new ArrayList<>();
        java.util.Map<BlockState, Integer> index = new java.util.HashMap<>();
        ListTag blocksTag = new ListTag();
        for (Entry e : entries) {
            int i = index.computeIfAbsent(e.state(), st -> {
                palette.add(st);
                return palette.size() - 1;
            });
            CompoundTag b = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(net.minecraft.nbt.IntTag.valueOf(e.pos().getX()));
            pos.add(net.minecraft.nbt.IntTag.valueOf(e.pos().getY()));
            pos.add(net.minecraft.nbt.IntTag.valueOf(e.pos().getZ()));
            b.put("pos", pos);
            b.putInt("state", i);
            blocksTag.add(b);
        }
        ListTag p = new ListTag();
        palette.forEach(st -> p.add(NbtUtils.writeBlockState(st)));
        t.put("palette", p);
        t.put("blocks", blocksTag);
        t.put("entities", new ListTag());
        return t;
    }
}
