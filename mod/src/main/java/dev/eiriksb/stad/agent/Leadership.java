package dev.eiriksb.stad.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.colony.Request;
import dev.eiriksb.stad.colony.Standing;
import dev.eiriksb.stad.twt.StadConversations;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.ReflectionField;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Leading a town (PLAN.md §6.6): standing, citizens' willingness to follow orders, the morning review, strikes and
 * leaving. The numbers are decided here, in code; the language model only puts them into words.
 */
public final class Leadership {
    private static final Map<String, Long> hitCooldown = new HashMap<>();

    private Leadership() {}

    // ---- willingness ------------------------------------------------------------------------------------------

    /**
     * How willing a citizen is to do an order, and why.
     *
     * @param condition what would change their mind ("food", "bed"), or null
     */
    public record Willingness(int score, Order.Outcome outcome, String reason, String condition) {}

    private record Factor(double value, String reason, String condition) {}

    public static Willingness willingness(CitizenEntity c, ServerPlayer player, Colony colony, OrderKind kind, String arg) {
        String who = player.getGameProfile().getName();
        List<Factor> factors = new ArrayList<>();
        Standing standing = colony.standing(player.getUUID());
        String standingWhy = standing.worst(1).stream().map(Standing.Event::cause).findFirst()
                .map(cause -> "they remember: " + cause).orElse("they don't trust " + who + " as a leader");
        factors.add(new Factor(standing.score() * 0.6, standingWhy, null));
        int affinity = TheyWillTalkApi.get().memory().affinity(c.getUUID(), player.getUUID())
                .completeOnTimeout(0, 50, TimeUnit.MILLISECONDS).exceptionally(t -> 0).join();
        factors.add(new Factor(affinity * 0.4, "they don't like " + who, null));
        if (kind == OrderKind.INVITE) {
            int town = townHappiness(colony);
            factors.add(new Factor(colony.population() == 0 ? 0 : (town - 50) * 0.6, "they've heard the people of " + colony.name()
                    + " aren't happy", null));
        } else {
            factors.add(happiness(c));
        }
        factors.add(new Factor(persona(c), "it's not in their nature to take orders", null));
        if (kind == OrderKind.ASSIGN_JOB && arg != null) {
            factors.add(new Factor(-8, "they'd rather not take on new work", null));
        }
        if (kind == OrderKind.ASSIGN_HOME) {
            factors.add(new Factor(c.home().isEmpty() ? 25 : 10, "", null)); // a home is a gift, not a chore
        }
        double score = factors.stream().mapToDouble(Factor::value).sum();
        StadConfig.Leadership difficulty = StadConfig.LEADERSHIP.get();
        if (difficulty == StadConfig.Leadership.HARSH) {
            score -= 15;
        }
        Order.Outcome outcome = score >= 15 ? Order.Outcome.ACCEPTED : score >= -10 ? Order.Outcome.GRUDGING : Order.Outcome.REFUSED;
        if (difficulty == StadConfig.Leadership.LOYAL && outcome == Order.Outcome.REFUSED) {
            outcome = Order.Outcome.GRUDGING;
        }
        Factor worst = factors.stream().min((a, b) -> Double.compare(a.value, b.value)).orElseThrow();
        boolean explain = outcome != Order.Outcome.ACCEPTED && worst.value < 0;
        return new Willingness((int) Math.round(score), outcome, explain ? worst.reason : null, explain ? worst.condition : null);
    }

    private static Factor happiness(CitizenEntity c) {
        double value = (c.happiness() - 50) * 0.6;
        if (c.hunger() < 4) {
            return new Factor(value - 10, "they're starving", "food");
        }
        if (c.home().isEmpty()) {
            return new Factor(value - 5, "they still have no bed of their own", "bed");
        }
        if (c.rest() < 5) {
            return new Factor(value, "they're exhausted", null);
        }
        return new Factor(value, "they're unhappy in this town", null);
    }

    /** Some personalities take orders more readily than others. */
    static double persona(CitizenEntity c) {
        String p = TheyWillTalkApi.get().profile(c.getUUID()).map(VillagerProfile::persona).orElse("");
        return switch (p) {
            case "grumpy", "paranoid", "sarcastic" -> -15;
            case "boastful", "dramatic", "prankster", "greedy" -> -5;
            case "friendly", "peaceful", "cheerful", "sensitive" -> 10;
            case "shy", "laid_back", "wise" -> 5;
            default -> 0;
        };
    }

