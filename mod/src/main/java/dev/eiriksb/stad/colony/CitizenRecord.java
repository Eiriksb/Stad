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
    /** what they've done at work today (blocks placed, crops harvested, logs chopped, items delivered) */
    public int workToday;
    /** their plan for today, written each morning (see {@link dev.eiriksb.stad.agent.DayPlanner}) */
    public java.util.List<String> plan = java.util.List.of();
    /** the day the plan is for */
    public long planDay = -1;
    /** who they mean to see this evening, from the plan */
    public String planVisit;
    /** whether they mean to take it easy today, from the plan */
    public boolean planRest;
    /** game time they last spent time with someone (a chat, an evening at the town hall) */
    public long lastSocial;

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
        t.putInt("WorkToday", workToday);
        net.minecraft.nbt.ListTag planTag = new net.minecraft.nbt.ListTag();
        plan.forEach(p -> planTag.add(net.minecraft.nbt.StringTag.valueOf(p)));
        t.put("Plan", planTag);
        t.putLong("PlanDay", planDay);
        if (planVisit != null) {
            t.putString("PlanVisit", planVisit);
        }
        t.putBoolean("PlanRest", planRest);
        t.putLong("LastSocial", lastSocial);
        return t;
    }

    static CitizenRecord load(CompoundTag t) {
        CitizenRecord r = new CitizenRecord(t.getUUID("Uuid"), t.getString("Name"), t.getLong("Joined"));
        r.home = NbtUtils.readBlockPos(t, "Home").orElse(null);
        r.hunger = t.getInt("Hunger");
        r.rest = t.getInt("Rest");
        r.happiness = t.getInt("Happiness");
        r.job = t.contains("Job") ? t.getString("Job") : null;
        r.workToday = t.getInt("WorkToday");
        java.util.List<String> plan = new java.util.ArrayList<>();
        t.getList("Plan", net.minecraft.nbt.Tag.TAG_STRING).forEach(p -> plan.add(p.getAsString()));
        r.plan = java.util.List.copyOf(plan);
        r.planDay = t.contains("PlanDay") ? t.getLong("PlanDay") : -1;
        r.planVisit = t.contains("PlanVisit") ? t.getString("PlanVisit") : null;
        r.planRest = t.getBoolean("PlanRest");
        r.lastSocial = t.getLong("LastSocial");
        return r;
    }
}
