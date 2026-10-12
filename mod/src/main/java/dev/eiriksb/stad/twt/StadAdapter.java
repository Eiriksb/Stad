package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.EatAtTownHallGoal;
import dev.eiriksb.stad.building.Construction;
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
        f.ageGroup = "adult";
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
        if (builder) {
            builderContext(c, t, f);
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
            f.context.add(player.getGameProfile().getName() + " is " + r.describe() + ".");
        }
    }

    /** What the builder is working on, and what they're missing: they can tell the player when asked. */
    private static void builderContext(CitizenEntity c, Colony t, VillagerFacts f) {
        Building current = t.buildings().stream().filter(b -> !b.done() && c.getUUID().equals(b.builder)).findFirst()
                .or(() -> t.buildings().stream().filter(b -> !b.done() && b.builder == null).findFirst()).orElse(null);
        if (current == null) {
            f.context.add("Nothing is planned for you to build. You'd love it if the leader planned something (with the building plans).");
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