    public static int townHappiness(Colony colony) {
        return (int) Math.round(colony.citizens().stream().mapToInt(r -> r.happiness).average().orElse(60));
    }

    /** "be the farmer", "move into Lillestad" */
    public static String describe(OrderKind kind, String arg, Colony colony) {
        return switch (kind) {
            case INVITE -> "move into " + colony.name();
            case ASSIGN_JOB -> arg == null ? "stop working" : "be the town's " + arg;
            case DISMISS -> "leave town";
            case PLAN_BUILDING -> "build something";
            case ADOPT_BUILDING -> "take over a building for the town";
            case ASSIGN_HOME -> "move into " + homeName(arg, colony);
        };
    }

    private static String homeName(String id, Colony colony) {
        try {
            return colony.building(Integer.parseInt(id)).map(b -> "the " + b.name).orElse("a new home");
        } catch (NumberFormatException e) {
            return "a new home";
        }
    }

    /** What would change their mind, in words. */
    public static String conditionText(String condition) {
        return switch (condition) {
            case "food" -> "there's food in the town hall and they've eaten";
            case "bed" -> "they have a bed of their own";
            default -> condition;
        };
    }

    // ---- the morning review -----------------------------------------------------------------------------------

    /** Once a day, in the morning: how did the leaders do yesterday? Server thread. */
    public static void review(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        if (level.getDayTime() % 24000 > 4000 || colony.lastReviewDay == day) {
            return;
        }
        boolean first = colony.lastReviewDay < 0;
        colony.lastReviewDay = day;
        if (first) {
            return; // nothing to judge yet
        }
        long now = level.getGameTime();
        StadConfig.Leadership difficulty = StadConfig.LEADERSHIP.get();
        int happy = townHappiness(colony);
        for (CitizenRecord r : colony.citizens()) {
            if (r.wentHungry) {
                colony.changeLeadersStanding(-3, r.name + " went hungry", now);
                TheyWillTalkApi.get().memory().remember(NewMemory.of(r.uuid, Subject.player(colony.owner()),
                        "I went hungry yesterday: there was no food in the town hall.").withKind("event").withImportance(5));
                r.wentHungry = false;
            }
            if (r.home == null) {
                colony.changeLeadersStanding(-1, r.name + " has no bed", now);
            }
            r.unhappyDays = r.happiness < 25 ? r.unhappyDays + 1 : 0;
            if (r.unhappyDays >= 3 && difficulty != StadConfig.Leadership.LOYAL) {
                leave(level, colony, r);
            }
        }
        if (colony.population() > 0 && happy >= 70) {
            colony.changeLeadersStanding(3, "the town is happy", now);
        } else if (colony.population() > 0 && happy < 40) {
            colony.changeLeadersStanding(-3, "the town is unhappy", now);
        }
        List<Request> waited = colony.requests().stream().filter(q -> now - q.created > 24000).toList();
        if (!waited.isEmpty()) {
            colony.changeLeadersStanding(-Math.min(3, waited.size()), "the town waited a long time for " + waited.getFirst().label(), now);
        }
        Standing owner = colony.standing(colony.owner());
        if (difficulty != StadConfig.Leadership.LOYAL && colony.population() > 0 && happy < 35 && owner.score() < -30) {
            colony.strikeDay = day;
            String why = owner.worst(1).stream().map(Standing.Event::cause).findFirst().orElse("nobody listens to them");
            Announce.members(level.getServer(), colony, Component.literal("The workers of " + colony.name() + " are on strike today: "
                    + why + ".").withStyle(ChatFormatting.RED));
        }
        colony.changed();
    }

    private static void leave(ServerLevel level, Colony colony, CitizenRecord r) {
        if (level.getEntity(r.uuid) instanceof CitizenEntity c) {
            ColonyManager.get(level.getServer()).leave(colony, c);
        } else {
            colony.removeCitizen(r.uuid);
        }
        colony.changeLeadersStanding(-8, r.name + " left the town", level.getGameTime());
        Announce.members(level.getServer(), colony, Component.literal(r.name + " has had enough of " + colony.name() + " and moved away.")
                .withStyle(ChatFormatting.RED));
    }

    // ---- refused orders waiting on a condition -------------------------------------------------------------------

