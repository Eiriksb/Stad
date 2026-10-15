package dev.eiriksb.stad.war;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Who fights for a town, and whom (PLAN.md §6.7). Server thread. */
public final class Combat {
    private Combat() {}

    /** how far a fighter looks for someone to fight */
    public static final double RANGE = 20;
    private static final Set<String> BRAVE = Set.of("boastful", "dramatic");
    /** best first */
    private static final List<net.minecraft.world.item.Item> SWORDS = List.of(Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD);

    public static boolean guard(CitizenEntity c) {
        return Jobs.GUARD.equals(c.job().orElse(null)) && !c.isBaby();
    }

    /** Bold sorts pick up a weapon when their town is attacked. */
    public static boolean brave(CitizenEntity c) {
        String p = TheyWillTalkApi.get().profile(c.getUUID()).map(VillagerProfile::persona).orElse("");
        return BRAVE.contains(p) && !c.isBaby();
    }

    /** Whether their town is being raided (by mobs or players), or is raiding someone. */
    public static boolean underAttack(Colony colony) {
        return Raids.of(colony).isPresent() || Wars.raidOf(colony.id).isPresent();
    }

    /** Whether they fight: guards always, brave citizens while their town is attacked; envoys and children never. */
    public static boolean fighter(CitizenEntity c) {
        if (c.isBaby() || Envoys.isEnvoy(c.getUUID())) {
            return false;
        }
        Optional<Colony> colony = c.colony();
        return guard(c) || colony.map(Combat::underAttack).orElse(false) && brave(c);
    }

    /** Guards stay up at night while their town is attacked. */
    public static boolean onWatch(CitizenEntity c) {
        return guard(c) && c.colony().map(Combat::underAttack).orElse(false);
    }

    /** Someone {@code c} should fight: the nearest monster or raider on their town's land, or an enemy in a war raid. */
    public static LivingEntity target(CitizenEntity c) {
        Colony colony = c.colony().orElse(null);
        if (colony == null || !(c.level() instanceof ServerLevel level)) {
            return null;
        }
        Set<Integer> enemies = Wars.raidEnemies(colony.id);
        AABB area = c.getBoundingBox().inflate(RANGE, 8, RANGE);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (e == c || !hostile(e, colony, enemies, level)) {
                continue;
            }
            double d = e.distanceToSqr(c);
            if (d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    private static boolean hostile(LivingEntity e, Colony colony, Set<Integer> enemies, ServerLevel level) {
        if (e instanceof Enemy && !(e instanceof Creeper)) {
            ColonyManager m = ColonyManager.get(level.getServer());
            boolean ours = colony.contains(level.dimension(), e.blockPosition()); // our land, or a town we're raiding
            return ours || Raids.isRaider(e, colony.id) || enemies.stream().anyMatch(id -> m.byId(id).map(o -> o.contains(level.dimension(),
                    e.blockPosition())).orElse(false));
        }
        if (enemies.isEmpty()) {
            return false;
        }
        ColonyManager m = ColonyManager.get(level.getServer());
        if (e instanceof ServerPlayer p) {
            return !p.isCreative() && !p.isSpectator() && enemies.stream().anyMatch(id -> m.byId(id).map(o -> o.rank(p.getUUID()).atLeast(Rank.MEMBER))
                    .orElse(false));
        }
        if (e instanceof CitizenEntity other) {
            return other.colony().map(o -> enemies.contains(o.id)).orElse(false) && fighter(other);
        }
        return false;
    }

    /** A guard without a sword takes the best one from the town's stores; anyone else who has one puts it back. */
    public static void arm(CitizenEntity c, ServerLevel level, Colony colony) {
        ItemStack held = c.getItemBySlot(EquipmentSlot.MAINHAND);
        boolean armed = held.getItem() instanceof SwordItem;
        if (guard(c)) {
            armour(c, level, colony);
        }
        if (guard(c) && !armed) {
            ColonyStorage[] stores = {ColonyStorage.supply(level, colony), ColonyStorage.warehouses(level, colony)};
            for (net.minecraft.world.item.Item sword : SWORDS) {
                for (ColonyStorage s : stores) {
                    List<ItemStack> got = s.takeUpTo(st -> st.is(sword), 1);
                    if (!got.isEmpty()) {
                        c.setItemSlot(EquipmentSlot.MAINHAND, got.getFirst());
                        c.setDropChance(EquipmentSlot.MAINHAND, 1.0f);
                        return;
                    }
                }
            }
        } else if (!guard(c) && armed && !underAttack(colony)) {
            ItemStack rest = ColonyStorage.deliveries(level, colony).put(held);
            c.setItemSlot(EquipmentSlot.MAINHAND, rest);
        }
    }

    /** A guard puts on the best armour in the town's stores, piece by piece. */
    static void armour(CitizenEntity c, ServerLevel level, Colony colony) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!c.getItemBySlot(slot).isEmpty()) {
                continue;
            }
            ColonyStorage[] stores = {ColonyStorage.supply(level, colony), ColonyStorage.warehouses(level, colony)};
            ItemStack best = null;
            ColonyStorage from = null;
            for (ColonyStorage s : stores) {
                for (ColonyStorage.Box b : s.boxes()) {
                    for (int i = 0; i < b.container().getContainerSize(); i++) {
                        ItemStack it = b.container().getItem(i);
                        if (it.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getEquipmentSlot() == slot
                                && (best == null || a.getDefense() > ((net.minecraft.world.item.ArmorItem) best.getItem()).getDefense())) {
                            best = it;
                            from = s;
                        }
                    }
                }
            }
            if (best != null) {
                net.minecraft.world.item.Item item = best.getItem();
                List<ItemStack> got = from.takeUpTo(st -> st.is(item), 1);
                if (!got.isEmpty()) {
                    c.setItemSlot(slot, got.getFirst());
                    c.setDropChance(slot, 1.0f);
                }
            }
        }
    }

    /** The armour slots, in the order the smith makes them (the chestplate guards the most). */
    public static final EquipmentSlot[] ARMOUR = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.HEAD, EquipmentSlot.FEET};

    /** How many pieces for a slot the town's guards are short of: guards without one, less the spares in the stores. */
    public static int armourShortage(ServerLevel level, Colony colony, EquipmentSlot slot) {
        int lacking = 0;
        for (dev.eiriksb.stad.colony.CitizenRecord r : colony.citizens()) {
            if (Jobs.GUARD.equals(r.job) && level.getEntity(r.uuid) instanceof CitizenEntity e && guard(e) && e.getItemBySlot(slot).isEmpty()) {
                lacking++;
            }
        }
        if (lacking == 0) {
            return 0;
        }
        int spare = ColonyStorage.supply(level, colony).count(st -> armourFor(st, slot)) + ColonyStorage.warehouses(level, colony).count(st -> armourFor(st, slot));
        return Math.max(0, lacking - spare);
    }

    static boolean armourFor(ItemStack st, EquipmentSlot slot) {
        return st.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getEquipmentSlot() == slot;
    }

    /** Whether a guard still needs a sword (for the town's requests). */
    public static boolean unarmed(CitizenEntity c) {
        return guard(c) && !(c.getItemBySlot(EquipmentSlot.MAINHAND).getItem() instanceof SwordItem);
    }
}
