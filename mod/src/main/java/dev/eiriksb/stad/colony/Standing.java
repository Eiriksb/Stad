package dev.eiriksb.stad.colony;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * How a town sees one player as a leader (PLAN.md §6.6): a score from -100 (despised) to 100 (beloved), and the
 * latest things that changed it, so citizens can say why.
 */
public final class Standing {
    private static final int EVENTS = 20;

    public record Event(long time, int delta, String cause) {}

    private int score;
    private final Deque<Event> events = new ArrayDeque<>();

    public int score() {
        return score;
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    void change(int delta, String cause, long time) {
        score = Math.max(-100, Math.min(100, score + delta));
        events.addFirst(new Event(time, delta, cause));
        while (events.size() > EVENTS) {
            events.removeLast();
        }
    }

    void set(int value) {
        score = Math.max(-100, Math.min(100, value));
    }

    /** "beloved", "respected", "accepted", "doubted", "resented", "despised" */
    public String word() {
        return word(score);
    }

    public static String word(int score) {
        return score >= 60 ? "beloved" : score >= 25 ? "respected" : score >= -10 ? "accepted" : score >= -35 ? "doubted"
                : score >= -65 ? "resented" : "despised";
    }

    /** The biggest recent reasons, worst first: "Astrid went hungry", ... */
    public List<Event> worst(int n) {
        return events.stream().filter(e -> e.delta() < 0).sorted((a, b) -> Integer.compare(a.delta(), b.delta())).limit(n).toList();
    }

    public List<Event> best(int n) {
        return events.stream().filter(e -> e.delta() > 0).sorted((a, b) -> Integer.compare(b.delta(), a.delta())).limit(n).toList();
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Score", score);
        ListTag list = new ListTag();
        for (Event e : events) {
            CompoundTag et = new CompoundTag();
            et.putLong("Time", e.time());
            et.putInt("Delta", e.delta());
            et.putString("Cause", e.cause());
            list.add(et);
        }
        t.put("Events", list);
        return t;
    }

    static Standing load(CompoundTag t) {
        Standing s = new Standing();
        s.score = t.getInt("Score");
        for (Tag tag : t.getList("Events", Tag.TAG_COMPOUND)) {
            CompoundTag et = (CompoundTag) tag;
            s.events.addLast(new Event(et.getLong("Time"), et.getInt("Delta"), et.getString("Cause")));
        }
        return s;
    }
}
