package dev.eiriksb.stad.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.building.BuildingDef;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A building as the builder and the player talk about it (PLAN.md §6.4): what the language model fills in from "a
 * small stone cottage with two bedrooms and a chimney", and what the {@link Generator} builds from. Every field has a
 * fixed set of values or a range, so the model's grammar keeps it valid; {@link #parse} clamps anything odd anyway.
 *
 * @param base a pack building to restyle (its key path, "medieval/cottage"), or "new" for a generated one
 */
public record BuildSpec(String name, String type, String base, int width, int depth, int floors, String roof, String walls,
                        String frame, String roofMaterial, String floor, int beds, List<String> rooms, List<String> features) {
    public static final List<String> TYPES = List.of("home", "warehouse", "builder_hut", "workshop", "other");
    public static final List<String> ROOFS = List.of("gable", "hip", "flat");
    public static final List<String> ROOMS = List.of("bedroom", "kitchen", "living", "storage", "workshop", "library");
    public static final List<String> FEATURES = List.of("chimney", "porch", "big_windows");
    public static final int MIN_SIZE = 5, MAX_SIZE = 17, MAX_FLOORS = 3, MAX_BEDS = 8;

    public BuildSpec {
        rooms = List.copyOf(rooms);
        features = List.copyOf(features);
    }

    public boolean has(String feature) {
        return features.contains(feature);
    }

    public boolean generated() {
        return base == null || base.equals("new");
    }

    public ResourceLocation buildingType() {
        return switch (type) {
            case "home" -> BuildingDef.HOME;
            case "warehouse" -> BuildingDef.WAREHOUSE;
            case "builder_hut" -> BuildingDef.BUILDER_HUT;
            case "workshop" -> ResourceLocation.fromNamespaceAndPath("stad", "workshop");
            default -> ResourceLocation.fromNamespaceAndPath("stad", "building");
        };
    }

    public BuildSpec withBase(String base) {
        return new BuildSpec(name, type, base, width, depth, floors, roof, walls, frame, roofMaterial, floor, beds, rooms, features);
    }

    /** The JSON schema the model's answer must match; {@code bases} are the pack buildings it may pick. */
    public static JsonObject schema(List<String> bases) {
        JsonObject props = new JsonObject();
        props.add("name", str(40));
        props.add("type", enumOf(TYPES));
        List<String> b = new ArrayList<>(bases);
        b.add("new");
        props.add("base", enumOf(b));
        props.add("width", integer(MIN_SIZE, MAX_SIZE));
        props.add("depth", integer(MIN_SIZE, MAX_SIZE));
        props.add("floors", integer(1, MAX_FLOORS));
        props.add("roof", enumOf(ROOFS));
        props.add("walls", enumOf(Materials.names()));
        List<String> frames = new ArrayList<>(Materials.woods());
        frames.add("none");
        props.add("frame", enumOf(frames));
        props.add("roof_material", enumOf(Materials.names()));
        props.add("floor", enumOf(Materials.names()));
        props.add("beds", integer(0, MAX_BEDS));
        props.add("rooms", arrayOf(enumOf(ROOMS), 6));
        props.add("features", arrayOf(enumOf(FEATURES), 3));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        props.keySet().forEach(required::add);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Reads the model's answer (or a saved spec), clamping sizes and dropping unknown values. */
    public static BuildSpec parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String type = pick(o, "type", TYPES, "home");
        String name = str(o, "name", "").strip();
        if (name.isEmpty()) {
            name = switch (type) {
                case "warehouse" -> "Warehouse";
                case "builder_hut" -> "Builder's Hut";
                case "workshop" -> "Workshop";
                case "home" -> "House";
                default -> "Building";
            };
        }
        if (name.matches("[A-Za-z0-9_]+") && name.contains("_")) { // "stone_cottage" -> "Stone Cottage"
            StringBuilder words = new StringBuilder();
            for (String w : name.split("_+")) {
                if (!w.isEmpty()) {
                    words.append(words.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
            }
            name = words.toString();
        }
        name = name.length() > 40 ? name.substring(0, 40) : name;
        return new BuildSpec(name, type, str(o, "base", "new"),
                clamp(num(o, "width", 7), MIN_SIZE, MAX_SIZE), clamp(num(o, "depth", 7), MIN_SIZE, MAX_SIZE),
                clamp(num(o, "floors", 1), 1, MAX_FLOORS), pick(o, "roof", ROOFS, "gable"),
                Materials.get(str(o, "walls", "")).map(Materials.Material::name).orElse("oak"),
                str(o, "frame", "none").equals("none") ? "none" : Materials.get(str(o, "frame", "")).filter(Materials.Material::wood)
                        .map(Materials.Material::name).orElse("none"),
                Materials.get(str(o, "roof_material", "")).map(Materials.Material::name).orElse("spruce"),
                Materials.get(str(o, "floor", "")).map(Materials.Material::name).orElse("oak"),
                clamp(num(o, "beds", 0), 0, MAX_BEDS), list(o, "rooms", ROOMS, 6), list(o, "features", FEATURES, 3));
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.addProperty("base", base);
        o.addProperty("width", width);
        o.addProperty("depth", depth);
        o.addProperty("floors", floors);
        o.addProperty("roof", roof);
        o.addProperty("walls", walls);
        o.addProperty("frame", frame);
        o.addProperty("roof_material", roofMaterial);
        o.addProperty("floor", floor);
        o.addProperty("beds", beds);
        JsonArray r = new JsonArray();
        rooms.forEach(r::add);
        o.add("rooms", r);
        JsonArray f = new JsonArray();
        features.forEach(f::add);
        o.add("features", f);
        return o.toString();
    }

    private static JsonObject str(int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("maxLength", max);
        return o;
    }

    private static JsonObject integer(int min, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("minimum", min);
        o.addProperty("maximum", max);
        return o;
    }

    static JsonObject enumOf(List<String> values) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        o.add("enum", a);
        return o;
    }

    private static JsonObject arrayOf(JsonObject items, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        o.add("items", items);
        o.addProperty("maxItems", max);
        return o;
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static int num(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? (int) Math.round(e.getAsDouble()) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String pick(JsonObject o, String key, List<String> allowed, String fallback) {
        String v = str(o, key, fallback).toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }

    private static List<String> list(JsonObject o, String key, List<String> allowed, int max) {
        List<String> out = new ArrayList<>();
        if (o.get(key) instanceof JsonArray a) {
            for (JsonElement e : a) {
                if (e.isJsonPrimitive() && allowed.contains(e.getAsString()) && out.size() < max
                        && (!key.equals("features") || !out.contains(e.getAsString()))) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