    /** A citizen who said "only once I've eaten" gets on with it when they have. Server thread, every few seconds. */
    public static void checkConditions(ServerLevel level, Colony colony) {
        for (CitizenRecord r : colony.citizens()) {
            if (r.condition == null) {
                continue;
            }
            boolean met = switch (r.condition) {
                case "food" -> level.getEntity(r.uuid) instanceof CitizenEntity c && c.hunger() >= 8;
                case "bed" -> r.home != null;
                default -> false;
            };
            if (!met) {
                continue;
            }
            String job = r.pendingJob;
            String condition = r.condition;
            r.condition = null;
            r.pendingJob = null;
            r.pendingFrom = null;
            r.job = job;
            colony.changed();
            Announce.members(level.getServer(), colony, Component.literal(r.name + " agrees to " + (job == null ? "stop working" : "be the town's " + job)
                    + " now that " + conditionText(condition) + ".").withStyle(ChatFormatting.GREEN));
            if (level.getEntity(r.uuid) instanceof CitizenEntity c) {
                StadConversations.speakUp(c, 24, "(Now that " + conditionText(condition).replace("they've", "you've").replace("they have", "you have")
                        + ", you agree to " + (job == null ? "stop working" : "be the town's " + job) + ". Tell {player}, in a sentence.)");
            }
        }
    }

    // ---- what players do -------------------------------------------------------------------------------------------

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Leadership::onHurt);
        NeoForge.EVENT_BUS.addListener(Leadership::onDeath);
        TheyWillTalkApi.get().addReflectionField(new RespectField());
    }

    private static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity c) || !(event.getSource().getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Optional<Colony> colony = c.colony();
        long now = c.level().getGameTime();
        String key = c.getUUID() + "|" + p.getUUID();
        if (colony.isEmpty() || now - hitCooldown.getOrDefault(key, -1000L) < 400) {
            return;
        }
        hitCooldown.put(key, now);
        colony.get().changeStanding(p.getUUID(), p.getGameProfile().getName(), -6, "you attacked " + c.getName().getString(), now);
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity c)) {
            return;
        }
        c.colony().ifPresent(colony -> {
            long now = c.level().getGameTime();
            String name = c.getName().getString();
            if (event.getSource().getEntity() instanceof ServerPlayer p) {
                colony.changeStanding(p.getUUID(), p.getGameProfile().getName(), -30, "you killed " + name, now);
            } else {
                colony.changeLeadersStanding(-4, name + " died", now);
            }
            colony.citizens().stream().filter(r -> c.getUUID().equals(r.spouse)).forEach(r -> r.spouse = null);
            colony.removeCitizen(c.getUUID());
        });
    }

    /** Asks, in TWT's reflection after each reply, how the player's standing as a leader changed. */
    public static final class RespectField implements ReflectionField {
        @Override
        public String name() {
            return "stad_respect";
        }

        @Override
        public JsonObject schema() {
            JsonObject change = new JsonObject();
            change.addProperty("type", "integer");
            change.addProperty("minimum", -3);
            change.addProperty("maximum", 3);
            JsonObject why = new JsonObject();
            why.addProperty("type", "string");
            why.addProperty("maxLength", 60);
            JsonObject props = new JsonObject();
            props.add("change", change);
            props.add("why", why);
            JsonObject o = new JsonObject();
            o.addProperty("type", "object");
            o.add("properties", props);
            JsonArray req = new JsonArray();
            req.add("change");
            req.add("why");
            o.add("required", req);
            return o;
        }

        @Override
        public String instruction() {
            return "how this exchange changed {villager}'s respect for {player} as a leader of the town: -3 (bullied, lied, broke a promise, "
                    + "ignored the town's needs) to +3 (kept a promise, helped the town, treated people well); usually 0. \"why\": a few words, "
                    + "written as \"you ...\" to {player}, e.g. \"you promised me a bed\", or \"\".";
        }

        @Override
        public boolean appliesTo(TurnInfo turn) {
            return turn.kind() == Stad.CITIZEN;
        }

        @Override
        public void apply(TurnInfo turn, JsonElement value) {
            JsonObject o = value.getAsJsonObject();
            int change = Math.max(-3, Math.min(3, o.get("change").getAsInt()));
            String why = o.get("why").getAsString().strip();
            var server = ServerLifecycleHooks.getCurrentServer();
            if (change == 0 || server == null) {
                return;
            }
            server.execute(() -> ColonyManager.get(server).ofCitizen(turn.villager()).ifPresent(colony -> {
                if (colony.rank(turn.player()).atLeast(Rank.MEMBER)) {
                    colony.changeStanding(turn.player(), turn.playerName(), change * 2,
                            why.isEmpty() ? (change > 0 ? "you impressed " : "you disappointed ") + turn.villagerName() : why,
                            server.overworld().getGameTime());
                }
            }));
        }
    }

}
