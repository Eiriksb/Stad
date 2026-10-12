package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.EatAtTownHallGoal;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.colony.Request;
import dev.eiriksb.stad.colony.Standing;
import dev.eiriksb.stad.colony.WorkSites;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tells They Will Talk who a citizen is and how their life in town is going. Server thread. */
public final class StadAdapter implements VillagerAdapter {
    @Override
    public VillagerKind kind() {
        return Stad.CITIZEN;
    }

    @Override
    public boolean matches(Entity entity) {
        return entity instanceof CitizenEntity;
    }

    @Override
    public void collect(Entity entity, ServerPlayer player, VillagerFacts f) {
        CitizenEntity c = (CitizenEntity) entity;
        f.uuid = c.getUUID();
        f.kind = Stad.CITIZEN;
        f.dimension = c.level().dimension().location().toString();
        f.x = c.getX();
        f.y = c.getY();
        f.z = c.getZ();
        f.alive = c.isAlive();
        if (c.hasCustomName() && c.getCustomName() != null) {
            f.name = c.getCustomName().getString();
        }
        f.gender = c.isFemale() ? "f" : "m";
        f.ageGroup = c.isBaby() ? "child" : "adult";
        f.biomeType = c.level().getBiome(c.blockPosition()).unwrapKey().map(k -> k.location().getPath()).orElse("plains");
        f.sleeping = c.isSleeping();
        f.backstory = c.backstory().isEmpty() ? null : c.backstory();
        f.job = c.job().orElse("unemployed");

        Optional<Colony> town = c.colony();
        if (town.isEmpty()) {
            f.role = "newcomer looking for a town to settle in and work for; you don't belong to any town yet";
            return;
        }
        Colony t = town.get();
        f.village = new VillagerFacts.Village("stad:" + t.id, "stad", t.name(), t.dimension.location().toString(),
                t.center.getX(), t.center.getY(), t.center.getZ(), t.population());
        boolean builder = Jobs.BUILDER.equals(c.job().orElse(null));
        f.role = (builder ? "the builder of the young town of " : "citizen of the young town of ") + t.name() + ", founded by " + t.ownerName();
        List<String> buildings = new ArrayList<>();
        for (Building b : t.buildings()) {
            buildings.add(b.name + " (" + (b.done() ? "built" : b.status == Building.Status.PLANNED ? "planned" : "being built") + ")");
        }
        if (!buildings.isEmpty()) {
            f.context.add("Buildings in town: " + String.join(", ", buildings) + ".");
        }
        String job = c.job().orElse(null);
        if (builder) {
            builderContext(c, t, f);
        } else if (job != null) {
            jobContext(c, t, f, job);
        } else {
            f.context.add("You have no job yet; you'd like the leader to give you one (builder, farmer, lumberjack or courier).");
        }
        c.record().ifPresent(r -> {
            if (!r.plan.isEmpty()) {
                f.context.add("Your plan for today: " + String.join("; ", r.plan) + ".");
            }
        });
        if (!t.requests().isEmpty()) {
            f.context.add("The town needs: " + t.requests().stream().limit(4).map(Request::label).collect(java.util.stream.Collectors.joining(", "))
                    + (ColonyStorage.warehouses((net.minecraft.server.level.ServerLevel) c.level(), t).isEmpty() ? " (put it in the town hall)." : "."));
        }
        f.context.add(t.name() + " has " + t.population() + (t.population() == 1 ? " citizen" : " citizens") + "; its leader is " + t.ownerName()
                + officers(t) + ".");
        f.context.add(c.home().isPresent() ? "You have a bed of your own in town."
                : "You have no home or bed in town and sleep on the ground; you'd love a bed of your own.");
        if (c.hunger() < 4) {
            f.context.add(EatAtTownHallGoal.townHasFood(c) ? "You're very hungry." : "You're starving: there's no food in the town hall.");
        } else if (c.hunger() < 8) {
            f.context.add(EatAtTownHallGoal.townHasFood(c) ? "You're getting hungry." : "You're hungry, and the town hall has no food.");
        }
        if (c.rest() < 5) {
            f.context.add("You're exhausted.");
        }
        f.extra.put("happiness", c.happiness() + "%");
        if (player != null) {
            Rank r = t.rank(player.getUUID());
            String name = player.getGameProfile().getName();
            f.context.add(name + " is " + r.describe() + ".");
            Standing s = t.standing(player.getUUID());
            if (r.atLeast(Rank.OFFICER) || s.score() != 0) {
                List<String> why = new ArrayList<>();
                s.worst(2).forEach(e -> why.add(e.cause()));
                s.best(1).forEach(e -> why.add(e.cause()));
                f.context.add("As a leader, the town sees " + name + " as " + s.word() + (why.isEmpty() ? "" : " (lately: "
                        + String.join("; ", why).replace("you ", name + " ") + ")") + ".");
            }
        }
        family(c, t, f);
        if (player != null && builder) {
            dev.eiriksb.stad.planner.Drafts.get(player.getUUID()).filter(d -> d.colony == t.id).ifPresent(d -> f.context.add(
                    "You and " + player.getGameProfile().getName() + " are drawing up a building: " + d.design.describe()
                            + ". Its ghost stands " + dev.eiriksb.stad.planner.Drafts.where(d, player)
                            + ". They can ask for changes, or tell you to build it."));
        }
    }

