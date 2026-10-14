package dev.eiriksb.stad.architect;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Generator;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws up a table's generated building so it fits the site (PLAN.md §6.8): as big as the site allows, turned so its
 * door faces the chosen side, centred, standing on the site's ground with a foundation down to it where the land
 * falls away.
 */
public final class Fit {
    private Fit() {}

    /** The rotation that turns a generated building's front (north) to face {@code door}. */
    public static Rotation rotation(Direction door) {
        return switch (door) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The spec's "type" for a kind of building: the generator knows homes, huts, warehouses... the rest are "other". */
    public static String specType(ResourceLocation type) {
        String t = type.getPath();
        return BuildSpec.TYPES.contains(t) ? t : "other";
    }

    /** What to call a new building of a kind: "House", "Kitchen", "Builder's Hut"... */
    public static String defaultName(ResourceLocation type) {
        return switch (type.getPath()) {
            case "home" -> "House";
            case "builder_hut" -> "Builder's Hut";
            case "building" -> "Building";
            case "post" -> "Guard post";
            default -> Character.toUpperCase(type.getPath().charAt(0)) + type.getPath().substring(1).replace('_', ' ');
        };
    }

    /** The spec, made right for the table's kind of building (its type, and rooms that suit it when none were asked for). */
    public static BuildSpec prepare(BuildSpec s, ResourceLocation type) {
        List<String> rooms = s.rooms();
        if (rooms.isEmpty()) {
            if (type.equals(BuildingDef.KITCHEN)) {
                rooms = List.of("kitchen", "storage");
            } else if (type.equals(BuildingDef.MINE)) {
                rooms = List.of("storage");
            } else if (type.equals(Requirements.WORKSHOP) || type.equals(BuildingDef.LUMBER_CAMP) || type.equals(BuildingDef.SMITHY)) {
                rooms = List.of("workshop");
            } else if (type.equals(BuildingDef.FARMHOUSE) || type.equals(BuildingDef.FISHERY) || type.equals(BuildingDef.BARN)
                    || type.equals(BuildingDef.MARKET)) {
                rooms = List.of("storage");
            } else if (type.equals(BuildingDef.SCHOOL)) {
                rooms = List.of("library");
            } else if (type.equals(BuildingDef.INFIRMARY)) {
                rooms = List.of("bedroom", "storage");
            }
        }
        return new BuildSpec(s.name(), specType(type), "new", s.width(), s.depth(), s.floors(), s.roof(), s.walls(), s.frame(),
                s.roofMaterial(), s.floor(), s.beds(), rooms, s.features());
    }

    private static BuildSpec sized(BuildSpec s, int w, int d) {
        return new BuildSpec(s.name(), s.type(), "new", w, d, s.floors(), s.roof(), s.walls(), s.frame(), s.roofMaterial(), s.floor(),
                s.beds(), s.rooms(), s.features());
    }

    /**
     * Generates {@code p.spec} into {@code p.generated}, fitted to the site; may raise the site's top to make room
     * (up to {@link Project#MAX_HEIGHT}). {@code terrain} is the site with {@link Project#MARGIN} blocks round it.
     * Returns why it can't, or null.
     */
    public static String generate(Project p, Grid terrain) {
        if (p.spec == null || !p.hasSite()) {
            return "There's nothing to generate.";
        }
        Rotation rot = rotation(p.door);
        boolean quarter = rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90;
        int ux = quarter ? p.sz : p.sx, uz = quarter ? p.sx : p.sz;
        BuildSpec spec = p.spec;
        int over = spec.roof().equals("flat") ? 0 : 1;
        int front = Math.max(1, Math.max(over, spec.has("porch") ? 2 : 0));
        int w = Math.min(BuildSpec.MAX_SIZE, ux - 2 * over), d = Math.min(BuildSpec.MAX_SIZE, uz - front - over);
        Blueprint bp = null;
        for (int tries = 0; tries < 16 && w >= BuildSpec.MIN_SIZE && d >= BuildSpec.MIN_SIZE; tries++) {
            Blueprint b = Generator.generate(sized(spec, w, d)).blueprint();
            Vec3i s = b.size();
            if (s.getX() <= ux && s.getZ() <= uz) {
                bp = b;
                break;
            }
            w -= Math.max(0, s.getX() - ux);
            d -= Math.max(0, s.getZ() - uz);
        }
        if (bp == null) {
            int need = BuildSpec.MIN_SIZE + 2 * over;
            return "The site is too small to generate a building on: it needs at least " + need + " by " + (BuildSpec.MIN_SIZE + front + over)
                    + " blocks with that roof" + (spec.has("porch") ? " and porch" : "") + ". Make the site bigger, or build it by hand.";
        }
        p.spec = sized(spec, w, d);
        Blueprint turned = bp.rotated(rot);
        Vec3i rs = turned.size();
        int ox = (p.sx - rs.getX()) / 2, oz = (p.sz - rs.getZ()) / 2;
        if (p.floorY < 0) {
            p.floorY = ground(terrain, p, ox, oz, rs.getX(), rs.getZ());
        }
        if (p.floorY + rs.getY() > p.sy) {
            if (p.floorY + rs.getY() > Project.MAX_HEIGHT) {
                return "There isn't room above the ground for it: it's " + rs.getY() + " blocks high. Lower the site's bottom corner, or "
                        + "take a floor off.";
            }
            p.sy = p.floorY + rs.getY();
        }
        p.generated.clear();
        BlockState foundation = Blocks.COBBLESTONE.defaultBlockState();
        for (Blueprint.Entry e : turned.entries()) {
            int x = ox + e.pos().getX(), y = p.floorY + e.pos().getY(), z = oz + e.pos().getZ();
            p.generated.put(Project.key(x, y, z), e.state());
            if (e.pos().getY() == 0 && !e.state().isAir()) {
                for (int fy = p.floorY - 1; fy >= 0 && open(terrain.get(x + Project.MARGIN, fy + Project.MARGIN, z + Project.MARGIN)); fy--) {
                    p.generated.put(Project.key(x, fy, z), foundation);
                }
            }
        }
        furnish(p, terrain);
        if (p.type.equals(BuildingDef.MINE) && !p.markers.containsValue("stad:shaft")) {
            p.markers.put(Project.key(ox + rs.getX() / 2, p.floorY + 1, oz + rs.getZ() / 2), "stad:shaft");
        }
        return null;
    }

    /**
     * Puts what the kind of building must have and the generator didn't give it (a composter in a farmhouse, a loom
     * and a hay bale in a barn) against an inside wall on the ground floor, away from the door.
     */
    static void furnish(Project p, Grid terrain) {
        for (Requirements.Need n : Requirements.needs(p.type)) {
            if (n.furnish() == null) {
                continue;
            }
            if (n.met().test(DesignCheck.contents(terrain, p.sx, p.sy, p.sz, p.design(), p.markers))) {
                continue;
            }
            Integer spot = spot(p);
            if (spot == null) {
                return;
            }
            BlockState s = n.furnish().defaultBlockState();
            if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                s = s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, p.door);
            }
            p.generated.put(spot, s);
        }
    }

    /** A free cell on the ground floor against a wall, not by a door, in the generated building. */
    private static Integer spot(Project p) {
        int y = p.floorY + 1;
        for (int z = 0; z < p.sz; z++) {
            for (int x = 0; x < p.sx; x++) {
                int k = Project.key(x, y, z);
                BlockState here = p.generated.get(k), below = p.generated.get(Project.key(x, y - 1, z));
                if (here == null || !here.isAir() || below == null || below.isAir()) {
                    continue;
                }
                boolean wall = false, door = false;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (x + dx < 0 || z + dz < 0 || x + dx >= p.sx || z + dz >= p.sz) {
                            continue;
                        }
                        BlockState n = p.generated.get(Project.key(x + dx, y, z + dz));
                        if (n == null) {
                            continue;
                        }
                        if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                            door = true;
                        }
                        if (Math.abs(dx) + Math.abs(dz) == 1 && n.isSolid()) {
                            wall = true;
                        }
                    }
                }
                BlockState above = p.generated.get(Project.key(x, y + 1, z));
                if (wall && !door && above != null && above.isAir()) {
                    return k;
                }
            }
        }
        return null;
    }

    /** Where the land is open (air, plants, water): a foundation goes down through it. */
    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty() || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    /** The site's ground under a footprint: the most common height of the top of the land (logs and leaves aren't land). */
    static int ground(Grid terrain, Project p, int ox, int oz, int w, int d) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int x = ox; x < ox + w; x++) {
            for (int z = oz; z < oz + d; z++) {
                for (int y = p.sy - 1; y >= -Project.MARGIN; y--) {
                    BlockState s = terrain.get(x + Project.MARGIN, y + Project.MARGIN, z + Project.MARGIN);
                    if (!s.getFluidState().isEmpty() || (!open(s) && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES))) {
                        counts.merge(y, 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        int best = 0, bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || e.getValue() == bestCount && e.getKey() > best) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return Math.max(0, Math.min(p.sy - 1, best));
    }
}
