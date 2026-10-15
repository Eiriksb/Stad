package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.WorkSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.Comparator;

/**
 * The cook fetches ingredients from the town's stores (wheat, raw meat and fish, potatoes), cooks them at a furnace,
 * smoker or campfire on the town's land (three wheat make a loaf), and brings the food to the town hall, where everyone
 * eats. Cooked food fills a citizen up far more than raw.
 */
public class CookGoal extends WorkGoal {
    /** Wheat per loaf, as at a crafting table. */
    static final int WHEAT_PER_BREAD = 3;
    /** Ingredients fetched at a time. */
    static final int BATCH = 16;

    private BlockPos stove;

    public CookGoal(CitizenEntity citizen) {
        super(citizen, Jobs.COOK);
    }

    /** What cooking {@code stack} makes (one of them), or nothing: smoker recipes, which are all food, and bread. */
    public static ItemStack cooked(ServerLevel level, ItemStack stack) {
        if (stack.is(Items.WHEAT)) {
            return new ItemStack(Items.BREAD);
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, new SingleRecipeInput(stack), level)
                .map(r -> r.value().getResultItem(level.registryAccess()).copy()).orElse(ItemStack.EMPTY);
    }

    private boolean ingredient(ItemStack s) {
        return !s.isEmpty() && !cooked((ServerLevel) citizen.level(), s).isEmpty();
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return ingredient(stack);
    }

    @Override
    protected int load() {
        return 8; // the town's hungry: bring it while it's warm
    }

    @Override
    protected ColonyStorage deliverTo(ServerLevel level, Colony colony) {
        ColonyStorage hall = ColonyStorage.townHall(level, colony);
        return hall.isEmpty() ? ColonyStorage.deliveries(level, colony) : hall;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        BlockPos from = from(colony);
        stove = WorkSites.cooking(level, colony).stream().min(Comparator.comparingDouble(p -> p.distSqr(from))).orElse(null);
        if (stove == null) {
            return false;
        }
        return cookable() || stores(level, colony).count(this::worthFetching) > 0;
    }

    /** Something in their pockets they can cook now (wheat only by the loaf). */
    private boolean cookable() {
        int wheat = 0;
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            ItemStack s = citizen.inventory().getItem(i);
            if (s.is(Items.WHEAT)) {
                wheat += s.getCount();
            } else if (ingredient(s)) {
                return true;
            }
        }
        return wheat >= WHEAT_PER_BREAD;
    }

    private boolean worthFetching(ItemStack s) {
        return ingredient(s) && (!s.is(Items.WHEAT) || s.getCount() >= WHEAT_PER_BREAD);
    }

    /**
     * Where the ingredients are: the kitchen's own chests, the warehouses (farmers, fishers and the shepherd bring them
     * there) and the town hall.
     */
    private ColonyStorage stores(ServerLevel level, Colony colony) {
        java.util.List<ColonyStorage.Box> boxes = new java.util.ArrayList<>(ColonyStorage.deliveries(level, colony).boxes());
        for (ColonyStorage.Box b : ColonyStorage.townHall(level, colony).boxes()) {
            if (boxes.stream().noneMatch(x -> x.pos().equals(b.pos()))) {
                boxes.add(b);
            }
        }
        return ownFirst(level, colony, ColonyStorage.of(boxes));
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (WorkSites.cooking(level, colony).isEmpty()) {
            return "Nowhere to cook: place a furnace, smoker or campfire on the town's land, or build a kitchen";
        }
        return "Nothing to cook: they cook wheat (three to a loaf), raw meat, fish and potatoes from the town hall or the warehouse";
    }

    @Override
    protected String doingNow() {
        return cookable() ? "Cooking" : "Fetching something to cook";
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (!cookable()) {
            fetch(level, colony);
            return;
        }
        if (!approach.tick(citizen, stove, 2.8, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(stove.getX() + 0.5, stove.getY() + 0.5, stove.getZ() + 0.5);
        level.sendParticles(ParticleTypes.SMOKE, stove.getX() + 0.5, stove.getY() + 1.1, stove.getZ() + 0.5, 2, 0.15, 0.05, 0.15, 0.01);
        if (--cooldown > 0) {
            return;
        }
        cooldown = pace(40);
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            ItemStack s = citizen.inventory().getItem(i);
            if (!ingredient(s) || s.is(Items.WHEAT) && s.getCount() < WHEAT_PER_BREAD) {
                continue;
            }
            ItemStack food = cooked(level, s);
            s.shrink(s.is(Items.WHEAT) ? WHEAT_PER_BREAD : 1);
            pocket(food);
            level.playSound(null, stove, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8f, 1.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.didWork(1);
            return;
        }
    }

    private void fetch(ServerLevel level, Colony colony) {
        ColonyStorage from = stores(level, colony);
        ColonyStorage.Box box = from.find(this::worthFetching);
        if (box == null) {
            return;
        }
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return;
        }
        for (ItemStack s : ColonyStorage.of(box).takeUpTo(this::ingredient, BATCH)) {
            pocket(s);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        approach.reset();
    }
}
