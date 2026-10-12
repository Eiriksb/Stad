package dev.eiriksb.stad.planner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.api.BeforeReply;
import dev.eiriksb.theywilltalk.api.LlmApi;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The builder as architect (PLAN.md §6.4). When a player talks buildings with the town's builder, two quick calls to
 * the language model work out what they want (a new design, a change, "build it", "put it over there"...) and, for
 * designs, fill in a {@link BuildSpec}. The game draws it up and shows the ghost, and the builder's reply is told what
 * was drawn, so they can describe it in their own words. Everything else in the conversation is left to They Will Talk.
 */
public final class Architect {
    private Architect() {}

    /** Lines that might be about a building; anything else skips the model entirely (unless a draft is open). */
    private static final Pattern BUILDING_TALK = Pattern.compile("\\b(build\\w*|design\\w*|draw\\w*|plan\\w*|sketch\\w*|construct\\w*|"
            + "house|home|hut|cottage|cabin|tower|barn|shed|hall|warehouse|storehouse|workshop|bakery|smithy|forge|tavern|inn|chapel|"
            + "farmhouse|mansion|castle|roof|storey|story|stories|floors?|bedrooms?|rooms?|chimney|porch|windows?|walls?|bigger|smaller|"
            + "taller|wider|longer|shorter)\\b", Pattern.CASE_INSENSITIVE);
    private static final long BUDGET_MS = 7_000;
    /** Words that say where: without one, a new design goes wherever there's room. */
    private static final Pattern PLACE_TALK = Pattern.compile("\\b(here|there|this spot|that spot|next to|near|beside|by the|behind|"
            + "in front|across from|opposite|over by|turn|around|face|facing|overlooking|view of|on the hill|up the hill)\\b", Pattern.CASE_INSENSITIVE);

    enum Intent { NONE, DESIGN, REVISE, APPROVE, CANCEL, MOVE, SAVE }

    record Ask(Intent intent, Drafts.Where where, String near) {}

    /** What the hook needs to know, read on the server thread. */
    private record Scene(CitizenEntity citizen, ServerPlayer player, Colony colony, boolean builder, String otherBuilder,
                         String draftJson, String draftText, Rank rank, Building asking) {}

