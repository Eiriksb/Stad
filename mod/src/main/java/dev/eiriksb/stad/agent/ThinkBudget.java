package dev.eiriksb.stad.agent;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.theywilltalk.api.LlmApi;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who gets the language model's time (PLAN.md §4, "Think budget / level of detail"). Citizens near a player plan their
 * day with the model; the rest follow their routine. A town gets at most {@code plansPerDay} model plans a day, and
 * they're asked for one or two at a time while the model isn't busy, so a town of fifty doesn't hold up conversations.
 * Server thread.
 */
public final class ThinkBudget {
    /** Model plans asked for per town each time the towns are looked after (every 5 seconds). */
    static final int PER_ROUND = 2;

    private static final Map<Integer, Deque<UUID>> waiting = new HashMap<>();
    private static final Map<Integer, long[]> spent = new HashMap<>();
    private static long statsDay = -1;
    private static int byModel;
    private static int byRoutine;

    private ThinkBudget() {}

    /** Whether this citizen is near enough to a player to be worth the model's time. */
    public static boolean nearPlayer(CitizenEntity c) {
        int r = StadConfig.THINK_RADIUS.get();
        return r > 0 && c.level().getNearestPlayer(c, r) != null;
    }

    /** Whether background thinking should wait: the model is off, or others are already waiting for it. */
    public static boolean busy() {
        LlmApi llm = TheyWillTalkApi.get().llm();
        return !llm.ready() || llm.backlog() >= StadConfig.BUSY_QUEUE.get();
    }

    /** Whether {@code colony} may still have a citizen plan their day with the model today. */
    static boolean allowed(Colony colony, long day) {
        int max = StadConfig.PLANS_PER_DAY.get();
        long[] s = spent.get(colony.id);
        return max == 0 || s == null || s[0] != day || s[1] < max;
    }

    static void spend(Colony colony, long day) {
        long[] s = spent.computeIfAbsent(colony.id, k -> new long[]{day, 0});
        if (s[0] != day) {
            s[0] = day;
            s[1] = 0;
        }
        s[1]++;
        count(day, true);
    }

    static Deque<UUID> waiting(Colony colony) {
        return waiting.computeIfAbsent(colony.id, k -> new ArrayDeque<>());
    }

    static void count(long day, boolean model) {
        if (day != statsDay) {
            statsDay = day;
            byModel = 0;
            byRoutine = 0;
        }
        if (model) {
            byModel++;
        } else {
            byRoutine++;
        }
    }

    /** For {@code /stad perf}: how today's plans were made. */
    public static String describe(MinecraftServer server) {
        int queued = waiting.values().stream().mapToInt(Deque::size).sum();
        return "day plans today: " + byModel + " thought through, " + byRoutine + " routine" + (queued > 0 ? ", " + queued + " waiting" : "");
    }

    public static boolean allowedForTest(Colony colony, long day) {
        return allowed(colony, day);
    }

    public static void spendForTest(Colony colony, long day) {
        spend(colony, day);
    }

    public static void clear() {
        waiting.clear();
        spent.clear();
        statsDay = -1;
    }
}
