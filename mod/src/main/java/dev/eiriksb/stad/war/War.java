package dev.eiriksb.stad.war;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A war between two towns (PLAN.md §6.7): declared by the attacker, a warning period, at most one raid inside the
 * defender's window, then it's over and a truce keeps them apart for a while. Times are real-world milliseconds.
 * Saved with the colonies.
 */
public final class War {
    public enum State { DECLARED, RAID, OVER }

    public enum Outcome {
        PEACE("made peace"), ATTACKERS("the attackers took the banner"), DEFENDERS("the defenders held"), RETREAT("the attackers retreated");

        public final String words;

        Outcome(String words) {
            this.words = words;
        }
    }

    public final int id;
    public final int attacker;
    public final int defender;
    public final long declared;
    public final UUID declaredBy;
    public final String declaredByName;
    public State state = State.DECLARED;
    public long raidStarted;
    public long ended;
    public Outcome outcome;
    /** the towns that have offered peace */
    public final Set<Integer> peaceOffers = new LinkedHashSet<>();

    public War(int id, int attacker, int defender, long declared, UUID declaredBy, String declaredByName) {
        this.id = id;
        this.attacker = attacker;
        this.defender = defender;
        this.declared = declared;
        this.declaredBy = declaredBy;
        this.declaredByName = declaredByName;
    }

    public boolean involves(int colony) {
        return attacker == colony || defender == colony;
    }

    public int other(int colony) {
        return colony == attacker ? defender : attacker;
    }

    public boolean on() {
        return state != State.OVER;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", id);
        t.putInt("Attacker", attacker);
        t.putInt("Defender", defender);
        t.putLong("Declared", declared);
        t.putUUID("By", declaredBy);
        t.putString("ByName", declaredByName);
        t.putString("State", state.name());
        t.putLong("RaidStarted", raidStarted);
        t.putLong("Ended", ended);
        if (outcome != null) {
            t.putString("Outcome", outcome.name());
        }
        ListTag offers = new ListTag();
        peaceOffers.forEach(o -> offers.add(IntTag.valueOf(o)));
        t.put("PeaceOffers", offers);
        return t;
    }

    public static War load(CompoundTag t) {
        War w = new War(t.getInt("Id"), t.getInt("Attacker"), t.getInt("Defender"), t.getLong("Declared"), t.getUUID("By"), t.getString("ByName"));
        w.state = State.valueOf(t.getString("State"));
        w.raidStarted = t.getLong("RaidStarted");
        w.ended = t.getLong("Ended");
        w.outcome = t.contains("Outcome") ? Outcome.valueOf(t.getString("Outcome")) : null;
        for (Tag o : t.getList("PeaceOffers", Tag.TAG_INT)) {
            w.peaceOffers.add(((IntTag) o).getAsInt());
        }
        return w;
    }
}