    /** Called from the conversation hook (a background thread). */
    public static BeforeReply beforeReply(TurnInfo turn) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || turn.kind() != Stad.CITIZEN || turn.player() == null) {
            return BeforeReply.CONTINUE;
        }
        long start = System.currentTimeMillis();
        Scene scene;
        try {
            scene = server.submit(() -> scene(server, turn)).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return BeforeReply.CONTINUE;
        }
        if (scene == null) {
            return BeforeReply.CONTINUE;
        }
        boolean buildingTalk = BUILDING_TALK.matcher(turn.text()).find();
        if (!scene.builder) {
            if (!buildingTalk) {
                return BeforeReply.CONTINUE;
            }
            String job = scene.citizen.job().orElse("not working");
            return BeforeReply.context(scene.otherBuilder != null
                    ? "You can't design or build buildings: you're " + (job.equals("not working") ? "not the builder" : "the town's " + job)
                    + ". If " + turn.playerName() + " asks for a building, say so and send them to " + scene.otherBuilder
                    + ", the town's builder, who draws up buildings with people."
                    : "You can't design or build buildings, and nobody in town has the builder job yet. If " + turn.playerName()
                    + " asks for a building, say they'll need to make someone the builder first.");
        }
        if (scene.asking != null) {
            var offer = scene.asking.offer;
            Boolean yes = dev.eiriksb.stad.building.Substitutions.answer(turn.text(), offer);
            if (yes != null) {
                try {
                    return BeforeReply.context(server.submit(() -> swapAnswer(scene, yes)).get(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    return BeforeReply.CONTINUE;
                }
            }
        }
        if (!buildingTalk && scene.draftJson == null) {
            return BeforeReply.CONTINUE;
        }
        LlmApi llm = TheyWillTalkApi.get().llm();
        if (!llm.ready()) {
            return BeforeReply.CONTINUE;
        }
        CompletableFuture<List<String>> work = classify(scene, turn.text())
                .thenCompose(ask -> act(server, scene, turn.text(), ask));
        try {
            List<String> lines = work.get(Math.max(500, BUDGET_MS - (System.currentTimeMillis() - start)), TimeUnit.MILLISECONDS);
            return lines.isEmpty() ? BeforeReply.CONTINUE : BeforeReply.context(lines);
        } catch (TimeoutException e) {
            // still drawing: the builder says so now and describes it when it's done
            work.thenAccept(lines -> {
                if (!lines.isEmpty()) {
                    followUp(server, scene, "(" + String.join(" ", lines) + ")", 0);
                }
            });
            return BeforeReply.context("You're sketching what " + scene.player.getGameProfile().getName()
                    + " asked for right now; say you'll show them the drawing in a moment.");
        } catch (Exception e) {
            Stad.LOGGER.debug("Architect: {}", e.toString());
            return BeforeReply.CONTINUE;
        }
    }

    private static Scene scene(MinecraftServer server, TurnInfo turn) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.player());
        CitizenEntity citizen = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(turn.villager()) instanceof CitizenEntity c) {
                citizen = c;
            }
        }
        if (player == null || citizen == null) {
            return null;
        }
        Colony colony = citizen.colony().orElse(null);
        if (colony == null) {
            return null;
        }
        boolean builder = Jobs.BUILDER.equals(citizen.job().orElse(null));
        String other = colony.citizens().stream().filter(r -> Jobs.BUILDER.equals(r.job)).map(r -> r.name).findFirst().orElse(null);
        Drafts.Draft d = Drafts.get(player.getUUID()).filter(x -> x.colony == colony.id).orElse(null);
        CitizenEntity c = citizen;
        Building asking = !builder ? null : colony.buildings().stream()
                .filter(b -> b.offer != null && !b.done() && (b.builder == null || b.builder.equals(c.getUUID()))).findFirst().orElse(null);
        return new Scene(citizen, player, colony, builder, other, d == null ? null : d.design.spec().toJson(),
                d == null ? null : d.design.describe(), colony.rank(player.getUUID()), asking);
    }

    // ---- what do they want? -------------------------------------------------------------------------------------

    private static final String CLASSIFY_SYSTEM = """
            You sort what a player says to the builder of a Minecraft town. Answer with JSON only.""";

    static CompletableFuture<Ask> classify(Scene scene, String text) {
        List<String> intents = new ArrayList<>(List.of("none", "design"));
        StringBuilder sb = new StringBuilder();
        sb.append("The builder is ").append(scene.citizen.getName().getString()).append(".\n");
        if (scene.draftJson != null) {
            intents.addAll(List.of("revise", "approve", "cancel", "move", "save"));
            sb.append("They're drawing up a building with the player: ").append(scene.draftText).append(".\n");
        } else {
            sb.append("They have no drawing on the go with the player.\n");
        }
        List<String> names = scene.colony.buildings().stream().map(b -> b.name).distinct().toList();
        if (!names.isEmpty()) {
            sb.append("Buildings in town: ").append(String.join(", ", names)).append(", and the town hall.\n");
        }
        sb.append("\nThe player says: \"").append(text).append("\"\n\n");
        sb.append("intent:\n");
        sb.append("- design: they want a new building designed or built (a house, a hut, a workshop, a tower...)");
        sb.append(scene.draftJson != null ? ", something different from the drawing\n" : "\n");
        if (scene.draftJson != null) {
            sb.append("""
                    - revise: they want the drawing changed (size, floors, materials, roof, beds, rooms, a chimney...)
                    - approve: they like the drawing or tell the builder to go ahead and build it
                    - cancel: they don't want the drawing any more
                    - move: they want the drawing somewhere else, or turned around
                    - save: they want to keep the design for later
                    """);
        }
        sb.append("- none: anything else (questions, chat, asking how the building work is going)\n");
        sb.append("where (for design or move): here = where the player is or is looking; near = next to a building, or by a place such as "
                + "the lake, the river, the forest, the hill or the fields (name it in \"near\"); turn = turned around; same = not said\n");
        JsonObject props = new JsonObject();
        props.add("intent", BuildSpec.enumOf(intents));
        props.add("where", BuildSpec.enumOf(List.of("same", "here", "near", "turn")));
        JsonObject near = new JsonObject();
        near.addProperty("type", "string");
        near.addProperty("maxLength", 32);
        props.add("near", near);
        JsonObject schema = object(props);
        return TheyWillTalkApi.get().llm().json(CLASSIFY_SYSTEM, sb.toString(), schema, 40, LlmApi.Priority.CONVERSATION)
                .thenApply(json -> {
                    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                    Intent intent = Intent.valueOf(o.get("intent").getAsString().toUpperCase(Locale.ROOT));
                    Drafts.Where where = switch (o.get("where").getAsString()) {
                        case "here" -> Drafts.Where.HERE;
                        case "near" -> Drafts.Where.NEAR;
                        case "turn" -> Drafts.Where.TURN;
                        default -> Drafts.Where.SAME;
                    };
                    Stad.LOGGER.debug("Architect: '{}' -> {} {} {}", text, intent, where, o.get("near").getAsString());
                    return new Ask(intent, where, o.get("near").getAsString());
                });
    }

    private static JsonObject object(JsonObject props) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        props.keySet().forEach(required::add);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    // ---- drawing it up ------------------------------------------------------------------------------------------

    private static final String SPEC_SYSTEM = """
            You are the drafting table of a builder in a Minecraft town: you turn what a player asks for into a building spec.
            Answer with JSON only. Keep to what they asked for; pick sensible, good-looking defaults for the rest.""";

    /** Asks the model for a spec from what the player said (and the current draft, when revising). */
    public static CompletableFuture<BuildSpec> spec(String playerName, String text, String currentJson) {
        List<Restyle.Info> bases = BuildingPacks.all().stream().filter(d -> d.spec() == null)
                .sorted(Comparator.comparing(BuildingDef::pack).thenComparing(BuildingDef::name)).map(Restyle::info).toList();
        List<BuildingDef> saved = BuildingPacks.all().stream().filter(d -> d.spec() != null).toList();
        StringBuilder sb = new StringBuilder();
        if (!bases.isEmpty()) {
            sb.append("Designs the town already has (\"base\"):\n");
            bases.forEach(b -> sb.append("- ").append(b.describe()).append('\n'));
            sb.append("Pick a base only if it already matches what they ask for (size, floors, beds, roof) and copy its numbers; "
                    + "you may still change its materials. Otherwise base is \"new\".\n");
        }
        if (!saved.isEmpty()) {
            sb.append("Designs the player kept before (they can ask for one by name; copy it):\n");
            saved.stream().limit(6).forEach(d -> sb.append("- ").append(d.name()).append(": ").append(d.spec()).append('\n'));
        }
        if (!dev.eiriksb.stad.pack.Palettes.all().isEmpty()) {
            sb.append("Styles (when they ask for one, use its materials): ");
            List<String> styles = new ArrayList<>();
            dev.eiriksb.stad.pack.Palettes.all().forEach(p -> styles.add(p.name() + " = " + p.describe()));
            sb.append(String.join("; ", styles)).append(".\n");
        }
        sb.append("""
                name: what to call it, in plain words ("Stone Cottage", "Lakeside Workshop").
                Sizes are in blocks, walls included: small 7x7, medium 9x9 or 9x11, large 13x11; at most 17. Each floor is 4 blocks high.
                type: home (people live there), warehouse (storage), builder_hut, workshop, other.
                rooms: one entry per room (two bedrooms = ["bedroom", "bedroom"]). beds: how many people sleep there.
                frame: logs at the corners and between floors (a wood), or none.
                features: chimney, porch, big_windows, only when asked for or fitting.
                """);
        if (currentJson != null) {
            sb.append("\nThe current drawing:\n").append(currentJson).append("\nChange only what ").append(playerName)
                    .append(" asks for and keep everything else the same.\n");
        }
        sb.append('\n').append(playerName).append(" says: \"").append(text).append("\"\n");
        List<String> baseKeys = bases.stream().map(b -> b.def().key().getPath()).toList();
        return TheyWillTalkApi.get().llm().json(SPEC_SYSTEM, sb.toString(), BuildSpec.schema(baseKeys), 260, LlmApi.Priority.CONVERSATION)
                .thenApply(json -> {
                    Stad.LOGGER.debug("Architect spec: {}", json);
                    return BuildSpec.parse(json);
                });
    }

    // ---- doing it -----------------------------------------------------------------------------------------------

    private static CompletableFuture<List<String>> act(MinecraftServer server, Scene scene, String text, Ask ask) {
        String who = scene.player.getGameProfile().getName();
        return switch (ask.intent) {
            case NONE -> CompletableFuture.completedFuture(List.of());
            case DESIGN, REVISE -> {
                if (!scene.rank.atLeast(Rank.MEMBER)) {
                    yield CompletableFuture.completedFuture(List.of(who + " isn't one of " + scene.colony.name()
                            + "'s people; you only draw up buildings for the town."));
                }
                boolean revise = ask.intent == Intent.REVISE && scene.draftJson != null;
                Ask placed = byPlace(scene, text, PLACE_TALK.matcher(text).find() ? ask : new Ask(ask.intent, Drafts.Where.SAME, ""));
                yield spec(who, text, revise ? scene.draftJson : null)
                        .thenCompose(spec -> server.submit(() -> drew(scene, spec, placed, revise)));
            }
            case APPROVE -> server.submit(() -> approved(scene, Drafts.approve(scene.player)));
            case CANCEL -> server.submit(() -> {
                Drafts.cancel(scene.player);
                return List.of(who + " doesn't want the drawing any more; you've put it away. Say so briefly.");
            });
            case MOVE -> server.submit(() -> {
                Ask m = byPlace(scene, text, ask);
                Drafts.move(scene.player, m.where == Drafts.Where.SAME ? Drafts.Where.HERE : m.where, m.near);
                return placeLines(scene, "You've moved the drawing as " + who + " asked.");
            });
            case SAVE -> server.submit(() -> saved(scene));
        };
    }

    /** "facing the lake": when they name a place in the land (and not one of the town's buildings), it goes by that. */
    private static Ask byPlace(Scene scene, String text, Ask ask) {
        var kind = Landmarks.kind(text);
        if (kind.isEmpty() || ask.where == Drafts.Where.TURN
                || (ask.where == Drafts.Where.NEAR && Drafts.anchor(scene.colony, ask.near) != null)) {
            return ask;
        }
        String place = switch (kind.get()) {
            case WATER -> "lake";
            case TREES -> "forest";
            case HILL -> "hill";
            case FIELDS -> "fields";
        };
        return new Ask(ask.intent, Drafts.Where.NEAR, place);
    }

    private static List<String> drew(Scene scene, BuildSpec spec, Ask ask, boolean revise) {
        String who = scene.player.getGameProfile().getName();
        Drafts.Draft d = Drafts.draw(scene.player, scene.colony, scene.citizen.getUUID(), spec, ask.where, ask.near);
        Drafts.sendControls(scene.player);
        List<String> lines = new ArrayList<>();
        lines.add((revise ? "You've changed the drawing as " + who + " asked; now it's " : "You've just drawn up a building for " + who + ": ")
                + d.design.describe() + ".");
        lines.addAll(placeLines(scene, null));
        lines.addAll(d.design.notes());
        lines.add(Drafts.materialsSentence(d, scene.player.serverLevel(), scene.colony));
        lines.add("Describe it in a sentence or two, in your own words, then ask whether they'd like changes or want you to build it.");
        return lines;
    }

    private static List<String> placeLines(Scene scene, String first) {
        List<String> lines = new ArrayList<>();
        if (first != null) {
            lines.add(first);
        }
        Drafts.get(scene.player.getUUID()).ifPresent(d -> {
            lines.add("A ghost of it now stands " + Drafts.where(d, scene.player) + ", for them to see.");
            String problem = Drafts.problem(d, scene.colony, scene.player.serverLevel());
            if (problem != null) {
                lines.add("But it can't be built there: " + problem + " Suggest putting it somewhere else.");
            }
        });
        return lines;
    }

    static List<String> approved(Scene scene, Drafts.Approval a) {
        String who = scene.player.getGameProfile().getName();
        return switch (a.outcome()) {
            case ACCEPTED -> List.of(who + " approved the drawing: you'll build the " + a.building().name + " next. You're pleased; say so.",
                    missingLine(scene, a.building()));
            case GRUDGING -> List.of(who + " approved the drawing. You'll build the " + a.building().name + ", but grudgingly"
                    + (a.reason() == null ? "" : ": " + you(a.reason())) + ". Say so.", missingLine(scene, a.building()));
            case REFUSED -> List.of(who + " told you to build the drawing. You refuse: " + you(a.reason().replaceFirst("^[^:]*: ", ""))
                    + " Say so in your own words.");
            case NOT_ALLOWED -> List.of(who + " told you to build the drawing, but " + who + " isn't allowed to approve buildings: "
                    + a.reason() + " Say you'd need the word of " + scene.colony.ownerName() + " or an officer.");
            default -> List.of(who + " told you to build the drawing, but it can't go ahead: " + a.reason());
        };
    }

    private static String missingLine(Scene scene, Building b) {
        var storage = dev.eiriksb.stad.building.ColonyStorage.supply(scene.player.serverLevel(), scene.colony);
        var missing = b.placement().map(p -> dev.eiriksb.stad.building.Construction.missing(scene.player.serverLevel(), b, p, storage))
                .orElse(java.util.Map.of());
        return missing.isEmpty() ? "The town has everything it needs for it."
                : "Mention the town still needs " + dev.eiriksb.stad.building.Construction.describe(missing, 4) + " for it (in the town hall).";
    }

    private static String you(String reason) {
        return reason.replace("they're", "you're").replace("they ", "you ").replace("their", "your");
    }

    private static List<String> saved(Scene scene) {
        Drafts.Draft d = Drafts.get(scene.player.getUUID()).orElse(null);
        if (d == null) {
            return List.of();
        }
        try {
            MyPack.save(scene.player.server, d.design, scene.player.getGameProfile().getName());
            return List.of("You've saved the " + d.design.name() + " design in " + scene.player.getGameProfile().getName()
                    + "'s pack of designs; it now shows in the Building Plans, ready to build again anywhere. Say so briefly.");
        } catch (Exception e) {
            Stad.LOGGER.warn("Couldn't save design {}: {}", d.design.name(), e.toString());
            return List.of("You tried to save the design but couldn't. Say something went wrong with the paperwork.");
        }
    }

    private static List<String> swapAnswer(Scene scene, boolean yes) {
        Building b = scene.asking;
        var offer = b.offer;
        if (offer == null) {
            return List.of();
        }
        String who = scene.player.getGameProfile().getName();
        String problem = yes ? dev.eiriksb.stad.building.Substitutions.accept(scene.player, scene.colony, b)
                : dev.eiriksb.stad.building.Substitutions.decline(scene.player, scene.colony, b);
        if (problem != null) {
            return List.of(who + " answered your question about the " + b.name + ", but it isn't theirs to decide: " + problem
                    + " Say you'll wait to hear from " + scene.colony.ownerName() + " or an officer.");
        }
        return List.of(yes ? who + " said yes: you'll " + offer.describe() + " and get on with the " + b.name + ". Say so briefly."
                : who + " said no: you'll wait for more " + offer.from().label() + " for the " + b.name + ". Say so briefly, and that it"
                + " needs putting in the town hall.");
    }

    /** Has the builder say something once they're free (they may still be finishing their last reply). */
    static void followUp(MinecraftServer server, Scene scene, String instruction, int attempt) {
        if (attempt > 8) {
            return;
        }
        CompletableFuture.delayedExecutor(attempt == 0 ? 500 : 2000, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
            if (!scene.citizen.isAlive() || scene.player.hasDisconnected()) {
                return;
            }
            if (!TheyWillTalkApi.get().speak(scene.citizen, scene.player, instruction)) {
                followUp(server, scene, instruction, attempt + 1);
            }
        }));
    }

    /** After an approval by button or command: the builder says what they think of it. Server thread. */
    public static void reactToApproval(ServerPlayer player, Drafts.Approval a, java.util.UUID builder) {
        if (builder == null || a.outcome() == dev.eiriksb.stad.colony.Order.Outcome.INVALID) {
            return;
        }
        CitizenEntity c = player.serverLevel().getEntity(builder) instanceof CitizenEntity ce ? ce : null;
        Optional<Colony> colony = c == null ? Optional.empty() : c.colony();
        if (c == null || colony.isEmpty() || c.distanceTo(player) > 24) {
            return;
        }
        Scene scene = new Scene(c, player, colony.get(), true, null, null, null, colony.get().rank(player.getUUID()), null);
        TheyWillTalkApi.get().speak(c, player, "(" + String.join(" ", approved(scene, a)) + ")");
    }

    /** The builder of a player's town who's nearest them (for drafts made by command). */
    public static Optional<CitizenEntity> nearestBuilder(ServerPlayer player, Colony colony) {
        return player.serverLevel().getEntitiesOfClass(CitizenEntity.class, player.getBoundingBox().inflate(48),
                        c -> colony.hasCitizen(c.getUUID()) && Jobs.BUILDER.equals(c.job().orElse(null)))
                .stream().min(Comparator.comparingDouble(c -> c.distanceTo(player)));
    }

    static Optional<Colony> colonyOf(ServerPlayer player) {
        ColonyManager m = ColonyManager.get(player.server);
        return m.at(player.level().dimension(), player.blockPosition());
    }
}
