package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.citizen.goal.BuildGoal;
import dev.eiriksb.stad.citizen.goal.CourierGoal;
import dev.eiriksb.stad.citizen.goal.FarmGoal;
import dev.eiriksb.stad.citizen.goal.LumberjackGoal;
import dev.eiriksb.stad.citizen.goal.EatAtTownHallGoal;
import dev.eiriksb.stad.citizen.goal.GatherAtTownHallGoal;
import dev.eiriksb.stad.citizen.goal.SleepAtHomeGoal;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.Optional;

/**
 * A Stad citizen: a person who lives in (or is looking for) a town. Talking, memory and voice come from They Will
 * Talk through {@link dev.eiriksb.stad.twt.StadAdapter}; this class is the body: looks, needs, daily routine and saving.
 */
public class CitizenEntity extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> FEMALE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> CHILD = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    public static final float MAX_NEED = 20f;

    /** Where they come from (server side only; They Will Talk tells it in conversation). */
    private Genes genes;
    private int ageYears;
    private String backstory = "";
    /** Their colony's id, 0 for none (the colony's citizen list is the truth; this is a quick lookup). */
    private int colonyId;
    private float hunger = MAX_NEED;
    private float rest = MAX_NEED;
    /** what they carry for work: harvests, logs, deliveries */
    private final SimpleContainer inventory = new SimpleContainer(9);
    /** game time they were born (children) */
    private long born;

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
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ATTACK_DAMAGE, 1.5);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FEMALE, false);
        builder.define(SKIN, 0);
        builder.define(CHILD, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(1, new dev.eiriksb.stad.citizen.goal.EnvoyGoal(this));
        goalSelector.addGoal(1, new dev.eiriksb.stad.citizen.goal.GuardGoal(this));
        goalSelector.addGoal(1, new dev.eiriksb.stad.citizen.goal.ShelterGoal(this));
        goalSelector.addGoal(2, new SleepAtHomeGoal(this));
        goalSelector.addGoal(2, new dev.eiriksb.stad.citizen.goal.IllGoal(this));
        goalSelector.addGoal(3, new EatAtTownHallGoal(this));
        goalSelector.addGoal(4, new BuildGoal(this));
        goalSelector.addGoal(4, new FarmGoal(this));
        goalSelector.addGoal(4, new LumberjackGoal(this));
        goalSelector.addGoal(4, new CourierGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.CookGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.FishGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.ShepherdGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.MineGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.SmithGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.HealerGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.MerchantGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.TeacherGoal(this));
        goalSelector.addGoal(4, new dev.eiriksb.stad.citizen.goal.SchoolGoal(this));
        goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.6));
        goalSelector.addGoal(6, new GatherAtTownHallGoal(this));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    /** A new citizen gets their looks, name and story when they first appear (spawn egg, /summon, moving into town). */
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
        genes = Genes.random(random);
        // most newcomers are working age; a few young ones and a few old hands
        ageYears = 18 + (int) Math.round(Math.abs(random.nextGaussian()) * 18 + random.nextInt(8));
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    /** What they were born with (their voice, for now); made up from their id for citizens from before genes. */
    public Genes genes() {
        if (genes == null) {
            genes = Genes.random(RandomSource.create(getUUID().getLeastSignificantBits()));
        }
        return genes;
    }

    public void setGenes(Genes g) {
        genes = g;
    }

    /** Their age in years once grown up (children: see {@link CitizenVoice#age}). */
    public int ageYears() {
        if (ageYears <= 0) {
            ageYears = 20 + (int) Math.floorMod(getUUID().getMostSignificantBits(), 35L);
        }
        return ageYears;
    }

    public void setAgeYears(int years) {
        ageYears = years;
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

    public void setBackstory(String b) {
        backstory = b;
    }

    @Override
    public boolean isBaby() {
        return entityData.get(CHILD);
    }

    /** Children are half size and don't work; they grow up after a few days ({@link dev.eiriksb.stad.agent.Social}). */
    public void setChild(boolean child) {
        boolean was = entityData.get(CHILD);
        entityData.set(CHILD, child);
        if (child) {
            born = level().getGameTime();
        } else if (was) {
            ageYears = 18; // just grown up
        }
        refreshDimensions();
    }

    public long born() {
        return born;
    }

    public void setBorn(long time) {
        born = time;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (CHILD.equals(key)) {
            refreshDimensions();
        }
    }

    /** Working slower today: they agreed to an order but didn't like it. */
    public boolean grudging() {
        return record().map(r -> r.grudgingUntil > level().getGameTime()).orElse(false);
    }

    // ---- town -----------------------------------------------------------------------------------------------------

    /** Their colony (server side). */
    public Optional<Colony> colony() {
        if (colonyId == 0 || !(level() instanceof ServerLevel server)) {
            return Optional.empty();
        }
        return ColonyManager.get(server.getServer()).byId(colonyId).filter(c -> c.hasCitizen(getUUID()));
    }

    public Optional<CitizenRecord> record() {
        return colony().flatMap(c -> c.citizen(getUUID()));
    }

    /** The head of their bed, if they have one. */
    public Optional<BlockPos> home() {
        return record().map(r -> r.home);
    }

    /** Their job ("builder"), if they have one. */
    public Optional<String> job() {
        return record().map(r -> r.job);
    }

    /** Called by {@link ColonyManager#join}. */
    public void joinedColony(Colony colony) {
        colonyId = colony.id;
        restrictTo(colony.center, colony.radius);
    }

    /** Called when they leave their colony, or it's dissolved. */
    public void leftColony() {
        colonyId = 0;
        clearRestriction();
        if (isSleeping()) {
            stopSleeping();
        }
    }

    // ---- needs and routine --------------------------------------------------------------------------------------

    public float hunger() {
        return hunger;
    }

    public float rest() {
        return rest;
    }

    public void setHunger(float h) {
        hunger = Math.max(0, Math.min(MAX_NEED, h));
    }

    public void setRest(float r) {
        rest = Math.max(0, Math.min(MAX_NEED, r));
    }

    public void eat(int nutrition) {
        setHunger(hunger + nutrition * 2);
    }

    /** 0..100, from food, sleep, a home, work and company (and from M5, how the town is led). */
    public int happiness() {
        Optional<CitizenRecord> r = record();
        boolean company = r.map(x -> level().getGameTime() - x.lastSocial < 24000).orElse(false);
        // a job is worth more with somewhere to do it (PLAN.md §6.8)
        int work = r.map(x -> x.job == null ? 5 : dev.eiriksb.stad.colony.Workplaces.kinds(x.job).isEmpty() ? 15
                : colony().flatMap(c -> dev.eiriksb.stad.colony.Workplaces.of(c, x)).isPresent() ? 20 : 12).orElse(5);
        double h = 30 * hunger / MAX_NEED + 25 * rest / MAX_NEED + (home().isPresent() ? 20 : 0) + work + (company ? 10 : 0)
                - (r.map(x -> x.illness != null).orElse(false) ? 15 : 0);
        return (int) Math.round(Math.max(0, Math.min(100, h)));
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    /** Working hours: daytime, awake, fed, and not taking the afternoon off when their plan says to rest. */
    public boolean canWork() {
        if (isSleeping() || isNight() || hunger < 4 || isBaby() || ill()) {
            return false;
        }
        if (colony().map(c -> c.onStrike(level().getDayTime() / 24000)).orElse(false)) {
            return false;
        }
        long t = level().getDayTime() % 24000;
        return !(t > 6000 && record().map(r -> r.planRest).orElse(false));
    }

    /** Counts work done today (for prompts and the town hall). */
    public void didWork(int amount) {
        record().ifPresent(r -> {
            r.workToday += amount;
            if (Skills.learn(r, r.job, amount) && level() instanceof net.minecraft.server.level.ServerLevel sl) {
                colony().ifPresent(c -> dev.eiriksb.stad.colony.Announce.members(sl.getServer(), c, net.minecraft.network.chat.Component
                        .literal(r.name + " is " + Skills.describe(r) + " now.").withStyle(net.minecraft.ChatFormatting.GREEN)));
            }
        });
    }

    /** Whether they're ill (PLAN.md §6.9). */
    public boolean ill() {
        return record().map(r -> r.illness != null).orElse(false);
    }

    /** How good they are at their job (0 to {@link Skills#MAX}). */
    public int skill() {
        return record().map(r -> Skills.level(r, r.job)).orElse(0);
    }

    /** They spent time with someone. */
    public void hadCompany() {
        record().ifPresent(r -> r.lastSocial = level().getGameTime());
    }

    /** what they're up to at work, said by their job's goal, and when */
    private String doing = "";
    private long doingAt = -100_000;
    /** what's stopping their work and what would fix it, said by their job's goal, and when */
    private String stuck = "";
    private long stuckAt = -100_000;

    /** Their job's goal says what they're doing ("Harvesting the wheat"). */
    public void doing(String what) {
        doing = what;
        doingAt = level().getGameTime();
        stuckAt = -100_000;
    }

    /** Their job's goal says what's stopping them, and what would fix it ("No seeds: put wheat seeds in the town hall"). */
    public void stuck(String why) {
        stuck = why;
        stuckAt = level().getGameTime();
    }

    public String doing() {
        return doing;
    }

    public String stuck() {
        return stuck;
    }

    boolean doingFresh() {
        return level().getGameTime() - doingAt < Status.FRESH;
    }

    boolean stuckFresh() {
        return level().getGameTime() - stuckAt < Status.FRESH;
    }

    /** What they're doing, or why they can't work, in a line for the player. */
    public Status status() {
        return Status.of(this);
    }

    public boolean isNight() {
        long t = level().getDayTime() % 24000;
        return t >= 12500 && t < 23500;
    }

    /** What they're up to, for admins: running goals, where they're walking, needs. */
    public String activity() {
        var path = getNavigation().getPath();
        return "doing " + goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning())
                .map(g -> g.getGoal().getClass().getSimpleName().replace("Goal", "")).toList()
                + (path == null ? ", standing" : ", walking to " + path.getTarget().toShortString() + (path.canReach() ? "" : " (can't reach)"))
                + (isSleeping() ? ", asleep" : "") + String.format(", food %.1f, rest %.1f", hunger, rest)
                + home().map(h -> ", bed at " + h.toShortString()).orElse(", homeless");
    }

    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || isSleeping();
    }

    @Override
    public void tick() {
        if (level().isClientSide) {
            super.tick();
            return;
        }
        long t = dev.eiriksb.stad.perf.Perf.start();
        super.tick();
        dev.eiriksb.stad.perf.Perf.end("citizens", t);
    }

    @Override
    public void aiStep() {
        if (!level().isClientSide) {
            if (isSleeping() && (!isNight() && !ill() || hunger < 2)) {
                stopSleeping();
            }
            if (tickCount % 20 == 0) {
                tickNeeds();
            }
            if (tickCount % 100 == 0 && hunger >= 6 && getHealth() < getMaxHealth()) {
                heal(isSleeping() ? 2 : 1); // well fed, they mend
            }
            if (tickCount % 10 == 0) {
                onRoad(level() instanceof net.minecraft.server.level.ServerLevel sl && colony().map(c -> dev.eiriksb.stad.building.Roads.on(sl, c,
                        BlockPos.containing(getX(), getY() - 0.5, getZ()))).orElse(false));
            }
            if (tickCount % 100 == 0) {
                trainedAsGuard(dev.eiriksb.stad.colony.Jobs.GUARD.equals(job().orElse(null)));
                Tools.tidy(this);
                Status.watch(this);
            }
            if (tickCount % 100 == 0) {
                record().ifPresent(r -> {
                    r.name = getName().getString();
                    r.hunger = Math.round(hunger);
                    r.rest = Math.round(rest);
                    r.happiness = happiness();
                });
            }
        }
        super.aiStep();
    }

    private static final net.minecraft.resources.ResourceLocation GUARD_TRAINING = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "guard");

    private static final net.minecraft.resources.ResourceLocation ROAD = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "road");

    /** On the town's roads, they walk faster. */
    private void onRoad(boolean road) {
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.hasModifier(ROAD) == road) {
            return;
        }
        if (road) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(ROAD, dev.eiriksb.stad.building.Roads.FASTER,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        } else {
            speed.removeModifier(ROAD);
        }
    }

    /** Guards are trained fighters: tougher and harder-hitting while they have the job. */
    private void trainedAsGuard(boolean guard) {
        var health = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        var attack = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (health == null || attack == null || health.hasModifier(GUARD_TRAINING) == guard) {
            return;
        }
        if (guard) {
            health.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(GUARD_TRAINING, 10,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            attack.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(GUARD_TRAINING, 1.5,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        } else {
            health.removeModifier(GUARD_TRAINING);
            attack.removeModifier(GUARD_TRAINING);
            setHealth(Math.min(getHealth(), getMaxHealth()));
        }
    }

    private void tickNeeds() {
        // per second: a full stomach lasts HUNGER_DAYS Minecraft days (20 minutes each)
        setHunger(hunger - (float) (MAX_NEED / (StadConfig.HUNGER_DAYS.get() * 1200.0)));
        if (hunger < 4) {
            record().ifPresent(r -> r.wentHungry = true);
        }
        if (isSleeping()) {
            rest = Math.min(MAX_NEED, rest + MAX_NEED / 400f); // a night's sleep fills it
        } else if (isNight() && home().isEmpty()) {
            rest = Math.min(MAX_NEED, rest + MAX_NEED / 1200f); // dozing on the ground
        } else {
            rest = Math.max(0, rest - MAX_NEED / 1500f);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && sp.isShiftKeyDown()
                && dev.eiriksb.stad.colony.Jobs.MERCHANT.equals(job().orElse(null))) {
            String why = dev.eiriksb.stad.colony.Trading.open(sp, this); // sneak and right-click a merchant: straight to trading
            if (why != null) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(why).withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) {
            CitizenInteraction.show(sp, this);
            return InteractionResult.SUCCESS;
        }
        return hand == InteractionHand.MAIN_HAND ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    // ---- saving -----------------------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Female", isFemale());
        tag.putInt("Skin", skin());
        tag.putString("Backstory", backstory);
        tag.putInt("Colony", colonyId);
        tag.putFloat("Hunger", hunger);
        tag.putFloat("Rest", rest);
        tag.put("Inventory", inventory.createTag(registryAccess()));
        tag.putBoolean("Child", isBaby());
        tag.putLong("Born", born);
        tag.put("Genes", genes().save());
        tag.putInt("Age", ageYears());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(FEMALE, tag.getBoolean("Female"));
        entityData.set(SKIN, Math.floorMod(tag.getInt("Skin"), CitizenLooks.SKIN_COUNT));
        backstory = tag.getString("Backstory");
        colonyId = tag.getInt("Colony");
        hunger = tag.contains("Hunger") ? tag.getFloat("Hunger") : MAX_NEED;
        rest = tag.contains("Rest") ? tag.getFloat("Rest") : MAX_NEED;
        inventory.fromTag(tag.getList("Inventory", net.minecraft.nbt.Tag.TAG_COMPOUND), registryAccess());
        entityData.set(CHILD, tag.getBoolean("Child"));
        born = tag.getLong("Born");
        genes = Genes.load(tag.getCompound("Genes"));
        ageYears = tag.getInt("Age");
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        // Back in the world: stay within the town's land (the restriction itself isn't saved).
        colony().ifPresentOrElse(c -> restrictTo(c.center, c.radius), () -> colonyId = 0);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        net.minecraft.world.Containers.dropContents(level, blockPosition(), inventory);
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
