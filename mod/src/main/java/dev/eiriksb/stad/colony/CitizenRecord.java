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
    /** whether they went hungry today (for the morning review) */
    public boolean wentHungry;
    /** days in a row they've been miserable; at three they leave */
    public int unhappyDays;
    /** until when (game time) they work grudgingly, slower */
    public long grudgingUntil;
    /** what they want before they'll do the order they refused: "food" or "bed" (null: nothing pending) */
    public String condition;
    /** the job they'll take once their condition is met */
    public String pendingJob;
    /** who asked for it */
    public java.util.UUID pendingFrom;
    /** their husband or wife */
    public java.util.UUID spouse;
    public java.util.List<java.util.UUID> parents = java.util.List.of();
    /** the day they last had a child */
    public long lastChildDay = -100;

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
        t.putBoolean("WentHungry", wentHungry);
        t.putInt("UnhappyDays", unhappyDays);
        t.putLong("GrudgingUntil", grudgingUntil);
        if (condition != null) {
            t.putString("Condition", condition);
        }
        if (pendingJob != null) {
            t.putString("PendingJob", pendingJob);
        }
        if (pendingFrom != null) {
            t.putUUID("PendingFrom", pendingFrom);
        }
        if (spouse != null) {
            t.putUUID("Spouse", spouse);
        }
        net.minecraft.nbt.ListTag ps = new net.minecraft.nbt.ListTag();
        parents.forEach(p -> ps.add(net.minecraft.nbt.NbtUtils.createUUID(p)));
        t.put("Parents", ps);
        t.putLong("LastChildDay", lastChildDay);
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
        r.wentHungry = t.getBoolean("WentHungry");
        r.unhappyDays = t.getInt("UnhappyDays");
        r.grudgingUntil = t.getLong("GrudgingUntil");
        r.condition = t.contains("Condition") ? t.getString("Condition") : null;
        r.pendingJob = t.contains("PendingJob") ? t.getString("PendingJob") : null;
        r.pendingFrom = t.hasUUID("PendingFrom") ? t.getUUID("PendingFrom") : null;
        r.spouse = t.hasUUID("Spouse") ? t.getUUID("Spouse") : null;
        java.util.List<java.util.UUID> parents = new java.util.ArrayList<>();
        t.getList("Parents", net.minecraft.nbt.Tag.TAG_INT_ARRAY).forEach(p -> parents.add(net.minecraft.nbt.NbtUtils.loadUUID(p)));
        r.parents = java.util.List.copyOf(parents);
        r.lastChildDay = t.contains("LastChildDay") ? t.getLong("LastChildDay") : -100;
        return r;
    }
}
