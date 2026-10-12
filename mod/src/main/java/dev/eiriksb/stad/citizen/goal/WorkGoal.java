package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * A day at work for one job: do the job's own work, and when they're carrying a fair load (or there's nothing else to
 * do, or it's late in the day) take what they've gathered to the warehouse, or the town hall when there's none.
 */
abstract class WorkGoal extends Goal {
    protected final CitizenEntity citizen;
    private final String job;
    protected final Movement.Approach approach = new Movement.Approach();
    protected int cooldown;
    private boolean delivering;

    WorkGoal(CitizenEntity citizen, String job) {
        this.citizen = citizen;
        this.job = job;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Whether there's work now (the job's own, besides delivering). */
    protected abstract boolean hasWork(ServerLevel level, Colony colony);

    /** One goal tick of the job's own work. */
    protected abstract void work(ServerLevel level, Colony colony);

    /** What they hang on to instead of delivering (seeds to sow, saplings to plant). */
    protected boolean keep(ItemStack stack) {
        return false;
    }

    private boolean onDuty() {
        return citizen.canWork() && job.equals(citizen.job().orElse(null)) && citizen.level() instanceof ServerLevel;
    }

    private boolean somethingToDeliver() {
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty() && !keep(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** How much they carry that's for delivering. */
    private int carried() {
        int n = 0;
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!keep(inv.getItem(i))) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    /** Late afternoon: bring the day's work in before the evening. */
    private boolean lateInTheDay() {
        return citizen.level().getDayTime() % 24000 > 10000;
    }

    private boolean pocketsFull() {
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canUse() {
        if (!onDuty()) {
            return false;
        }
        Colony colony = citizen.colony().orElse(null);
        return colony != null && (hasWork((ServerLevel) citizen.level(), colony) || somethingToDeliver());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        approach.reset();
        cooldown = 0;
        delivering = false;
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) citizen.level();
        Colony colony = citizen.colony().orElse(null);
        if (colony == null) {
            return;
        }
        if (!delivering && somethingToDeliver() && (pocketsFull() || carried() >= 48 || lateInTheDay() || !hasWork(level, colony))) {
            delivering = true;
            approach.reset();
        }
        if (delivering) {
            deliver(level, colony);
        } else {
            work(level, colony);
        }
    }

    private void deliver(ServerLevel level, Colony colony) {
        ColonyStorage to = ColonyStorage.deliveries(level, colony);
        if (to.isEmpty()) {
            delivering = false;
            return;
        }
        ColonyStorage.Box box = to.boxes().getFirst();
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return;
        }
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !keep(s)) {
                inv.setItem(i, to.put(s));
            }
        }
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        delivering = false;
        approach.reset();
    }

    /** Puts what they gathered in their pockets; what doesn't fit drops at their feet. */
    protected void pocket(ItemStack stack) {
        ItemStack rest = citizen.inventory().addItem(stack);
        if (!rest.isEmpty()) {
            citizen.spawnAtLocation(rest);
        }
    }

    /** Takes one matching item from their pockets. */
    protected boolean usePocketed(java.util.function.Predicate<ItemStack> matches) {
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (matches.test(inv.getItem(i))) {
                inv.getItem(i).shrink(1);
                return true;
            }
        }
        return false;
    }
}
