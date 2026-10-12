package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Tells They Will Talk who a citizen is. Server thread. */
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
        // No colonies yet (M2): every citizen is a newcomer without a town.
        f.job = "unemployed";
        f.role = "newcomer looking for a town to settle in and work for; there is no town here yet";
        f.backstory = c.backstory().isEmpty() ? null : c.backstory();
    }

    @Override
    public void holdAttention(Entity entity, Entity target) {
        CitizenEntity c = (CitizenEntity) entity;
        c.getNavigation().stop();
        c.getLookControl().setLookAt(target, 30f, 30f);
    }
}