    /** What the builder is working on, and what they're missing: they can tell the player when asked. */
    private static void builderContext(CitizenEntity c, Colony t, VillagerFacts f) {
        Building current = t.buildings().stream().filter(b -> !b.done() && c.getUUID().equals(b.builder)).findFirst()
                .or(() -> t.buildings().stream().filter(b -> !b.done() && b.builder == null).findFirst()).orElse(null);
        if (current == null) {
            f.context.add("Nothing is planned for you to build. You'd love it if the leader planned something: they can describe a building"
                    + " to you and you'll draw it up, or pick one from the building plans.");
            return;
        }
        int pct = (int) Math.round(current.fractionDone() * 100);
        switch (current.status) {
            case WAITING -> f.context.add("You're building the " + current.name + " (" + pct + "% done) but you're stuck: the town is missing "
                    + Construction.describe(current.missing, 5) + ". Ask for them to be put in the town hall.");
            case PLANNED -> f.context.add("Next you'll build the " + current.name + "; you haven't started yet.");
            default -> f.context.add("You're building the " + current.name + " (" + pct + "% done).");
        }
        t.buildings().stream().filter(b -> !b.done() && b != current).findFirst()
                .ifPresent(b -> f.context.add("After that, the " + b.name + " is planned."));
    }

    private static void jobContext(CitizenEntity c, Colony t, VillagerFacts f, String job) {
        int done = c.record().map(r -> r.workToday).orElse(0);
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) c.level();
        f.context.add("You're the town's " + job + ": " + Jobs.describe(job) + ".");
        switch (job) {
            case Jobs.FARMER -> f.context.add(WorkSites.farmland(level, t).isEmpty()
                    ? "There's no farmland on the town's land yet; you'd like the leader to make some fields (hoe the dirt, near water)."
                    : "Today you've harvested " + done + " crops.");
            case Jobs.LUMBERJACK -> f.context.add(WorkSites.trees(level, t).isEmpty() && done == 0
                    ? "There are no trees left on the town's land to fell."
                    : "Today you've felled " + done + " logs.");
            case Jobs.COURIER -> f.context.add(ColonyStorage.warehouses(level, t).isEmpty()
                    ? "The town has no warehouse yet, so there's nothing for you to carry; you'd like the leader to have one built."
                    : "Today you've carried " + done + " items.");
            default -> {
            }
        }
    }

    /** Spouse, parents and children, so They Will Talk knows the family. */
    private static void family(CitizenEntity c, Colony t, VillagerFacts f) {
        c.record().ifPresent(r -> {
            if (r.spouse != null) {
                t.citizen(r.spouse).ifPresent(s -> f.family.add(new VillagerFacts.FamilyLink("spouse", s.uuid, s.name, false, false)));
            }
            for (UUID p : r.parents) {
                t.citizen(p).ifPresent(s -> f.family.add(new VillagerFacts.FamilyLink("parent", s.uuid, s.name, false, false)));
            }
            for (var other : t.citizens()) {
                if (other.parents.contains(r.uuid)) {
                    f.family.add(new VillagerFacts.FamilyLink("child", other.uuid, other.name, false, false));
                }
            }
        });
    }

    private static String officers(Colony t) {
        List<String> names = new ArrayList<>();
        t.ranks().forEach((uuid, rank) -> {
            if (rank == Rank.OFFICER) {
                names.add(t.playerName(uuid));
            }
        });
        return names.isEmpty() ? "" : " (officers: " + String.join(", ", names) + ")";
    }

    @Override
    public void holdAttention(Entity entity, Entity target) {
        CitizenEntity c = (CitizenEntity) entity;
        if (!c.isSleeping()) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(target, 30f, 30f);
        }
    }
}
