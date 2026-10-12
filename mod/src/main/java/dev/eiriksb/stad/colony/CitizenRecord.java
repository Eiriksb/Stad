package dev.eiriksb.stad.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

import java.util.UUID;

/**
 * What a colony knows about one of its citizens, also while they're not loaded: their home, and how they were
 * doing when last seen (for the town hall screen and the dashboard).
 */
public final class CitizenRecord {
    public final UUID uuid;
    public String name;
    public long joined;
    /** the head of their bed, or null when homeless */
    public BlockPos home;
    public int hunger = 20;
    public int rest = 20;
    public int happiness = 50;
    /** "builder", or null for no job */
    public String job;

    public CitizenRecord(UUID uuid, String name, long joined) {
        this.uuid = uuid;
        this.name = name;
        this.joined = joined;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Uuid", uuid);
        t.putString("Name", name);
        t.putLong("Joined", joined);
        if (home != null) {
            t.put("Home", NbtUtils.writeBlockPos(home));
        }
        t.putInt("Hunger", hunger);
        t.putInt("Rest", rest);
        t.putInt("Happiness", happiness);
        if (job != null) {
            t.putString("Job", job);
        }
        return t;
    }

    static CitizenRecord load(CompoundTag t) {
        CitizenRecord r = new CitizenRecord(t.getUUID("Uuid"), t.getString("Name"), t.getLong("Joined"));
        r.home = NbtUtils.readBlockPos(t, "Home").orElse(null);
        r.hunger = t.getInt("Hunger");
        r.rest = t.getInt("Rest");
        r.happiness = t.getInt("Happiness");
        r.job = t.contains("Job") ? t.getString("Job") : null;
        return r;
    }
}
