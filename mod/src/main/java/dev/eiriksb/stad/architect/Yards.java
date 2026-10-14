package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Land at the architect's table (PLAN.md §6.11: fields, pens, groves, quarries, docks, guard posts, walls, market
 * squares and roads): what the table says about one, and how its design becomes a planned building. The land in it
 * (farmland, water, the crops and saplings) becomes markers for the farmer and the lumberjack
 * ({@link dev.eiriksb.stad.building.Blueprint#land}); the rest (fences, paths, the dirt that levels it) is left for the
 * builders.
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
        String a = p.sx == 8 || p.sx == 11 || p.sx == 18 || p.sx >= 80 ? "an " : "a ";
        if (p.type.equals(BuildingDef.FIELD)) {
            return "Laid out " + a + size + " field: " + plots + " plots of " + (y.crop().equals("mixed") ? "mixed crops" : y.crop()) + " and "
                    + water + " blocks of water. The builders " + (y.fence().equals("none") && !y.path() ? "level it" : "level it and put up the "
                    + (y.fence().equals("none") ? "path" : "fence")) + "; the farmers till and sow it.";
        }
        if (p.type.equals(BuildingDef.PEN)) {
            return "Fenced " + a + size + " pen for " + y.animal() + (y.extra() && p.sx >= 8 && p.sz >= 8 ? ", with a shelter, hay and water" : "")
                    + ". The shepherd brings " + y.animal() + " in through the gate.";
        }
        if (p.type.equals(BuildingDef.GROVE)) {
            return "Marked out " + saplings + " saplings, " + y.spacing() + " blocks apart. The lumberjack plants them (with saplings from the"
                    + " town hall) and fells the trees when they're grown.";
        }
        if (p.type.equals(BuildingDef.QUARRY)) {
            return "Marked out " + a + size + " quarry, " + y.depth() + " deep. The miner digs it down a layer at a time, with steps round its"
                    + " edge, and brings up stone, coal and ore.";
        }
        if (p.type.equals(BuildingDef.DOCK)) {
            long spots = p.markers.values().stream().filter("stad:fish"::equals).count();
            return "Laid out a pier with " + spots + (spots == 1 ? " spot" : " spots") + " to fish from. The fishers fish from it.";
        }
        if (p.type.equals(BuildingDef.POST)) {
            return "Drew up a watch post. The guards patrol to it and keep watch from the top.";
        }
        if (p.type.equals(BuildingDef.STALLS)) {
            long stalls = p.markers.values().stream().filter("stad:work"::equals).count();
            return "Laid out " + a + size + " market square with " + stalls + (stalls == 1 ? " stall" : " stalls") + " round a bell. A merchant"
                    + " keeps each stall; trade with them there.";
        }
        if (p.type.equals(BuildingDef.ROAD)) {
            long cells = d.values().stream().filter(Yard::paving).count();
            return cells == 0 ? "No road yet: draw it with the Road tool (click along the ground), or Route it to join up the doors in the site."
                    : "Paved " + cells + " blocks of road, " + y.width() + " wide. Citizens walk faster on it.";
        }
        long posts = p.markers.values().stream().filter("stad:post"::equals).count();
        return "Drew up " + a + size + " wall, " + y.height() + " high, with a door" + (posts > 0 ? " and " + posts + " watch spots for the guards" : "")
                + ". What's inside stays as it is.";
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
        String who = Yard.job(p.type);
        String work = switch (p.type.getPath()) {
            case "field" -> "till and sow it";
            case "pen" -> "bring the animals in";
            case "grove" -> "plant the saplings";
            case "quarry" -> "dig it";
            case "dock" -> "fish from it";
            case "post" -> "keep watch from it";
            case "stalls" -> "keep the stalls";
            default -> "watch from it";
        };
        if (p.type.equals(BuildingDef.ROAD)) {
            return "Planned the " + p.name + " (" + items + " items for the builders). Citizens walk faster on it once it's built.";
        }
        if (p.type.equals(BuildingDef.WALL) && !p.markers.containsValue("stad:post")) {
            return "Planned the " + p.name + " (" + items + " items for the builders).";
        }
        return "Planned the " + p.name + (items > 0 ? " (" + items + " items for the builders)" : "") + ". " + (jobs.contains(who)
                ? "The " + who + " will " + work + "." : "Make someone the " + who + " and they'll " + work + ".");
    }
}
