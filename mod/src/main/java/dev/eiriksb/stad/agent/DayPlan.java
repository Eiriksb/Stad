package dev.eiriksb.stad.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A citizen's plan for the day, as the language model writes it: one to three intentions in their own words. A
 * "socialize" intention names someone to visit in the evening; a "rest" intention means taking the afternoon off.
 */
public record DayPlan(List<String> intentions, String visit, boolean rest, String mood) {
    public static final List<String> KINDS = List.of("work", "rest", "socialize", "errand");
    public static final List<String> MOODS = List.of("cheerful", "content", "tired", "worried", "grumpy", "excited", "lonely");

    /**
     * The plan of a citizen nobody's near (PLAN.md §4, "level of detail"): their job, their needs, now and then an
     * afternoon off. Written from code, in the same words a citizen would use, since it ends up in their prompts too.
     */
    public static DayPlan routine(dev.eiriksb.stad.colony.Colony colony, dev.eiriksb.stad.colony.CitizenRecord r, boolean hungry,
                                  int happiness, net.minecraft.util.RandomSource random) {
        List<String> intentions = new ArrayList<>();
        if (hungry) {
            intentions.add("Get something to eat");
        }
        String job = r.job == null ? "" : r.job;
        intentions.add(switch (job) {
            case dev.eiriksb.stad.colony.Jobs.BUILDER -> colony.buildings().stream().filter(b -> !b.done()).findFirst()
                    .map(b -> "Get on with the " + b.name.toLowerCase(java.util.Locale.ROOT)).orElse("Help out around town");
            case dev.eiriksb.stad.colony.Jobs.FARMER -> "Tend the fields";
            case dev.eiriksb.stad.colony.Jobs.LUMBERJACK -> "Fell trees and plant new ones";
            case dev.eiriksb.stad.colony.Jobs.COURIER -> "Carry supplies to where they're needed";
            case dev.eiriksb.stad.colony.Jobs.GUARD -> "Keep watch over the town";
            case "" -> "Look for something useful to do";
            default -> "Get on with the work";
        });
        // tired of it all, or just a lucky day
        boolean rest = !job.isEmpty() && (happiness < 30 ? random.nextInt(3) == 0 : random.nextInt(8) == 0);
        if (rest) {
            intentions.add("Take the afternoon off");
        }
        if (r.home == null && intentions.size() < 3) {
            intentions.add("Find a proper bed");
        }
        return new DayPlan(List.copyOf(intentions), null, rest, happiness < 30 ? "grumpy" : "content");
    }

    public static JsonObject schema() {
        JsonObject intention = new JsonObject();
        intention.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("text", stringType(90));
        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        kind.add("enum", array(KINDS));
        props.add("kind", kind);
        props.add("with", stringType(40));
        intention.add("properties", props);
        intention.add("required", array(List.of("text", "kind", "with")));
        JsonObject intentions = new JsonObject();
        intentions.addProperty("type", "array");
        intentions.add("items", intention);
        intentions.addProperty("minItems", 1);
        intentions.addProperty("maxItems", 3);
        JsonObject mood = new JsonObject();
        mood.addProperty("type", "string");
        mood.add("enum", array(MOODS));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject top = new JsonObject();
        top.add("intentions", intentions);
        top.add("mood", mood);
        schema.add("properties", top);
        schema.add("required", array(List.of("intentions", "mood")));
        return schema;
    }

    private static JsonObject stringType(int max) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("maxLength", max);
        return s;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    /**
     * Reads the model's answer. {@code townsfolk} are the names of the other citizens: a visit is kept only when it
     * names one of them (matched on first name, any case).
     */
    public static DayPlan parse(String json, List<String> townsfolk) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<String> texts = new ArrayList<>();
        String visit = null;
        boolean rest = false;
        for (JsonElement e : o.getAsJsonArray("intentions")) {
            JsonObject i = e.getAsJsonObject();
            String text = i.get("text").getAsString().strip();
            if (text.isEmpty()) {
                continue;
            }
            texts.add(text);
            String kind = i.get("kind").getAsString();
            if (kind.equals("rest")) {
                rest = true;
            } else if (kind.equals("socialize") && visit == null && i.has("with")) {
                visit = match(i.get("with").getAsString(), townsfolk);
            }
        }
        return new DayPlan(List.copyOf(texts), visit, rest, o.has("mood") ? o.get("mood").getAsString() : "content");
    }

    static String match(String with, List<String> townsfolk) {
        String w = with.strip().toLowerCase(java.util.Locale.ROOT);
        if (w.isEmpty()) {
            return null;
        }
        for (String name : townsfolk) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            String first = n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n;
            if (w.equals(n) || w.equals(first) || w.startsWith(first + " ")) {
                return name;
            }
        }
        return null;
    }
}
