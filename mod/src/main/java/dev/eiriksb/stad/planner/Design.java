package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A building drawn up with a builder: the spec it came from and the blocks it turned into, either a pack building in
 * other materials ("pick and adapt") or a new one from the {@link Generator}. Planned designs are saved with the town,
 * blocks and all, so they don't change if the generator or the pack does.
 *
 * @param base  the pack building it's based on, or null for a new design
 * @param beds  how many beds it has
 * @param rooms the rooms that fitted, by kind (empty for pack buildings)
 * @param notes what didn't fit ("There wasn't room for the library.")
 */
public record Design(String name, ResourceLocation type, BuildSpec spec, Blueprint blueprint, ResourceLocation base, int beds,
                     Map<String, Integer> rooms, List<String> notes) {
    public Design {
        rooms = Map.copyOf(rooms);
        notes = List.copyOf(notes);
    }

    /** The pack building {@code spec.base()} names ("medieval/cottage"), if it's loaded. */
    public static Optional<BuildingDef> baseOf(BuildSpec spec) {
        if (spec.generated()) {
            return Optional.empty();
        }
        return BuildingPacks.all().stream().filter(d -> d.key().getPath().equals(spec.base()) || d.key().toString().equals(spec.base())).findFirst();
    }

    /** Draws up {@code spec}: the base building in new materials when it still fits the spec, else a new building. */
    public static Design make(BuildSpec spec) {
        Optional<BuildingDef> base = baseOf(spec);
        if (base.isPresent() && base.get().spec() == null) {
            Restyle.Info info = Restyle.info(base.get());
            if (info.matches(spec)) {
                return new Design(spec.name(), base.get().type(), spec, Restyle.apply(base.get(), spec), base.get().key(), info.beds(),
                        Map.of(), List.of());
            }
        }
        BuildSpec gen = base.isPresent() && base.get().spec() != null ? spec : spec.withBase("new");
        Generator.Result r = Generator.generate(gen);
        return new Design(spec.name(), spec.buildingType(), gen, r.blueprint(), null, r.beds(), r.rooms(), r.notes());
    }

    /** A pack building in other materials (rather than a generated one). */
    public boolean adapted() {
        return base != null;
    }

    /** "a new two-storey design, 9 by 11 blocks: stone brick walls with an oak frame, a spruce gable roof..." */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        var size = blueprint.size();
        List<String> materials = new ArrayList<>();
        materials.add(mat(spec.walls()) + " walls");
        if (!spec.frame().equals("none")) {
            materials.add(article(mat(spec.frame())) + " frame");
        }
        materials.add(article(mat(spec.roofMaterial()) + (adapted() ? "" : " " + spec.roof())) + " roof");
        materials.add(mat(spec.floor()) + " floors");
        if (adapted()) {
            String baseName = BuildingPacks.get(base).map(BuildingDef::name).orElse(base.getPath());
            sb.append("the town's ").append(baseName).append(" design (").append(count(beds, "bed")).append(") with ").append(join(materials));
        } else {
            sb.append("a new ").append(spec.floors() == 1 ? "one-storey" : spec.floors() == 2 ? "two-storey" : "three-storey").append(' ')
                    .append(typeWord()).append(", ").append(spec.width()).append(" by ").append(spec.depth()).append(" blocks: ")
                    .append(join(materials));
            List<String> parts = new ArrayList<>();
            rooms.forEach((room, n) -> parts.add(count(n, switch (room) {
                case "living" -> "living room";
                case "storage" -> "storage room";
                default -> room;
            })));
            if (beds > 0) {
                parts.add(count(beds, "bed"));
            }
            for (String f : spec.features()) {
                parts.add(switch (f) {
                    case "big_windows" -> "big windows";
                    default -> "a " + f;
                });
            }
            if (!parts.isEmpty()) {
                sb.append("; ").append(join(parts));
            }
        }
        sb.append(" (").append(size.getX()).append("x").append(size.getZ()).append(", ").append(size.getY()).append(" high)");
        return sb.toString();
    }

    private String typeWord() {
        return switch (spec.type()) {
            case "home" -> "house";
            case "builder_hut" -> "builder's hut";
            case "other" -> "building";
            default -> spec.type();
        };
    }

    private static String article(String word) {
        return ("aeiou".indexOf(word.charAt(0)) >= 0 ? "an " : "a ") + word;
    }

    private static String mat(String name) {
        return Materials.get(name).map(Materials.Material::label).orElse(name.replace('_', ' '));
    }

    private static String count(int n, String thing) {
        String[] words = {"no", "one", "two", "three", "four", "five", "six", "seven", "eight"};
        String num = n < words.length ? words[n] : String.valueOf(n);
        return num + " " + thing + (n == 1 ? "" : thing.endsWith("y") ? "" : "s");
    }

    static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** A short file-safe id from the name: "Stone Cottage" → "stone_cottage". */
    public String slug() {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "design" : s.length() > 32 ? s.substring(0, 32) : s;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        t.putString("Type", type.toString());
        t.putString("Spec", spec.toJson());
        if (base != null) {
            t.putString("Base", base.toString());
        }
        t.putInt("Beds", beds);
        t.put("Blueprint", blueprint.save());
        return t;
    }

    public static Design load(CompoundTag t) {
        return new Design(t.getString("Name"), ResourceLocation.parse(t.getString("Type")), BuildSpec.parse(t.getString("Spec")),
                Blueprint.load(t.getCompound("Blueprint"), BuiltInRegistries.BLOCK.asLookup()),
                t.contains("Base") ? ResourceLocation.parse(t.getString("Base")) : null, t.getInt("Beds"), new LinkedHashMap<>(), List.of());
    }
}
