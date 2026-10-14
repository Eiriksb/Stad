package dev.eiriksb.stad.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blocks of a building, read from a vanilla structure template (what a structure block saves). Positions run
 * from 0 to size - 1; the front (the door) faces north (z = 0). Air means "clear this"; structure void is left out.
 *
 * @param markers spots that tell citizens what's where (PLAN.md §6.3): vanilla structure blocks in Data mode whose
 *                metadata is one of {@link #MARKERS}. They aren't built; the builder leaves air there.
 */
public record Blueprint(Vec3i size, List<Entry> entries, List<Marker> markers) {
    public record Entry(BlockPos pos, BlockState state) {}

    /** A marker: "stad:storage" at a chest, "stad:work" where the worker stands, "stad:shaft" the middle of a mine's shaft... */
    public record Marker(BlockPos pos, String name) {}

    /** what each marker means: storage counts as the town's, work is where the worker stands, sit and bed are for people */
    public static final Set<String> MARKERS = Set.of("stad:bed", "stad:work", "stad:storage", "stad:door", "stad:sit", "stad:shaft");

    /**
     * The land markers of fields, pens and groves (PLAN.md §6.11): a plot to till ({@code stad:plot}, or
     * {@code stad:plot_carrots} for one crop), a water hole ({@code stad:water}), a sapling to plant
     * ({@code stad:sapling}, {@code stad:sapling_birch}...), and the middle of a pen ({@code stad:pen}, {@code stad:pen_sheep}...).
     * The workers make these, not the builders, so they don't clear the spot either.
     */
    public static boolean land(String name) {
        return name.startsWith("stad:plot") || name.equals("stad:water") || name.startsWith("stad:sapling") || name.startsWith("stad:pen");
    }

    /** Whether {@code name} is one of our markers. */
    public static boolean isMarker(String name) {
        return MARKERS.contains(name) || land(name) && name.matches("stad:[a-z_]+");
    }

    public Blueprint {
        entries = List.copyOf(entries);
        markers = List.copyOf(markers);
    }

    public Blueprint(Vec3i size, List<Entry> entries) {
        this(size, entries, List.of());
    }

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
        List<Marker> markers = new ArrayList<>();
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockPos pos = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            BlockState state = palette.get(b.getInt("state"));
            String marker = marker(state, b.getCompound("nbt"));
            if (marker != null) {
                markers.add(new Marker(pos, marker));
                if (!land(marker)) {
                    entries.add(new Entry(pos, Blocks.AIR.defaultBlockState()));
                }
            } else if (!state.is(Blocks.STRUCTURE_VOID)) {
                entries.add(new Entry(pos, state));
            }
        }
        return new Blueprint(size, entries, markers);
    }

    /** The marker a block is, if it's a Data-mode structure block with one of ours as its metadata. */
    public static String marker(BlockState state, CompoundTag nbt) {
        if (!state.is(Blocks.STRUCTURE_BLOCK) || state.getValue(StructureBlock.MODE) != StructureMode.DATA) {
            return null;
        }
        String meta = nbt.getString("metadata").strip();
        return isMarker(meta) ? meta : null;
    }

    /**
     * What's in the world inside {@code box} (corner at 0, 0, 0), air included; Data structure blocks with one of our
     * markers become markers. Server thread.
     */
    public static Blueprint capture(net.minecraft.world.level.Level level, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        List<Entry> entries = new ArrayList<>();
        List<Marker> markers = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState s = level.getBlockState(p);
            BlockPos rel = new BlockPos(p.getX() - box.minX(), p.getY() - box.minY(), p.getZ() - box.minZ());
            if (s.is(Blocks.STRUCTURE_BLOCK) && level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.StructureBlockEntity sb) {
                String m = marker(s, sb.saveWithoutMetadata(level.registryAccess()));
                if (m != null) {
                    markers.add(new Marker(rel, m));
                    if (!land(m)) {
                        entries.add(new Entry(rel, Blocks.AIR.defaultBlockState()));
                    }
                    continue;
                }
            }
            if (s.is(Blocks.STRUCTURE_VOID) || s.is(Blocks.JIGSAW)) {
                continue;
            }
            entries.add(new Entry(rel, s)); // water and lava stay in; placements leave them out
        }
        return new Blueprint(new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), entries, markers);
    }

    public long solidBlocks() {
        return entries.stream().filter(e -> !e.state().isAir()).count();
    }

    public List<Marker> markers(String name) {
        return markers.stream().filter(m -> m.name().equals(name)).toList();
    }

    /** The same building turned about its middle; {@code size} swaps x and z for quarter turns. */
    public Blueprint rotated(Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return this;
        }
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(new Entry(Placement.rotate(e.pos(), size, rotation), e.state().rotate(rotation)));
        }
        List<Marker> ms = new ArrayList<>();
        for (Marker m : markers) {
            ms.add(new Marker(Placement.rotate(m.pos(), size, rotation), m.name()));
        }
        return new Blueprint(Placement.rotatedSize(size, rotation), out, ms);
    }

    /** The same size and markers with other blocks. */
    public Blueprint withEntries(List<Entry> entries) {
        return new Blueprint(size, entries, markers);
    }

    /** Compact NBT for saving with a town: a palette and one index per position (-1: left alone). */
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putIntArray("Size", new int[]{size.getX(), size.getY(), size.getZ()});
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[size.getX() * size.getY() * size.getZ()];
        Arrays.fill(cells, -1);
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
        if (!markers.isEmpty()) {
            ListTag ms = new ListTag();
            for (Marker m : markers) {
                CompoundTag mt = new CompoundTag();
                mt.putInt("Cell", cell(m.pos()));
                mt.putString("Name", m.name());
                ms.add(mt);
            }
            t.put("Markers", ms);
        }
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
        List<Marker> markers = new ArrayList<>();
        for (Tag tag : t.getList("Markers", Tag.TAG_COMPOUND)) {
            CompoundTag mt = (CompoundTag) tag;
            int c = mt.getInt("Cell");
            markers.add(new Marker(new BlockPos(c % size.getX(), c / (size.getX() * size.getZ()), (c / size.getX()) % size.getZ()),
                    mt.getString("Name")));
        }
        return new Blueprint(size, entries, markers);
    }

    private int cell(BlockPos pos) {
        return (pos.getY() * size.getZ() + pos.getZ()) * size.getX() + pos.getX();
    }

    /** As a vanilla structure template (what a structure block saves), for data packs; markers become Data structure blocks. */
    public CompoundTag toStructure() {
        CompoundTag t = new CompoundTag();
        t.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size.getX()));
        sizeTag.add(IntTag.valueOf(size.getY()));
        sizeTag.add(IntTag.valueOf(size.getZ()));
        t.put("size", sizeTag);
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        ListTag blocksTag = new ListTag();
        Map<BlockPos, String> markerAt = new HashMap<>();
        markers.forEach(m -> markerAt.put(m.pos(), m.name()));
        BlockState data = Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.DATA);
        List<Entry> all = new ArrayList<>();
        for (Entry e : entries) {
            if (!markerAt.containsKey(e.pos())) {
                all.add(e);
            }
        }
        markerAt.forEach((pos, name) -> all.add(new Entry(pos, data)));
        for (Entry e : all) {
            int i = index.computeIfAbsent(e.state(), st -> {
                palette.add(st);
                return palette.size() - 1;
            });
            CompoundTag b = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(e.pos().getX()));
            pos.add(IntTag.valueOf(e.pos().getY()));
            pos.add(IntTag.valueOf(e.pos().getZ()));
            b.put("pos", pos);
            b.putInt("state", i);
            String marker = markerAt.get(e.pos());
            if (marker != null) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "minecraft:structure_block");
                nbt.putString("mode", "DATA");
                nbt.putString("metadata", marker);
                b.put("nbt", nbt);
            }
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
