package dev.eiriksb.stad.war;

import dev.eiriksb.stad.citizen.CitizenLooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A raider of one of Stad's factions (bandits, Norse raiders, marauders): a person, drawn with the player model, in the
 * faction's gear. Bowmen shoot from a distance, the rest close in. One raider of each raid is its leader: named, tougher
 * and better armoured.
 */
public class RaiderEntity extends Monster implements RangedAttackMob {
    private static final EntityDataAccessor<Integer> FACTION = SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);

    private final RangedBowAttackGoal<RaiderEntity> bowGoal = new RangedBowAttackGoal<>(this, 1.0, 30, 15f);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.1, false);
    private boolean leader;

    public RaiderEntity(EntityType<? extends RaiderEntity> type, Level level) {
        super(type, level);
        setCanPickUpLoot(false);
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation g) {
            g.setCanOpenDoors(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 16).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.5).add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FACTION, Faction.BANDITS.ordinal());
        builder.define(SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(3, new OpenDoorGoal(this, false));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /** Dresses and arms them as one of {@code faction}'s; the leader gets a name and the best gear. */
    public void outfit(Faction faction, boolean leader, RandomSource random) {
        this.leader = leader;
        entityData.set(FACTION, faction.ordinal());
        entityData.set(SKIN, random.nextInt(CitizenLooks.SKIN_COUNT));
        // the weapon is for show: how hard they hit is the faction's (a raider with a player's iron axe cut down whole towns)
        setItemSlot(EquipmentSlot.MAINHAND, blunt(leader && faction != Faction.PILLAGERS ? melee(faction) : faction.weapon(random)));
        if (faction == Faction.NORSE) {
            getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(3.5);
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            // the rank and file dress the part; only the leader's armour really protects
            setItemSlot(slot, leader ? faction.wear(slot, true) : blunt(faction.wear(slot, false)));
            setDropChance(slot, 0.03f);
        }
        setDropChance(EquipmentSlot.MAINHAND, 0.05f);
        if (leader) {
            String name = faction.leaderName(random);
            setCustomName(Component.literal(name));
            setCustomNameVisible(true);
            getAttribute(Attributes.MAX_HEALTH).addPermanentModifier(new AttributeModifier(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "raid_leader"), 20, AttributeModifier.Operation.ADD_VALUE));
            getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(new AttributeModifier(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "raid_leader"), 2, AttributeModifier.Operation.ADD_VALUE));
            setHealth(getMaxHealth());
        } else {
            setCustomName(Component.literal(capitalize(faction.one)));
            setCustomNameVisible(false);
        }
        reassessWeaponGoal();
    }

    private static ItemStack blunt(ItemStack weapon) {
        if (weapon.isEmpty()) {
            return weapon;
        }
        weapon.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        return weapon;
    }

    private static ItemStack melee(Faction faction) {
        return switch (faction) {
            case NORSE -> new ItemStack(Items.IRON_AXE);
            case MARAUDERS -> new ItemStack(Items.GOLDEN_SWORD);
            default -> new ItemStack(Items.IRON_SWORD);
        };
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public Faction faction() {
        return Faction.values()[Math.floorMod(entityData.get(FACTION), Faction.values().length)];
    }

    public int skin() {
        return entityData.get(SKIN);
    }

    public boolean leader() {
        return leader;
    }

    /** Bow in hand: shoot from where they stand; otherwise close in. */
    public void reassessWeaponGoal() {
        if (level().isClientSide) {
            return;
        }
        goalSelector.removeGoal(meleeGoal);
        goalSelector.removeGoal(bowGoal);
        if (getMainHandItem().getItem() instanceof BowItem) {
            goalSelector.addGoal(4, bowGoal);
        } else {
            goalSelector.addGoal(4, meleeGoal);
        }
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (slot == EquipmentSlot.MAINHAND && !level().isClientSide) {
            reassessWeaponGoal();
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack bow = getMainHandItem();
        ItemStack arrows = new ItemStack(Items.ARROW);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, arrows, power, bow);
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + dist * 0.2, dz, 1.6f, 14 - level().getDifficulty().getId() * 4);
        playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (getRandom().nextFloat() * 0.4f + 0.8f));
        level().addFreshEntity(arrow);
    }

    @Override
    public boolean canFireProjectileWeapon(net.minecraft.world.item.ProjectileWeaponItem weapon) {
        return weapon instanceof BowItem;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Faction", entityData.get(FACTION));
        tag.putInt("Skin", entityData.get(SKIN));
        tag.putBoolean("Leader", leader);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(FACTION, tag.getInt("Faction"));
        entityData.set(SKIN, tag.getInt("Skin"));
        leader = tag.getBoolean("Leader");
        reassessWeaponGoal();
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return SoundEvents.VINDICATOR_AMBIENT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }
}
