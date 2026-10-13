package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Smithing;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The smith (PLAN.md §6.9), at the smithy's anvil and furnace: makes the tools the town's workers are short of (and
 * swords for unarmed guards) from what's in the stores, mends worn tools, and smelts the miner's ore. What they make
 * goes to the town's stores, where the workers take their tools from.
 */
public class SmithGoal extends WorkGoal {
    enum Kind { MAKE, MEND, SMELT }

    /** What the smith is doing: making {@code target} out of {@code bill}, mending {@code tool} with {@code units} of {@code repair}, or smelting. */
    record Task(Kind kind, Item target, Map<Item, Integer> bill, ItemStack tool, Item repair, int units) {}

    /** how much ore they fetch at a time */
    static final int ORE_BATCH = 16;

    private Task task;
    private boolean fetched;
    private int planIn;

    public SmithGoal(CitizenEntity citizen) {
        super(citizen, Jobs.SMITH);
    }

    @Override
    protected ColonyStorage deliverTo(ServerLevel level, Colony colony) {
        return ColonyStorage.deliveries(level, colony); // where the workers look for tools
    }

    @Override
    protected boolean keep(ItemStack s) {
        if (task == null || s.isEmpty()) {
            return false;
        }
        return switch (task.kind) {
            case MAKE -> task.bill.containsKey(s.getItem());
            case MEND -> s.is(task.repair);
            case SMELT -> Smithing.ore(s);
        };
    }

    private Optional<BlockPos> find(Colony colony, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what) {
        return workplace(colony).flatMap(Building::placement)
                .flatMap(p -> p.steps().stream().filter(s -> what.test(s.state())).map(s -> s.pos()).findFirst());
    }

    private Optional<BlockPos> anvil(Colony colony) {
        return find(colony, s -> s.getBlock() instanceof AnvilBlock);
    }

    private Optional<BlockPos> furnace(Colony colony) {
        return find(colony, s -> s.getBlock() instanceof AbstractFurnaceBlock);
    }

