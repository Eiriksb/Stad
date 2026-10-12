package dev.eiriksb.stad.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * A Stad citizen: a person who lives in (or is looking for) a town. Talking, memory and voice come from They Will
 * Talk through {@link dev.eiriksb.stad.twt.StadAdapter}; this class is the body: looks, movement and saving.
 */
public class CitizenEntity extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> FEMALE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    /** Where they come from (server side only; They Will Talk tells it in conversation). */
    private String backstory = "";

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FEMALE, false);
        builder.define(SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0f));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /** A new citizen gets their looks and name when they first appear (spawn egg, /summon, arriving in town). */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        SpawnGroupData data) {
        boolean female = random.nextBoolean();
        entityData.set(FEMALE, female);
        entityData.set(SKIN, random.nextInt(CitizenLooks.SKIN_COUNT));
        if (!hasCustomName()) {
            setCustomName(Component.literal(CitizenNames.random(female, random)));
        }
        backstory = CitizenStories.random(random);
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    public boolean isFemale() {
        return entityData.get(FEMALE);
    }

    public int skin() {
        return entityData.get(SKIN);
    }

    public String backstory() {
        return backstory;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Female", isFemale());
        tag.putInt("Skin", skin());
        tag.putString("Backstory", backstory);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(FEMALE, tag.getBoolean("Female"));
        entityData.set(SKIN, Math.floorMod(tag.getInt("Skin"), CitizenLooks.SKIN_COUNT));
        backstory = tag.getString("Backstory");
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }
}
