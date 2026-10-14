package dev.eiriksb.stad.client.architect;

import dev.eiriksb.stad.architect.DesignCheck;
import dev.eiriksb.stad.architect.Grid;
import dev.eiriksb.stad.architect.Project;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.net.TablePayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The table's design as the screen holds it: the land (the site and {@link Project#MARGIN} blocks round it, in "grid"
 * coordinates from the margin's corner) and what the design puts on the site (by site key), with its markers.
 */
public final class DesignModel {
    public static final int M = Project.MARGIN;

    public final Grid terrain;
    public final BlockPos min;
    public final int sx, sy, sz;
    public final Map<Integer, BlockState> design = new HashMap<>();
    public final Map<Integer, String> markers = new HashMap<>();
    public String name = "", type = "stad:home", spec = "", yard = "", door = "north";
    public int floorY = -1;
    /** what's round the site (farmland, trees, water, a pen) */
    public java.util.Set<String> near = java.util.Set.of();

    public DesignModel(Grid terrain, BlockPos min, int sx, int sy, int sz) {
        this.terrain = terrain;
        this.min = min;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    public void load(TablePayloads.Design d) {
        name = d.name();
        type = d.type();
        spec = d.spec();
        yard = d.yard();
        door = d.door();
        floorY = d.floorY();
        design.clear();
        for (int i = 0; i < Math.min(d.keys().length, d.states().length); i++) {
            design.put(d.keys()[i], Block.stateById(d.states()[i]));
        }
        markers.clear();
        for (int i = 0; i < Math.min(d.markerKeys().length, d.markerNames().size()); i++) {
            markers.put(d.markerKeys()[i], d.markerNames().get(i));
        }
    }

    public int gw() {
        return terrain.sx;
    }

    public int gh() {
        return terrain.sy;
    }

    public int gd() {
        return terrain.sz;
    }

    public boolean inGrid(int gx, int gy, int gz) {
        return terrain.inside(gx, gy, gz);
    }

    public boolean inSite(int gx, int gy, int gz) {
        return gx >= M && gy >= M && gz >= M && gx < M + sx && gy < M + sy && gz < M + sz;
    }

    /** The site key of a grid position (only meaningful inside the site). */
    public static int key(int gx, int gy, int gz) {
        return Project.key(gx - M, gy - M, gz - M);
    }

    public static BlockPos grid(int key) {
        return new BlockPos(Project.kx(key) + M, Project.ky(key) + M, Project.kz(key) + M);
    }

    /** What's at a grid position: the design's block, else the land. */
    public BlockState at(int gx, int gy, int gz) {
        if (inSite(gx, gy, gz)) {
            BlockState d = design.get(key(gx, gy, gz));
            if (d != null) {
                return d;
            }
        }
        return terrain.get(gx, gy, gz);
    }

    public BlockState land(int key) {
        BlockPos g = grid(key);
        return terrain.get(g.getX(), g.getY(), g.getZ());
    }

    /** What the design would cost to build where it differs from the land: items and counts. */
    public Map<Item, Integer> materials() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<Integer, BlockState> e : design.entrySet()) {
            BlockState s = e.getValue();
            if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.equals(land(e.getKey()))) {
                continue;
            }
            ResourceLocation t = ResourceLocation.tryParse(type);
            if (t != null && dev.eiriksb.stad.architect.Yards.worked(t, s)) {
                continue; // the farmer or lumberjack makes that
            }
            Item item = Placement.itemFor(s);
            int n = Placement.cost(s, item);
            if (n > 0) {
                m.merge(item, n, Integer::sum);
            }
        }
        return m;
    }

    public DesignCheck.Result check() {
        ResourceLocation t = ResourceLocation.tryParse(type);
        return DesignCheck.check(t == null ? ResourceLocation.fromNamespaceAndPath("stad", "building") : t, terrain, sx, sy, sz, design, markers, near);
    }

    public static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}