    /** Where the smith looks: their smithy, the town's stores, and every workplace (where tools are kept). */
    private List<ColonyStorage> stores(ServerLevel level, Colony colony) {
        List<ColonyStorage> out = new ArrayList<>(Tools.stores(level, colony, workplace(colony)));
        for (Building b : colony.buildings()) {
            if (b.done() && dev.eiriksb.stad.colony.Workplaces.isWorkplace(b) && !workplace(colony).map(w -> w.id == b.id).orElse(false)) {
                out.add(dev.eiriksb.stad.colony.Workplaces.storage(level, b));
            }
        }
        return out;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty() || anvil(colony).isEmpty()) {
            task = null;
            return false;
        }
        if (task != null && fetched) {
            return true;
        }
        if (--planIn <= 0) {
            planIn = 50;
            task = plan(level, colony);
            fetched = false;
        }
        return task != null;
    }

    /** What most needs doing: tools the town is short of, then mending, then smelting. */
    Task plan(ServerLevel level, Colony colony) {
        List<ColonyStorage> stores = stores(level, colony);
        Map<Item, Integer> stock = Smithing.stock(stores);
        for (Tools.Kind k : Tools.Kind.values()) {
            if (Tools.shortage(level, colony, k) <= 0) {
                continue;
            }
            for (Item item : k.made) {
                Map<Item, Integer> bill = Smithing.bill(level, stock, item);
                if (bill != null) {
                    return new Task(Kind.MAKE, item, bill, ItemStack.EMPTY, null, 0);
                }
            }
        }
        List<ItemStack> worn = new ArrayList<>();
        for (ColonyStorage s : stores) {
            for (ColonyStorage.Box b : s.boxes()) {
                for (int i = 0; i < b.container().getContainerSize(); i++) {
                    ItemStack st = b.container().getItem(i);
                    if (st.isDamageableItem() && st.getDamageValue() >= st.getMaxDamage() / 4) {
                        worn.add(st);
                    }
                }
            }
        }
        worn.sort(Comparator.comparingDouble(st -> -(double) st.getDamageValue() / st.getMaxDamage()));
        for (ItemStack tool : worn) {
            Item repair = Smithing.repairWith(tool, stock);
            if (repair != null) {
                int units = Math.min(stock.get(repair), (int) Math.ceil(tool.getDamageValue() / (tool.getMaxDamage() / 4.0)));
                return new Task(Kind.MEND, tool.getItem(), Map.of(), tool, repair, Math.max(1, units));
            }
        }
        if (furnace(colony).isPresent()) {
            for (ColonyStorage s : stores) {
                ColonyStorage.Box b = s.find(st -> Smithing.ore(st) && !Smithing.smelted(level, st).isEmpty());
                if (b != null) {
                    return new Task(Kind.SMELT, null, Map.of(), ItemStack.EMPTY, null, 0);
                }
            }
        }
        return null;
    }

    @Override
    public void stop() {
        super.stop();
        fetched = false;
        putDownTool();
    }

    /** The tool they were mending goes in their pockets (to go back to the stores). */
    private void putDownTool() {
        ItemStack held = citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && held.isDamageableItem()) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            pocket(held);
        }
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty() || anvil(colony).isEmpty()) {
            return "No smithy to work in: build one (an anvil, a furnace and a chest)";
        }
        for (Tools.Kind k : Tools.Kind.values()) {
            if (Tools.shortage(level, colony, k) > 0) {
                return "The " + Tools.jobOf(k) + "s need " + k.words + ", but there's nothing to make one from: put " + materials(k)
                        + " in the town hall";
            }
        }
        return null;
    }

    /** What a kind of tool is made from, in words. */
    private static String materials(Tools.Kind k) {
        return switch (k) {
            case FISHING_ROD -> "sticks and string";
            case SHEARS -> "iron ingots";
            default -> "cobblestone or iron ingots, and planks";
        };
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Nothing to make or mend";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "At the anvil";
        }
        return switch (task.kind()) {
            case MAKE -> "Making " + task.target().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
            case MEND -> "Mending " + task.tool().getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            case SMELT -> "Smelting ore";
        };
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        if (!fetched) {
            fetch(level, colony);
            return;
        }
        Optional<BlockPos> at = task.kind == Kind.SMELT ? furnace(colony) : anvil(colony);
        if (at.isEmpty()) {
            task = null;
            return;
        }
        BlockPos p = at.get();
        if (!approach.tick(citizen, p, 2.8, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        if (--cooldown > 0) {
            if (cooldown % 10 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                level.sendParticles(task.kind == Kind.SMELT ? ParticleTypes.SMOKE : ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 1.1,
                        p.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.02);
            }
            return;
        }
        switch (task.kind) {
            case MAKE -> make(level, p);
            case MEND -> mend(level, p);
            case SMELT -> smelt(level, p);
        }
    }

    /** To the stores for what the task takes. */
    private void fetch(ServerLevel level, Colony colony) {
        List<ColonyStorage.Box> all = new ArrayList<>();
        stores(level, colony).forEach(s -> all.addAll(s.boxes()));
        ColonyStorage stores = ColonyStorage.of(all);
        ColonyStorage.Box box = switch (task.kind) {
            case MAKE -> stores.find(s -> Smithing.plain(s) && task.bill.containsKey(s.getItem()));
            case MEND -> stores.find(s -> s == task.tool);
            case SMELT -> stores.find(Smithing::ore);
        };
        if (box == null) {
            task = null;
            return;
        }
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return;
        }
        switch (task.kind) {
            case MAKE -> {
                for (Map.Entry<Item, Integer> e : task.bill.entrySet()) {
                    stores.takeUpTo(s -> Smithing.plain(s) && s.is(e.getKey()), e.getValue()).forEach(this::pocket);
                }
                if (!has(task.bill)) {
                    task = null; // someone took it first: what they got goes back
                    return;
                }
            }
            case MEND -> {
                List<ItemStack> tool = stores.takeUpTo(s -> s == task.tool, 1);
                if (tool.isEmpty()) {
                    task = null;
                    return;
                }
                ItemStack t = tool.getFirst();
                task = new Task(Kind.MEND, task.target, Map.of(), t, task.repair, task.units);
                citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, t); // in hand at the anvil
                stores.takeUpTo(s -> Smithing.plain(s) && s.is(task.repair), task.units).forEach(this::pocket);
            }
            case SMELT -> stores.takeUpTo(s -> Smithing.ore(s) && !Smithing.smelted(level, s).isEmpty(), ORE_BATCH).forEach(this::pocket);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        fetched = true;
        cooldown = pace(task.kind == Kind.SMELT ? 40 : task.kind == Kind.MEND ? 60 : 80);
        approach.reset();
    }

    /** Whether their pockets hold all of a bill. */
    private boolean has(Map<Item, Integer> bill) {
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            if (count(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private int count(Item item) {
        int n = 0;
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    private void use(Item item, int n) {
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                int k = Math.min(n, s.getCount());
                s.shrink(k);
                n -= k;
            }
        }
    }

    private void done() {
        task = null;
        fetched = false;
        planIn = 0;
        approach.reset();
    }

    private void make(ServerLevel level, BlockPos anvil) {
        if (has(task.bill)) {
            task.bill.forEach(this::use);
            pocket(new ItemStack(task.target));
            level.playSound(null, anvil, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.0f);
            worked(2);
        }
        done();
    }

    private void mend(ServerLevel level, BlockPos anvil) {
        int units = Math.min(task.units, count(task.repair));
        if (units > 0) {
            use(task.repair, units);
            ItemStack t = citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            t.setDamageValue(Math.max(0, t.getDamageValue() - units * t.getMaxDamage() / 4));
            level.playSound(null, anvil, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.2f);
            worked(1);
        }
        putDownTool();
        done();
    }

    private void smelt(ServerLevel level, BlockPos furnace) {
        SimpleContainer inv = citizen.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            ItemStack out = Smithing.ore(s) ? Smithing.smelted(level, s) : ItemStack.EMPTY;
            if (!out.isEmpty()) {
                s.shrink(1);
                pocket(out);
                level.playSound(null, furnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8f, 1.0f);
                worked(1);
                cooldown = pace(40);
                return;
            }
        }
        done();
    }
}
