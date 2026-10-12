package dev.eiriksb.stad.agent;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.twt.StadConversations;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Citizens' lives together (PLAN.md §5, "MCA-style life"): evenings spent together make friends and pass news along,
 * close friends court, couples marry, married couples have children, and children grow up.
 */
public final class Social {
    static final int COURT_AFFINITY = 40;
    static final int COURT_FAMILIARITY = 4;
    static final int MARRY_AFFINITY = 60;
    static final int MARRY_FAMILIARITY = 8;
    private static final Map<String, Long> metToday = new HashMap<>();

    private Social() {}

    /** Two citizens spend time together (once a day counts): they grow closer and swap news. Server thread. */
    public static void met(CitizenEntity a, CitizenEntity b) {
        if (a.isBaby() || b.isBaby()) {
            return;
        }
        long day = a.level().getDayTime() / 24000;
        String key = a.getUUID().compareTo(b.getUUID()) < 0 ? a.getUUID() + "|" + b.getUUID() : b.getUUID() + "|" + a.getUUID();
        if (metToday.getOrDefault(key, -1L) == day) {
            return;
        }
        metToday.put(key, day);
        MemoryApi memory = TheyWillTalkApi.get().memory();
        memory.adjustRelation(a.getUUID(), b.getUUID(), 2 + a.getRandom().nextInt(6), 1);
        memory.adjustRelation(b.getUUID(), a.getUUID(), 2 + b.getRandom().nextInt(6), 1);
        memory.tell(a.getUUID(), b.getUUID(), Subject.NONE, 6, 1);
        memory.tell(b.getUUID(), a.getUUID(), Subject.NONE, 6, 1);
    }

    /** Once a day, in the morning: children grow up, couples court and marry, families grow. Server thread. */
    public static void morning(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        for (CitizenRecord r : colony.citizens()) {
            if (level.getEntity(r.uuid) instanceof CitizenEntity c && c.isBaby()
                    && level.getGameTime() - c.born() > StadConfig.CHILD_DAYS.get() * 24000L) {
                c.setChild(false);
                Announce.members(level.getServer(), colony, Component.literal(r.name + " has grown up.").withStyle(ChatFormatting.GREEN));
            }
        }
        List<CitizenRecord> adults = colony.citizens().stream().filter(r -> !isChild(level, r)).toList();
        for (int i = 0; i < adults.size(); i++) {
            for (int j = i + 1; j < adults.size(); j++) {
                CitizenRecord a = adults.get(i), b = adults.get(j);
                if (a.spouse == null && b.spouse == null && !family(a, b)) {
                    courtship(level, colony, a, b);
                } else if (b.uuid.equals(a.spouse)) {
                    maybeChild(level, colony, a, b, day);
                }
            }
        }
    }

    private static boolean isChild(ServerLevel level, CitizenRecord r) {
        return level.getEntity(r.uuid) instanceof CitizenEntity c && c.isBaby();
    }

    /** Parent and child, or siblings. */
    static boolean family(CitizenRecord a, CitizenRecord b) {
        return a.parents.contains(b.uuid) || b.parents.contains(a.uuid) || a.parents.stream().anyMatch(b.parents::contains);
    }

    private static void courtship(ServerLevel level, Colony colony, CitizenRecord a, CitizenRecord b) {
        MemoryApi memory = TheyWillTalkApi.get().memory();
        memory.relation(a.uuid, b.uuid).thenCombine(memory.relation(b.uuid, a.uuid), (ab, ba) -> new Relation[]{ab, ba})
                .thenAccept(rel -> level.getServer().execute(() -> decide(level, colony, a, b, rel[0], rel[1])))
                .exceptionally(t -> null);
    }

