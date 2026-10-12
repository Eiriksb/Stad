package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Pick and adapt" (PLAN.md §6.4): a pack building in other materials. Every block gets a rough role from its shape
 * and height (roof: stairs, slabs and whatever is above where they start; floor: the bottom layer; frame: logs;
 * walls: the most common full block in between), and each role can be given another material. Blocks the planner
 * doesn't know (glass, doors, beds, furniture) stay as they are.
 */
public final class Restyle {
    private Restyle() {}

    /** What a pack building is like, as far as the planner can tell from its blocks. */
    public record Info(BuildingDef def, int width, int depth, int floors, int beds, String roof, String walls, String roofMaterial,
                       String floor, String frame) {
        /** One line for the language model: "medieval/cottage: Cottage (home), 7x9, 1 floor, 2 beds, hip roof, oak walls..." */
        public String describe() {
            String type = def.type().getPath();
            return def.key().getPath() + ": " + def.name() + " (" + type + "), " + width + "x" + depth + ", " + floors
                    + (floors == 1 ? " floor, " : " floors, ") + beds + (beds == 1 ? " bed, " : " beds, ") + roof + " roof, "
                    + walls + " walls, " + roofMaterial + " roof, " + floor + " floor" + (frame.equals("none") ? "" : ", " + frame + " frame");
        }

        /** Whether {@code spec} asks for this building as it is, apart from materials. */
        public boolean matches(BuildSpec spec) {
            boolean size = (Math.abs(spec.width() - width) <= 1 && Math.abs(spec.depth() - depth) <= 1)
                    || (Math.abs(spec.width() - depth) <= 1 && Math.abs(spec.depth() - width) <= 1);
            return size && spec.floors() == floors && spec.beds() == beds && (roof.equals("unknown") || spec.roof().equals(roof))
                    && spec.features().isEmpty() && spec.rooms().size() <= 1;
        }

        /** A spec that describes this building as it is: what a revision starts from. */
        public BuildSpec spec() {
            String type = switch (def.type().getPath()) {
                case "home", "warehouse", "builder_hut", "workshop" -> def.type().getPath();
                default -> "other";
            };
            return new BuildSpec(def.name(), type, def.key().getPath(), width, depth, floors, roof.equals("unknown") ? "gable" : roof,
                    walls, frame, roofMaterial, floor, beds, List.of(), List.of());
        }
    }

    private record Roles(int floorY, int roofY, Materials.Material walls) {}

    private static Roles roles(Blueprint bp) {
        int floorY = Integer.MAX_VALUE, roofY = Integer.MAX_VALUE;
        for (Blueprint.Entry e : bp.entries()) {
            if (e.state().isAir()) {
                continue;
            }
            floorY = Math.min(floorY, e.pos().getY());
            var known = Materials.of(e.state().getBlock());
            int x = e.pos().getX(), z = e.pos().getZ();
            boolean edge = x == 0 || z == 0 || x == bp.size().getX() - 1 || z == bp.size().getZ() - 1; // eaves, not stairs and chairs inside
            if (edge && known.isPresent() && (known.get().getValue() == Materials.Shape.STAIRS || known.get().getValue() == Materials.Shape.SLAB)) {
                roofY = Math.min(roofY, e.pos().getY());
            }
        }
        if (floorY == Integer.MAX_VALUE) {
            floorY = 0;
        }
        Map<Materials.Material, Integer> counts = new HashMap<>();
        for (Blueprint.Entry e : bp.entries()) {
            int y = e.pos().getY();
            if (y > floorY && y < roofY) {
                Materials.of(e.state().getBlock()).filter(k -> k.getValue() == Materials.Shape.FULL)
                        .ifPresent(k -> counts.merge(k.getKey(), 1, Integer::sum));
            }
        }
        Materials.Material walls = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new Roles(floorY, roofY, walls);
    }

    public static Info info(BuildingDef def) {
        Blueprint bp = def.blueprint();
        Roles r = roles(bp);
        int beds = 0;
        Set<String> facings = new HashSet<>();
        Map<String, Integer> roofMats = new HashMap<>(), floorMats = new HashMap<>(), logs = new HashMap<>();
        for (Blueprint.Entry e : bp.entries()) {
            BlockState s = e.state();
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                beds++;
            }
            var known = Materials.of(s.getBlock());
            if (known.isEmpty()) {
                continue;
            }
            Materials.Shape shape = known.get().getValue();
            String name = known.get().getKey().name();
            if (shape == Materials.Shape.LOG) {
                logs.merge(name, 1, Integer::sum);
            } else if (e.pos().getY() >= r.roofY) {
                roofMats.merge(name, 1, Integer::sum);
                if (shape == Materials.Shape.STAIRS) {
                    facings.add(s.getValue(StairBlock.FACING).getName());
                }
            } else if (e.pos().getY() == r.floorY) {
                floorMats.merge(name, 1, Integer::sum);
            }
        }
        String roof = facings.isEmpty() ? (r.roofY == Integer.MAX_VALUE ? "flat" : "unknown") : facings.size() >= 4 ? "hip" : "gable";
        int height = r.roofY == Integer.MAX_VALUE ? bp.size().getY() : r.roofY - r.floorY;
        int floors = Math.max(1, (int) Math.round((height - 1) / (double) Generator.STOREY));
        return new Info(def, bp.size().getX(), bp.size().getZ(), floors, beds, roof, r.walls == null ? "unknown" : r.walls.name(),
                top(roofMats), top(floorMats), logs.isEmpty() ? "none" : top(logs));
    }

    private static String top(Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");
    }

    /** {@code def}'s blueprint in the spec's materials. */
    public static Blueprint apply(BuildingDef def, BuildSpec spec) {
        Blueprint bp = def.blueprint();
        Roles r = roles(bp);
        Materials.Material walls = Materials.get(spec.walls()).orElse(null);
        Materials.Material roof = Materials.get(spec.roofMaterial()).orElse(null);
        Materials.Material floor = Materials.get(spec.floor()).orElse(null);
        Materials.Material frame = spec.frame().equals("none") ? null : Materials.get(spec.frame()).orElse(null);
        List<Blueprint.Entry> out = new ArrayList<>(bp.entries().size());
        for (Blueprint.Entry e : bp.entries()) {
            out.add(new Blueprint.Entry(e.pos(), restyle(e, r, walls, roof, floor, frame)));
        }
        return new Blueprint(bp.size(), List.copyOf(out));
    }

    private static BlockState restyle(Blueprint.Entry e, Roles r, Materials.Material walls, Materials.Material roof,
                                      Materials.Material floor, Materials.Material frame) {
        BlockState s = e.state();
        var known = Materials.of(s.getBlock());
        if (known.isEmpty()) {
            return s;
        }
        Materials.Material was = known.get().getKey();
        Materials.Shape shape = known.get().getValue();
        int y = e.pos().getY();
        Materials.Material to;
        if (shape == Materials.Shape.LOG) {
            to = frame;
        } else if (y >= r.roofY) {
            to = roof;
        } else if (y == r.floorY && shape == Materials.Shape.FULL) {
            to = floor;
        } else if (was == r.walls) {
            to = walls;
        } else {
            to = null;
        }
        return to == null || to == was ? s : Materials.copyProperties(s, to.block(shape));
    }
}
