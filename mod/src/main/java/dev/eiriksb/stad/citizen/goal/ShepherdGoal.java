package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Pens;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shepherd looks after the farm animals on the town's land (sheep, cows, pigs, chickens): picks up what they drop,
 * shears the sheep, feeds them so each kind breeds up to {@code herdSize}, and butchers the extras for meat. Named or
 * leashed animals are someone's pets and are left alone.
 */
public class ShepherdGoal extends WorkGoal {
    private static final Set<net.minecraft.world.item.Item> PRODUCTS = Set.of(Items.EGG, Items.FEATHER, Items.LEATHER, Items.MUTTON,
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.RABBIT_HIDE);

    enum Task { PICK_UP, SHEAR, BUTCHER, FEED, LEAD }

    private Task task;
    private Entity target;

    public ShepherdGoal(CitizenEntity citizen) {
        super(citizen, Jobs.SHEPHERD);
    }

    @Override
    protected ItemStack tool() {
        return new ItemStack(Items.SHEARS);
    }

    /** Feed: they keep a little to hand. */
    @Override
    protected boolean keep(ItemStack stack) {
        return feed(stack) && stack.getCount() <= 16;
    }

    private static boolean feed(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT) || s.is(Items.WHEAT_SEEDS);
    }

    public static boolean herd(Animal a) {
        return (a instanceof Sheep || a instanceof Cow || a instanceof Pig || a instanceof Chicken) && !(a instanceof TamableAnimal)
                && !a.hasCustomName() && !a.isLeashed() && a.isAlive();
    }

    static boolean product(ItemStack s) {
        return PRODUCTS.contains(s.getItem()) || s.is(ItemTags.WOOL);
    }

    private static AABB land(Colony colony) {
        BlockPos c = colony.center;
        return new AABB(c).inflate(colony.radius, 16, colony.radius);
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (target != null && target.isAlive() && still(level)) {
            return true;
        }
        choose(level, colony);
        return task != null;
    }

    /** Whether the current task is still worth doing. */
    private boolean still(ServerLevel level) {
        return switch (task) {
            case PICK_UP -> true;
            case SHEAR -> target instanceof Sheep s && s.readyForShearing();
            case BUTCHER -> true;
            case FEED -> target instanceof Animal a && a.canFallInLove() && a.getAge() == 0;
            case LEAD -> target instanceof Animal a && (a.getLeashHolder() == citizen || !a.isLeashed());
        };
    }

    /** ticks until they look round outside town again for animals to bring in */
    private int lookOutside;
    /** whether they found any animals in town or nearby when they last looked */
    private boolean anyAnimals = true;

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        return anyAnimals ? null : "No farm animals on the town's land, and none nearby to bring in: lead in two sheep, cows, pigs or"
                + " chickens (they follow wheat or carrots)";
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return ColonyStorage.deliveries(level, colony).count(ShepherdGoal::feed) > 0 || pocketed(ShepherdGoal::feed) ? "Keeping an eye on the animals"
                : "Keeping an eye on the animals (to breed them they need wheat, carrots, potatoes or seeds in the town hall)";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "Looking after the animals";
        }
        return switch (task) {
            case PICK_UP -> "Picking up eggs and wool";
            case SHEAR -> "Shearing the sheep";
            case BUTCHER -> "Butchering";
            case FEED -> "Feeding the animals so they breed";
            case LEAD -> penFor != null ? "Bringing " + Pens.word(Pens.kind(penFor)) + " into the " + penFor.name.toLowerCase(java.util.Locale.ROOT)
                    : "Bringing animals into town";
        };
    }

    /** the pen the animal being led is going to, or null */
    private Building penFor;

    /** Where animals brought in are let go: their barn, or by the town hall. */
    private BlockPos home(Colony colony) {
        return workplace(colony).map(dev.eiriksb.stad.colony.Workplaces::spot).orElse(colony.center.offset(2, 0, 2));
    }

    /**
     * When a kind of animal has fewer than two in town (so it can't breed), a wild one of that kind from just outside
     * town, if there's one; kinds the town already has one of first.
     */
    private Animal wild(ServerLevel level, Colony colony, Map<EntityType<?>, List<Animal>> kinds, EntityType<?> only) {
        AABB around = new AABB(colony.center).inflate(colony.radius + 48, 16, colony.radius + 48);
        List<Animal> out = level.getEntitiesOfClass(Animal.class, around, a -> herd(a) && !a.isBaby()
                && !colony.contains(level.dimension(), a.blockPosition()) && kinds.getOrDefault(a.getType(), List.of()).size() < 2
                && (only == null || a.getType() == only));
        return out.stream().min(Comparator.comparingInt((Animal a) -> kinds.containsKey(a.getType()) ? 0 : 1)
                .thenComparingDouble(a -> a.distanceToSqr(citizen))).orElse(null);
    }

    private void choose(ServerLevel level, Colony colony) {
        task = null;
        target = null;
        AABB land = land(colony);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, citizen.getBoundingBox().inflate(16, 4, 16),
                e -> product(e.getItem()) && colony.contains(level.dimension(), e.blockPosition()));
        if (!drops.isEmpty()) {
            set(Task.PICK_UP, nearest(drops));
            return;
        }
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, land, a -> herd(a) && colony.contains(level.dimension(), a.blockPosition()));
        Map<EntityType<?>, List<Animal>> byKind = new HashMap<>();
        animals.forEach(a -> byKind.computeIfAbsent(a.getType(), k -> new java.util.ArrayList<>()).add(a));
        if (animals.isEmpty()) {
            bringIn(level, colony, byKind);
            if (task == null) {
                for (Building pen : Pens.of(colony)) {
                    Animal wild = wild(level, colony, Map.of(), Pens.kind(pen));
                    if (wild != null) {
                        penFor = pen;
                        set(Task.LEAD, wild);
                        break;
                    }
                }
            }
            anyAnimals = task != null;
            return;
        }
        anyAnimals = true;
        matchmake(animals);
        List<Animal> woolly = animals.stream().filter(a -> a instanceof Sheep s && s.readyForShearing()).toList();
        if (!woolly.isEmpty()) {
            set(Task.SHEAR, nearest(woolly));
            return;
        }
        // the town's pens: loose animals of the kind a pen is for go into it, and an empty pen gets a pair from outside
        for (Building pen : Pens.of(colony)) {
            EntityType<?> kind = Pens.kind(pen);
            List<Animal> loose = animals.stream().filter(a -> a.getType() == kind && !a.isBaby() && Pens.of(colony).stream()
                    .noneMatch(other -> Pens.inside(other, a.blockPosition()))).toList();
            if (!loose.isEmpty()) {
                penFor = pen;
                set(Task.LEAD, nearest(loose));
                return;
            }
            long in = animals.stream().filter(a -> a.getType() == kind && Pens.inside(pen, a.blockPosition())).count();
            if (in < 2 && --lookOutside <= 0) {
                lookOutside = 10;
                Animal wild = wild(level, colony, Map.of(), kind);
                if (wild != null) {
                    penFor = pen;
                    set(Task.LEAD, wild);
                    return;
                }
            }
        }
        int herdSize = StadConfig.HERD_SIZE.get();
        Map<EntityType<?>, List<Animal>> kinds = new HashMap<>();
        animals.forEach(a -> kinds.computeIfAbsent(a.getType(), k -> new java.util.ArrayList<>()).add(a));
        for (List<Animal> kind : kinds.values()) {
            List<Animal> grown = kind.stream().filter(a -> !a.isBaby()).toList();
            if (kind.size() > herdSize + 4 && !grown.isEmpty()) {
                set(Task.BUTCHER, nearest(grown));
                return;
            }
        }
        ColonyStorage feedStore = ColonyStorage.deliveries(level, colony);
        for (List<Animal> kind : kinds.values()) {
            List<Animal> ready = kind.stream().filter(a -> !a.isBaby() && a.getAge() == 0 && a.canFallInLove()).toList();
            boolean waiting = kind.stream().anyMatch(Animal::isInLove); // one's been fed: feed it a partner
            // what this kind eats (cows wheat, chickens seeds...), in their pockets or the stores
            boolean haveFeed = !ready.isEmpty() && (pocketed(ready.getFirst()::isFood) || feedStore.count(ready.getFirst()::isFood) > 0);
            if (kind.size() < herdSize && (ready.size() >= 2 || waiting && !ready.isEmpty()) && haveFeed) {
                set(Task.FEED, nearest(ready));
                return;
            }
        }
        // nothing else to do: a partner for an animal on its own, or a pair when nothing in town can breed
        if (byKind.values().stream().anyMatch(l -> l.size() == 1) || byKind.values().stream().noneMatch(l -> l.size() >= 2)) {
            bringIn(level, colony, byKind);
        }
    }

    /**
     * Animals in love only find a partner within eight blocks: ones that have wandered further apart are sent towards
     * each other (they'd just fall out of love otherwise).
     */
    private static void matchmake(List<Animal> animals) {
        for (Animal a : animals) {
            if (!a.isInLove()) {
                continue;
            }
            animals.stream().filter(b -> b != a && b.getType() == a.getType() && b.isInLove()).min(Comparator.comparingDouble(b -> b.distanceToSqr(a)))
                    .filter(b -> b.distanceToSqr(a) > 36).ifPresent(b -> {
                        a.getNavigation().moveTo(b, 1.0);
                        b.getNavigation().moveTo(a, 1.0);
                    });
        }
    }

    /** Leads in a wild animal from just outside town, if there's one (looking every few tries). */
    private void bringIn(ServerLevel level, Colony colony, Map<EntityType<?>, List<Animal>> byKind) {
        if (--lookOutside > 0) {
            return;
        }
        lookOutside = 10;
        Animal wild = wild(level, colony, byKind, null);
        if (wild != null) {
            penFor = Pens.of(colony).stream().filter(p -> Pens.kind(p) == wild.getType()).findFirst().orElse(null);
            set(Task.LEAD, wild);
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (target instanceof Animal a && a.getLeashHolder() == citizen) {
            a.dropLeash(true, false); // off for the night: let it go where it is
        }
    }

    private void set(Task t, Entity e) {
        task = t;
        target = e;
        approach.reset();
    }

    private <T extends Entity> T nearest(List<T> list) {
        return list.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(citizen))).orElseThrow();
    }

    private boolean pocketed(java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            if (what.test(citizen.inventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null || target == null) {
            return;
        }
        if (task == Task.FEED && !pocketed(s -> target instanceof Animal a && a.isFood(s))) {
            fetchFeed(level, colony);
            return;
        }
        if (task == Task.LEAD) {
            lead(level, colony, (Animal) target);
            return;
        }
        if (!approach.tick(citizen, target.blockPosition(), task == Task.PICK_UP ? 1.8 : 2.6, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(target, 30, 30);
        if (--cooldown > 0) {
            return;
        }
        cooldown = pace(20);
        switch (task) {
            case PICK_UP -> {
                if (target instanceof ItemEntity item) {
                    pocket(item.getItem().copy());
                    item.discard();
                }
            }
            case SHEAR -> {
                if (target instanceof Sheep sheep && sheep.readyForShearing()) {
                    sheep.shear(SoundSource.NEUTRAL); // the wool drops, to be picked up next
                    worked(1);
                }
            }
            case BUTCHER -> {
                target.hurt(level.damageSources().mobAttack(citizen), Float.MAX_VALUE);
                citizen.didWork(1);
            }
            case FEED -> {
                if (target instanceof Animal a && usePocketed(a::isFood)) {
                    a.setInLove(null);
                    citizen.didWork(1);
                }
            }
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        task = null;
        target = null;
        approach.reset();
    }

    /** Walks out to the animal, puts it on a lead, and walks it home. */
    private void lead(ServerLevel level, Colony colony, Animal animal) {
        if (animal.getLeashHolder() != citizen) {
            if (!approach.tick(citizen, animal.blockPosition(), 2.2, 0.6)) {
                return;
            }
            animal.setLeashedTo(citizen, true);
            citizen.swing(InteractionHand.MAIN_HAND);
            approach.reset();
            return;
        }
        Pens.Gate gate = penFor != null && colony.building(penFor.id).orElse(null) == penFor ? Pens.gate(level, penFor).orElse(null) : null;
        BlockPos home = gate != null ? gate.outside() : penFor != null ? Pens.middle(penFor) : home(colony);
        if (citizen.distanceToSqr(animal) > 36) {
            citizen.getNavigation().stop(); // let it catch up
            animal.getNavigation().moveTo(citizen, 1.1);
            return;
        }
        if (!approach.tick(citizen, home, gate != null ? 2.0 : 3.0, 0.5)) {
            return;
        }
        animal.dropLeash(true, false);
        if (gate != null) {
            // through the gate: open it, let the animal in, shut it behind
            BlockState g = level.getBlockState(gate.pos());
            if (g.getBlock() instanceof FenceGateBlock) {
                level.playSound(null, gate.pos(), SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1f, 1f);
                BlockPos in = gate.inside();
                animal.teleportTo(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
                animal.getNavigation().moveTo(Pens.middle(penFor).getX() + 0.5, Pens.middle(penFor).getY(), Pens.middle(penFor).getZ() + 0.5, 1.0);
                level.setBlock(gate.pos(), g.setValue(FenceGateBlock.OPEN, false), 3);
                level.playSound(null, gate.pos(), SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
            }
        } else if (penFor != null) {
            BlockPos m = Pens.middle(penFor);
            animal.teleportTo(m.getX() + 0.5, m.getY(), m.getZ() + 0.5);
        }
        penFor = null;
        citizen.didWork(1);
        task = null;
        target = null;
        approach.reset();
    }

    private void fetchFeed(ServerLevel level, Colony colony) {
        Animal animal = (Animal) target;
        ColonyStorage.Box box = ColonyStorage.deliveries(level, colony).find(animal::isFood);
        if (box == null) {
            task = null;
            target = null;
            return;
        }
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return;
        }
        for (ItemStack s : ColonyStorage.of(box).takeUpTo(animal::isFood, 8)) {
            pocket(s);
        }
        approach.reset();
    }
}
