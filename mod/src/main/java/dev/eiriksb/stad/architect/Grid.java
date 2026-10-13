package dev.eiriksb.stad.architect;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A box of blocks copied out of the world: an architect's table's site with the land round it, as the 3D view shows
 * it. Both sides; sent to the client run-length encoded by block state id.
 */
public final class Grid {
    public final int sx, sy, sz;
    private final BlockState[] cells;

    public Grid(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new BlockState[sx * sy * sz];
        java.util.Arrays.fill(cells, Blocks.AIR.defaultBlockState());
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public BlockState get(int x, int y, int z) {
        return inside(x, y, z) ? cells[(y * sz + z) * sx + x] : Blocks.AIR.defaultBlockState();
    }

    public void set(int x, int y, int z, BlockState s) {
        if (inside(x, y, z)) {
            cells[(y * sz + z) * sx + x] = s;
        }
    }

    /** The blocks from {@code min} on, {@code sx} by {@code sy} by {@code sz}. Server thread. */
    public static Grid capture(BlockGetter level, BlockPos min, int sx, int sy, int sz) {
        Grid g = new Grid(sx, sy, sz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState s = level.getBlockState(p.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    // our markers and other game-master blocks aren't shown as blocks
                    g.cells[(y * sz + z) * sx + x] = s.is(Blocks.STRUCTURE_BLOCK) || s.is(Blocks.STRUCTURE_VOID) || s.is(Blocks.JIGSAW)
                            || s.is(Blocks.BARRIER) || s.is(Blocks.LIGHT) ? Blocks.AIR.defaultBlockState() : s;
                }
            }
        }
        return g;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(sx);
        buf.writeVarInt(sy);
        buf.writeVarInt(sz);
        int i = 0;
        while (i < cells.length) {
            int id = Block.getId(cells[i]);
            int run = 1;
            while (i + run < cells.length && Block.getId(cells[i + run]) == id) {
                run++;
            }
            buf.writeVarInt(id);
            buf.writeVarInt(run);
            i += run;
        }
    }

    public static Grid read(FriendlyByteBuf buf) {
        Grid g = new Grid(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int i = 0;
        while (i < g.cells.length) {
            BlockState s = Block.stateById(buf.readVarInt());
            int run = buf.readVarInt();
            for (int k = 0; k < run && i < g.cells.length; k++) {
                g.cells[i++] = s;
            }
        }
        return g;
    }
}
