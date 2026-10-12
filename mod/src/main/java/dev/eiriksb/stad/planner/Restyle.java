package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.pack.Palette;
import dev.eiriksb.stad.pack.Roles;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Pick and adapt" (PLAN.md §6.4): a pack building in other materials, by its blocks' {@link Roles} (walls, roof,
 * floor, frame) and a spec or a {@link Palette}. Glass, doors, beds and furniture stay as they are.
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

    public static Info info(BuildingDef def) {
        Blueprint bp = def.blueprint();
        Roles.Analysis a = def.roles();
        int beds = 0;
        Set<String> facings = new HashSet<>();
        List<Blueprint.Entry> entries = bp.entries();
        for (int i = 0; i < entries.size(); i++) {
            BlockState s = entries.get(i).state();
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                beds++;
            }
            if (a.roles()[i] == Roles.Role.ROOF && s.getBlock() instanceof StairBlock) {
                facings.add(s.getValue(StairBlock.FACING).getName());
            }
        }
        String roof = facings.isEmpty() ? (a.hasRoof() ? "unknown" : "flat") : facings.size() >= 4 ? "hip" : "gable";
        int floors = Math.max(1, (int) Math.round((a.height() - 1) / (double) Generator.STOREY));
        Map<Roles.Role, String> m = Roles.materials(a);
        String frame = m.getOrDefault(Roles.Role.FRAME, "none");
        return new Info(def, bp.size().getX(), bp.size().getZ(), floors, beds, roof, m.getOrDefault(Roles.Role.WALL, "unknown"),
                m.getOrDefault(Roles.Role.ROOF, "unknown"), m.getOrDefault(Roles.Role.FLOOR, "unknown"),
                Materials.get(frame).filter(Materials.Material::wood).isPresent() ? frame : "none");
    }

    /** {@code def}'s blueprint in the spec's materials. */
    public static Blueprint apply(BuildingDef def, BuildSpec spec) {
        Map<Roles.Role, Materials.Material> to = new EnumMap<>(Roles.Role.class);
        Materials.get(spec.walls()).ifPresent(m -> to.put(Roles.Role.WALL, m));
        Materials.get(spec.roofMaterial()).ifPresent(m -> to.put(Roles.Role.ROOF, m));
        Materials.get(spec.floor()).ifPresent(m -> to.put(Roles.Role.FLOOR, m));
        if (!spec.frame().equals("none")) {
            Materials.get(spec.frame()).ifPresent(m -> to.put(Roles.Role.FRAME, m));
        }
        return Roles.restyle(def.roles(), to);
    }

    /** {@code def}'s blueprint in a palette's style. */
    public static Blueprint apply(BuildingDef def, Palette palette) {
        return Roles.restyle(def.roles(), palette.materials());
    }

    /** A spec for {@code def} in a palette's materials (what a builder's draft of it in that style starts from). */
    public static BuildSpec spec(BuildingDef def, Palette palette) {
        BuildSpec s = info(def).spec();
        return new BuildSpec(s.name(), s.type(), s.base(), s.width(), s.depth(), s.floors(), s.roof(),
                palette.material(Roles.Role.WALL, s.walls()), palette.material(Roles.Role.FRAME, s.frame()),
                palette.material(Roles.Role.ROOF, s.roofMaterial()), palette.material(Roles.Role.FLOOR, s.floor()), s.beds(), s.rooms(), s.features());
    }
}
