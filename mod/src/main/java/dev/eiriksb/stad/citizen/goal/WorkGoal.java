package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * A day at work for one job: do the job's own work, and when they're carrying a fair load (or there's nothing else to
 * do, or it's late in the day) take what they've gathered to the warehouse, or the town hall when there's none.
 * Workers with a workplace (PLAN.md §6.8) go there first thing, work the land round it, work a quarter faster, and take
 * their haul in there when the town has a courier to carry it on.
 */
abstract class WorkGoal extends Goal {
    protected final CitizenEntity citizen;
    private final String job;
    protected final Movement.Approach approach = new Movement.Approach();
    protected int cooldown;
    private boolean delivering;
    /** the day they last started at their workplace */
    private long checkedIn = -1;

    WorkGoal(CitizenEntity citizen, String job) {
        this.citizen = citizen;
        this.job = job;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Whether there's work now (the job's own, besides delivering). */
    protected abstract boolean hasWork(ServerLevel level, Colony colony);

    /** One goal tick of the job's own work. */
    protected abstract void work(ServerLevel level, Colony colony);

    /** The tool they hold while at work (a fishing rod, a pickaxe), or nothing. */
    protected ItemStack tool() {
        return ItemStack.EMPTY;
    }

    /** How much they carry before taking it in. */
    protected int load() {
        return 48;
    }

    /** Where they bring what they made: their workplace's chests when there's a courier to carry it on, else the warehouse. */
    protected ColonyStorage deliverTo(ServerLevel level, Colony colony) {
        Optional<Building> at = workplace(colony);
        if (at.isPresent() && colony.citizens().stream().anyMatch(r -> Jobs.COURIER.equals(r.job))) {
            ColonyStorage own = Workplaces.storage(level, at.get());
            if (!own.isEmpty()) {
                return own;
            }
        }
        return ColonyStorage.deliveries(level, colony);
    }

    /** Their workplace, if they have one. */
    protected Optional<Building> workplace(Colony colony) {
        return citizen.record().flatMap(r -> Workplaces.of(colony, r));
    }

    /** Where they work from: their workplace, or where they are. */
    protected BlockPos from(Colony colony) {
        return workplace(colony).map(Workplaces::spot).orElse(citizen.blockPosition());
    }

    /** The work sites within {@code reach} of their workplace, when they have one and there are any; else all of them. */
    protected List<BlockPos> nearWork(Colony colony, List<BlockPos> sites, int reach) {
        Optional<Building> at = workplace(colony);
        if (at.isEmpty()) {
            return sites;
        }
        BlockPos spot = Workplaces.spot(at.get());
        List<BlockPos> near = sites.stream().filter(p -> p.closerThan(spot, reach)).toList();
        return near.isEmpty() ? sites : near;
    }

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
        ItemStack tool = tool();
        if (!tool.isEmpty() && citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty()) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool.copy());
            citizen.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0f); // the town's tool, not loot
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        ItemStack tool = tool();
        if (!tool.isEmpty() && citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).is(tool.getItem())) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) citizen.level();
        Colony colony = citizen.colony().orElse(null);
        if (colony == null) {
            return;
        }
        if (!delivering && somethingToDeliver() && (pocketsFull() || carried() >= load() || lateInTheDay() || !hasWork(level, colony))) {
            delivering = true;
            approach.reset();
        }
        if (delivering) {
            deliver(level, colony);
        } else if (!checkIn(level, colony)) {
            work(level, colony);
        }
    }

    /** First thing in the day, they go to their workplace. Returns whether they're still on their way. */
    private boolean checkIn(ServerLevel level, Colony colony) {
        long day = level.getDayTime() / 24000;
        if (checkedIn == day) {
            return false;
        }
        Optional<Building> at = workplace(colony);
        if (at.isEmpty() || lateInTheDay()) {
            checkedIn = day;
            return false;
        }
        if (approach.tick(citizen, Workplaces.spot(at.get()), 2.5, 0.6)) {
            checkedIn = day;
            approach.reset();
            return false;
        }
        return true;
    }

    private void deliver(ServerLevel level, Colony colony) {
        ColonyStorage to = deliverTo(level, colony);
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

    /** How long something takes: twice as long when they're working grudgingly, a quarter less from a workplace. */
    protected int pace(int ticks) {
        int t = citizen.grudging() ? ticks * 2 : ticks;
        boolean placed = citizen.colony().flatMap(this::workplace).isPresent();
        return placed ? Math.max(1, (t * 3 + 3) / 4) : t;
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
