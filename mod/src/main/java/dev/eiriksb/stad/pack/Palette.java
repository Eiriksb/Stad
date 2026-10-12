package dev.eiriksb.stad.pack;

import com.google.gson.JsonObject;
import dev.eiriksb.stad.planner.Materials;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A style (PLAN.md §6.3): a material for each restyled role, so the same house can be built in Nordic spruce or
 * desert sandstone. From {@code data/<ns>/stad/palettes/<id>.json}:
 * {@code {"name": "Nordic", "description": "...", "walls": "spruce", "roof": "dark_oak", "floor": "spruce", "frame": "dark_oak", "trim": "cobblestone"}}.
 * Roles it leaves out keep the building's own blocks.
 */
public record Palette(ResourceLocation id, String name, String description, Map<Roles.Role, Materials.Material> materials) {
    public Palette {
        materials = Map.copyOf(materials);
    }

    public static Palette fromJson(ResourceLocation id, JsonObject o) {
        Map<Roles.Role, Materials.Material> m = new EnumMap<>(Roles.Role.class);
        for (Roles.Role r : Roles.Role.values()) {
            if (r.restyled) {
                String key = o.has(r.label) ? r.label : r.id();
                if (o.has(key)) {
                    Materials.get(o.get(key).getAsString()).ifPresent(mat -> m.put(r, mat));
                }
            }
        }
        String name = o.has("name") ? o.get("name").getAsString() : id.getPath();
        return new Palette(id, name, o.has("description") ? o.get("description").getAsString() : "", m);
    }

    /** "spruce walls, a dark oak roof and cobblestone trim" */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Roles.Role r : List.of(Roles.Role.WALL, Roles.Role.ROOF, Roles.Role.FRAME, Roles.Role.FLOOR, Roles.Role.TRIM)) {
            Materials.Material m = materials.get(r);
            if (m != null) {
                parts.add(m.label() + " " + r.label);
            }
        }
        return dev.eiriksb.stad.planner.Design.join(parts);
    }

    public String material(Roles.Role role, String fallback) {
        Materials.Material m = materials.get(role);
        return m == null ? fallback : m.name();
    }
}
