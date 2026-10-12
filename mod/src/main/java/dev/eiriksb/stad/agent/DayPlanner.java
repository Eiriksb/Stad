package dev.eiriksb.stad.agent;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Request;
import dev.eiriksb.theywilltalk.api.LlmApi;
import dev.eiriksb.theywilltalk.api.Memory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Every morning each citizen who's around plans their day (PLAN.md §4, "Plan day"): one call to the language model
 * with who they are, their job, how they're doing, their friends, what they remember and what the town needs.
 * The plan colours their conversations and can change what they do (a visit in the evening, an afternoon off).
 */
public final class DayPlanner {
    private DayPlanner() {}

    /** Every few seconds: starts the day's plans once it's morning. Server thread. */
    public static void tick(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        long time = level.getDayTime() % 24000;
        if (time > 4000) {
            return;
        }
        for (CitizenRecord r : colony.citizens()) {
            if (r.planDay == day) {
                continue;
            }
            if (!(level.getEntity(r.uuid) instanceof CitizenEntity c)) {
                continue; // planned when they're next around in the morning
            }
            r.planDay = day;
            r.workToday = 0;
            r.plan = List.of();
            r.planVisit = null;
            r.planRest = false;
            colony.changed();
            plan(level, colony, c, r);
        }
    }

    private static void plan(ServerLevel level, Colony colony, CitizenEntity c, CitizenRecord r) {
        TheyWillTalkApi twt = TheyWillTalkApi.get();
        if (!twt.llm().ready()) {
            return;
        }
        Optional<VillagerProfile> profile = twt.profile(c.getUUID());
        String situation = situation(colony, c, r);
        List<String> townsfolk = colony.citizens().stream().filter(o -> !o.uuid.equals(r.uuid)).map(o -> o.name).toList();
        CompletableFuture<List<Relation>> friends = twt.memory().relations(c.getUUID(), 4).exceptionally(t -> List.of());
        CompletableFuture<List<Memory>> memories = twt.memory().recent(c.getUUID(), 4).exceptionally(t -> List.of());
        friends.thenCombine(memories, (fs, ms) -> prompt(c, profile, situation, townsfolk, fs, ms))
                .thenCompose(user -> twt.llm().json(SYSTEM, user, DayPlan.schema(), 220, LlmApi.Priority.PLANNING))
                .thenAccept(json -> level.getServer().execute(() -> {
                    DayPlan plan = DayPlan.parse(json, townsfolk);
                    r.plan = plan.intentions();
                    r.planVisit = plan.visit();
                    r.planRest = plan.rest();
                    colony.changed();
                    Stad.LOGGER.debug("{} plans: {} (visit {}, rest {})", r.name, plan.intentions(), plan.visit(), plan.rest());
                }))
                .exceptionally(t -> {
                    Stad.LOGGER.debug("No day plan for {}: {}", r.name, t.toString());
                    return null;
                });
    }

    private static final String SYSTEM = """
            You plan one day in the life of a citizen of a small Minecraft town, in their own voice. Write 1 to 3 short,
            concrete intentions for today that fit who they are, their job and how they're doing (e.g. "Finish the cottage
            roof", "Ask the leader for more glass", "Have supper with Ingrid"). Kinds: work, rest (take the afternoon off),
            socialize (spend the evening with someone: put their name in "with"), errand. Leave "with" empty otherwise.
            Only name people from the list of townsfolk. Mood: how they feel this morning.""";

    private static String prompt(CitizenEntity c, Optional<VillagerProfile> profile, String situation, List<String> townsfolk,
                                 List<Relation> friends, List<Memory> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("Citizen: ").append(c.getName().getString());
        profile.ifPresent(p -> sb.append("\nPersonality: ").append(p.personaEnum().description).append(". Quirk: ").append(p.quirk())
                .append(".\nBackstory: they ").append(p.backstory()).append('.'));
        sb.append('\n').append(situation);
        sb.append("\nTownsfolk: ").append(townsfolk.isEmpty() ? "nobody else yet" : String.join(", ", townsfolk)).append('.');
        if (!friends.isEmpty()) {
            sb.append("\nPeople they know: ").append(friends.stream().filter(f -> f.otherName() != null)
                    .map(f -> f.otherName() + (f.affinity() >= 15 ? " (likes them)" : f.affinity() <= -15 ? " (dislikes them)" : ""))
                    .collect(Collectors.joining(", "))).append('.');
        }
        if (!memories.isEmpty()) {
            sb.append("\nOn their mind:");
            memories.forEach(m -> sb.append("\n- ").append(m.text()));
        }
        return sb.toString();
    }

    private static String situation(Colony colony, CitizenEntity c, CitizenRecord r) {
        StringBuilder sb = new StringBuilder("Town: ").append(colony.name()).append(", led by ").append(colony.ownerName()).append('.');
        if (r.job != null) {
            sb.append("\nJob: ").append(r.job).append(" (").append(Jobs.describe(r.job)).append(").");
            if (Jobs.BUILDER.equals(r.job)) {
                colony.buildings().stream().filter(b -> !b.done()).findFirst().ifPresent(b -> sb.append(" Working on: the ").append(b.name)
                        .append(b.status == Building.Status.WAITING ? " (stuck waiting for materials)" : "").append('.'));
            }
        } else {
            sb.append("\nNo job yet.");
        }
        sb.append("\nHow they are: ").append(c.hunger() < 8 ? "hungry, " : "fed, ").append(c.rest() < 8 ? "tired, " : "rested, ")
                .append(r.home != null ? "has a bed" : "homeless (sleeps on the ground)").append('.');
        List<Request> needs = colony.requests();
        if (!needs.isEmpty()) {
            sb.append("\nThe town needs: ").append(needs.stream().limit(4).map(Request::label).collect(Collectors.joining(", "))).append('.');
        }
        return sb.toString();
    }
}
