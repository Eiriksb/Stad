package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

    enum Task { PICK_UP, SHEAR, BUTCHER, FEED }

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

    static boolean herd(Animal a) {
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
        };
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
        if (animals.isEmpty()) {
            return;
        }
        List<Animal> woolly = animals.stream().filter(a -> a instanceof Sheep s && s.readyForShearing()).toList();
        if (!woolly.isEmpty()) {
            set(Task.SHEAR, nearest(woolly));
            return;
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
        boolean haveFeed = pocketed(ShepherdGoal::feed) || ColonyStorage.deliveries(level, colony).count(ShepherdGoal::feed) > 0;
        for (List<Animal> kind : kinds.values()) {
            List<Animal> ready = kind.stream().filter(a -> !a.isBaby() && a.getAge() == 0 && a.canFallInLove()).toList();
            boolean waiting = kind.stream().anyMatch(Animal::isInLove); // one's been fed: feed it a partner
            if (kind.size() < herdSize && (ready.size() >= 2 || waiting && !ready.isEmpty()) && haveFeed) {
                set(Task.FEED, nearest(ready));
                return;
            }
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
