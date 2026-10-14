package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fields, pens and groves at the architect's table (PLAN.md §6.11): what the table says about one, and how its design
 * becomes a planned building. The land in it (farmland, water, the crops and saplings) becomes markers for the
 * farmer and the lumberjack ({@link dev.eiriksb.stad.building.Blueprint#land}); the rest (fences, paths, the dirt that
 * levels it) is left for the builders.
 */
public final class Yards {
    private Yards() {}

    /** "Laid out a 20 by 14 field: 216 plots of wheat round 2 water channels." */
    public static String describe(Project p) {
        Map<Integer, BlockState> d = p.design();
        long plots = d.values().stream().filter(s -> s.is(Blocks.FARMLAND)).count();
        long water = d.values().stream().filter(s -> s.is(Blocks.WATER)).count();
        long saplings = d.values().stream().filter(s -> s.is(net.minecraft.tags.BlockTags.SAPLINGS)).count();
        Yard y = p.yard != null ? p.yard : Yard.DEFAULT;
        String size = p.sx + " by " + p.sz;
        if (p.type.equals(BuildingDef.FIELD)) {
            return "Laid out a " + size + " field: " + plots + " plots of " + (y.crop().equals("mixed") ? "mixed crops" : y.crop()) + " and "
                    + water + " blocks of water. The builders " + (y.fence().equals("none") && !y.path() ? "level it" : "level it and put up the "
                    + (y.fence().equals("none") ? "path" : "fence")) + "; the farmers till and sow it.";
        }
        if (p.type.equals(BuildingDef.PEN)) {
            return "Fenced a " + size + " pen for " + y.animal() + (y.extra() && p.sx >= 8 && p.sz >= 8 ? ", with a shelter, hay and water" : "")
                    + ". The shepherd brings " + y.animal() + " in through the gate.";
        }
        return "Marked out " + saplings + " saplings, " + y.spacing() + " blocks apart. The lumberjack plants them (with saplings from the"
                + " town hall) and fells the trees when they're grown.";
    }

    /**
     * The land part of a design, as markers by site key: farmland is a plot (with the crop on it, if any), water a
     * water hole, saplings a sapling to plant; a pen gets one marker in its middle saying what it's for.
     */
    static Map<Integer, String> land(Project p, Map<Integer, BlockState> design) {
        Map<Integer, String> out = new HashMap<>();
        if (!Yard.is(p.type)) {
            return out;
        }
        for (Map.Entry<Integer, BlockState> e : design.entrySet()) {
            int k = e.getKey();
            BlockState s = e.getValue();
            if (p.type.equals(BuildingDef.FIELD) && s.is(Blocks.FARMLAND)) {
                BlockState above = design.get(Project.key(Project.kx(k), Project.ky(k) + 1, Project.kz(k)));
                String crop = above == null ? null : Yard.cropOf(above);
                out.put(k, crop == null ? "stad:plot" : "stad:plot_" + crop);
            } else if (p.type.equals(BuildingDef.FIELD) && s.is(Blocks.WATER)) {
                out.put(k, "stad:water");
            } else if (p.type.equals(BuildingDef.GROVE) && Yard.treeOf(s) != null) {
                out.put(k, "stad:sapling_" + Yard.treeOf(s));
            }
        }
        if (p.type.equals(BuildingDef.PEN)) {
            int y = Math.max(0, p.floorY) + 1;
            out.put(Project.key(p.sx / 2, Math.min(p.sy - 1, y), p.sz / 2), "stad:pen_" + (p.yard != null ? p.yard.animal() : "sheep"));
        }
        return out;
    }

    /** Whether a block in a field or grove's design is land the workers make (a crop or sapling on its plot, farmland, water). */
    static boolean worked(Project p, BlockState s) {
        return worked(p.type, s);
    }

    public static boolean worked(net.minecraft.resources.ResourceLocation type, BlockState s) {
        if (type.equals(BuildingDef.FIELD)) {
            return s.is(Blocks.FARMLAND) || s.is(Blocks.WATER) || Yard.cropOf(s) != null;
        }
        return type.equals(BuildingDef.GROVE) && Yard.treeOf(s) != null;
    }

    /** What to say when one is planned. */
    static String planned(Project p, int items, Set<String> jobs) {
        String who = p.type.equals(BuildingDef.FIELD) ? "farmer" : p.type.equals(BuildingDef.PEN) ? "shepherd" : "lumberjack";
        String work = p.type.equals(BuildingDef.FIELD) ? "till and sow it" : p.type.equals(BuildingDef.PEN) ? "bring the animals in"
                : "plant the saplings";
        return "Planned the " + p.name + (items > 0 ? " (" + items + " items for the builders)" : "") + ". " + (jobs.contains(who)
                ? "The " + who + " will " + work + "." : "Make someone the " + who + " and they'll " + work + ".");
    }
}
