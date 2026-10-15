package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.item.PlanArea;
import dev.eiriksb.stad.planner.BuildSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * A building drawn up to fill the area marked out with the building plans, the way the architect's table generates one
 * for its site: a field, pen or grove laid out over all of it, a house or workshop as big as it'll hold. Planned like a
 * table's design, except that what's already standing in the area is in the way rather than redesigned. Server thread.
 */
public final class AreaFit {
    private AreaFit() {}

    /** An option's key for "a {@code type} to fit the area". */
    public static final String PREFIX = "fit:";

    /** The kinds that can be drawn up to fit (a road is drawn by hand at the table, so not that). */
    public static List<ResourceLocation> types() {
        return Tables.types().stream().filter(t -> !t.equals(BuildingDef.ROAD) && !t.getPath().equals("building")).toList();
    }

    public static String key(ResourceLocation type) {
        return PREFIX + type;
    }

    /** The kind a key is for, or null when it isn't a fit key. */
    public static ResourceLocation type(String key) {
        return key.startsWith(PREFIX) ? ResourceLocation.tryParse(key.substring(PREFIX.length())) : null;
    }

    public static String name(ResourceLocation type) {
        return Fit.defaultName(type);
    }

    /** The corners of a site for the area: from a little under the lower corner, so the ground's in it wherever they were clicked. */
    public static BlockPos[] site(PlanArea area) {
        BoundingBox b = area.box();
        return new BlockPos[]{new BlockPos(b.minX(), b.minY() - 2, b.minZ()), new BlockPos(b.maxX(), b.maxY() + 1, b.maxZ())};
    }

    /** Why an area won't do for planning in, or null. */
    public static String problem(ServerLevel level, Colony colony, PlanArea area) {
        if (!area.complete()) {
            return "Mark the other corner first (sneak and right-click it).";
        }
        BlockPos[] site = site(area);
        String p = Project.siteProblem(level, colony, site[0], site[1]);
        return p == null ? null : p.replace("site", "area");
    }

    /**
     * What came of drawing one up: the blocks to build and where (null when it can't be), why not ("" when it can), and
     * when it was planned, what the player is told.
     */
    public record Result(Blueprint blueprint, BlockPos origin, String problem, String planned) {
        public boolean ok() {
            return problem.isEmpty();
        }
    }

    /**
     * Draws a {@code type} up in the area with its door turned by {@code rotation} (as the plans turn a building), and
     * checks it could be planned; with {@code plan}, plans it (an order to the builders, rank-checked and logged).
     */
    public static Result fit(ServerPlayer player, ServerLevel level, Colony colony, PlanArea area, ResourceLocation type, Rotation rotation,
                             boolean plan) {
        String problem = problem(level, colony, area);
        if (problem != null) {
            return new Result(null, null, problem, "");
        }
        Project p = new Project();
        BlockPos[] site = site(area);
        p.setSite(site[0], site[1]);
        p.type = type;
        p.name = name(type);
        p.door = rotation.rotate(Direction.NORTH);
        Grid terrain = Tables.capture(level, p);
        if (Yard.is(type)) {
            p.yard = Yard.DEFAULT;
            problem = p.yard.generate(p, terrain);
        } else {
            String beds = type.equals(BuildingDef.HOME) || type.equals(BuildingDef.BARRACKS) ? "2" : "0";
            p.spec = Fit.prepare(BuildSpec.parse("{\"beds\": " + beds + "}"), type);
            int sy = p.sy;
            problem = Fit.generate(p, terrain);
            if (problem == null && p.sy != sy) {
                terrain = Tables.capture(level, p); // the site grew upwards to make room
            }
        }
        if (problem != null) {
            return new Result(null, null, problem.replace("site", "area"), "");
        }
        Tables.Said said = Tables.plan(player, level, terrain, p, colony, !plan, false);
        Tables.Drawn drawn = Tables.draw(p, level, false);
        return new Result(drawn == null ? null : drawn.blueprint(), drawn == null ? null : drawn.origin(),
                said.error() ? said.message().replace("site", "area") : "", said.error() ? "" : said.message());
    }
}