    public static void decide(ServerLevel level, Colony colony, CitizenRecord a, CitizenRecord b, Relation ab, Relation ba) {
        if (!colony.hasCitizen(a.uuid) || !colony.hasCitizen(b.uuid) || a.spouse != null || b.spouse != null) {
            return;
        }
        int affinity = Math.min(ab.affinity(), ba.affinity());
        int familiarity = Math.min(ab.familiarity(), ba.familiarity());
        boolean partners = "partner".equals(ab.kind()) && "partner".equals(ba.kind());
        MemoryApi memory = TheyWillTalkApi.get().memory();
        if (partners && affinity >= MARRY_AFFINITY && familiarity >= MARRY_FAMILIARITY) {
            marry(level, colony, a, b);
        } else if (!partners && affinity >= COURT_AFFINITY && familiarity >= COURT_FAMILIARITY) {
            memory.setRelationKind(a.uuid, b.uuid, "partner");
            memory.setRelationKind(b.uuid, a.uuid, "partner");
            Announce.members(level.getServer(), colony, Component.literal(a.name + " and " + b.name + " are courting.").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    public static void marry(ServerLevel level, Colony colony, CitizenRecord a, CitizenRecord b) {
        MemoryApi memory = TheyWillTalkApi.get().memory();
        a.spouse = b.uuid;
        b.spouse = a.uuid;
        memory.setRelationKind(a.uuid, b.uuid, "spouse");
        memory.setRelationKind(b.uuid, a.uuid, "spouse");
        memory.remember(NewMemory.of(a.uuid, Subject.villager(b.uuid), "I married " + b.name + "!").withKind("event").withImportance(9));
        memory.remember(NewMemory.of(b.uuid, Subject.villager(a.uuid), "I married " + a.name + "!").withKind("event").withImportance(9));
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(a.name + " and " + b.name + " got married!").withStyle(ChatFormatting.LIGHT_PURPLE));
        if (level.getEntity(a.uuid) instanceof CitizenEntity c) {
            StadConversations.speakUp(c, 32, "(You just married " + b.name + ". Tell {player} the happy news, in a sentence or two.)");
        }
    }

    private static void maybeChild(ServerLevel level, Colony colony, CitizenRecord a, CitizenRecord b, long day) {
        if (day - Math.max(a.lastChildDay, b.lastChildDay) < 3 || a.happiness < 55 || b.happiness < 55) {
            return;
        }
        long homeless = colony.citizens().stream().filter(r -> r.home == null).count();
        if (Homes.freeBeds(level, colony) <= homeless || level.random.nextFloat() > 0.35f) {
            return;
        }
        haveChild(level, colony, a, b);
    }

    /** A baby is born to {@code a} and {@code b}, by whichever of them is around. Returns the baby, or null. */
    public static CitizenEntity haveChild(ServerLevel level, Colony colony, CitizenRecord a, CitizenRecord b) {
        CitizenEntity parent = level.getEntity(a.uuid) instanceof CitizenEntity c ? c : level.getEntity(b.uuid) instanceof CitizenEntity d ? d : null;
        CitizenEntity baby = parent == null ? null : StadRegistries.CITIZEN.get().create(level);
        if (baby == null) {
            return null;
        }
        baby.moveTo(parent.getX(), parent.getY(), parent.getZ(), parent.getYRot(), 0);
        baby.finalizeSpawn(level, level.getCurrentDifficultyAt(parent.blockPosition()), MobSpawnType.BREEDING, null);
        String first = baby.getName().getString().split(" ")[0];
        String family = a.name.contains(" ") ? a.name.substring(a.name.indexOf(' ') + 1) : a.name;
        baby.setCustomName(Component.literal(first + " " + family));
        baby.setChild(true);
        baby.setBackstory("were born in " + colony.name() + " to " + a.name + " and " + b.name);
        level.addFreshEntity(baby);
        ColonyManager.get(level.getServer()).join(colony, baby);
        colony.citizen(baby.getUUID()).ifPresent(r -> r.parents = List.of(a.uuid, b.uuid));
        long day = level.getDayTime() / 24000;
        a.lastChildDay = day;
        b.lastChildDay = day;
        String name = baby.getName().getString();
        MemoryApi memory = TheyWillTalkApi.get().memory();
        for (CitizenRecord p : List.of(a, b)) {
            memory.remember(NewMemory.of(p.uuid, Subject.villager(baby.getUUID()), "Our child " + name + " was born!").withKind("event").withImportance(9));
        }
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(a.name + " and " + b.name + " had a baby: " + name + "!")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return baby;
    }

}
