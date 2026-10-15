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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The smith (PLAN.md §6.9), at the smithy's anvil and furnace: makes the tools the town's workers are short of (and
 * swords and armour for the guards) from what's in the stores, mends worn tools and armour, and smelts the miner's
 * ore in the smithy's real furnace (a blast furnace if it has one), burning the town's coal or charcoal: they load it,
 * get on with other work while it burns, and take out what it's made. What they make goes to the town's stores, where
 * the workers take their tools from.
 */
public class SmithGoal extends WorkGoal {
    enum Kind { MAKE, MEND, LOAD, COLLECT }

    /**
     * What the smith is doing: making {@code target} out of {@code bill}, mending {@code tool} with {@code units} of
     * {@code repair}, loading the furnace with {@code units} of the ore {@code target} (or none: just fuel) and
     * {@code bill}'s fuel, or taking out what it's made.
     */
    record Task(Kind kind, Item target, Map<Item, Integer> bill, ItemStack tool, Item repair, int units) {}

    /** how much ore they put in the furnace at a time */
    static final int ORE_BATCH = 16;
    /** how many items a piece of coal or charcoal smelts (a block of coal, ten times that) */
    static final int PER_COAL = 8;
    /** what armour they make for a guard short of it, best first, by slot */
    static final Map<net.minecraft.world.entity.EquipmentSlot, List<Item>> ARMOUR = Map.of(
            net.minecraft.world.entity.EquipmentSlot.CHEST, List.of(net.minecraft.world.item.Items.IRON_CHESTPLATE, net.minecraft.world.item.Items.LEATHER_CHESTPLATE),
            net.minecraft.world.entity.EquipmentSlot.LEGS, List.of(net.minecraft.world.item.Items.IRON_LEGGINGS, net.minecraft.world.item.Items.LEATHER_LEGGINGS),
            net.minecraft.world.entity.EquipmentSlot.HEAD, List.of(net.minecraft.world.item.Items.IRON_HELMET, net.minecraft.world.item.Items.LEATHER_HELMET),
            net.minecraft.world.entity.EquipmentSlot.FEET, List.of(net.minecraft.world.item.Items.IRON_BOOTS, net.minecraft.world.item.Items.LEATHER_BOOTS));

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
            case LOAD -> s.is(task.target) && task.target != null || Smithing.fuel(s);
            case COLLECT -> false;
        };
    }

    private Optional<BlockPos> find(Colony colony, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what) {
        return workplace(colony).flatMap(Building::placement)
                .flatMap(p -> p.steps().stream().filter(s -> what.test(s.state())).map(s -> s.pos()).findFirst());
    }

    private Optional<BlockPos> anvil(Colony colony) {
        return find(colony, s -> s.getBlock() instanceof AnvilBlock);
    }

    /** The smithy's furnace for ore: a blast furnace if there is one, else a furnace (a smoker only cooks food). */
    private Optional<BlockPos> furnace(Colony colony) {
        Optional<BlockPos> blast = find(colony, s -> s.getBlock() instanceof net.minecraft.world.level.block.BlastFurnaceBlock);
        return blast.isPresent() ? blast : find(colony, s -> s.getBlock() instanceof net.minecraft.world.level.block.FurnaceBlock);
    }

    /** The furnace's block entity, when it's really there. */
    private Optional<AbstractFurnaceBlockEntity> oven(ServerLevel level, Colony colony) {
        return furnace(colony).map(level::getBlockEntity).filter(AbstractFurnaceBlockEntity.class::isInstance).map(AbstractFurnaceBlockEntity.class::cast);
    }

    private static net.minecraft.world.item.crafting.RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> recipes(AbstractFurnaceBlockEntity f) {
        return f instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity ? RecipeType.BLASTING : RecipeType.SMELTING;
    }

    /** Whether this furnace smelts it. */
    private static boolean smeltable(ServerLevel level, AbstractFurnaceBlockEntity f, ItemStack s) {
        return Smithing.ore(s) && level.getRecipeManager().getRecipeFor(recipes(f), new net.minecraft.world.item.crafting.SingleRecipeInput(s), level).isPresent();
    }

    /** Whether the fire's out with something still waiting in it. */
    private static boolean outOfFuel(AbstractFurnaceBlockEntity f) {
        return !f.getItem(SLOT_INPUT).isEmpty() && f.getItem(SLOT_FUEL).isEmpty() && !f.getBlockState().getValue(AbstractFurnaceBlock.LIT);
    }

    private static final int SLOT_INPUT = 0, SLOT_FUEL = 1, SLOT_RESULT = 2;

    /** Where the smith looks: their smithy, the town's stores, and every workplace (where tools are kept). */
    private List<ColonyStorage> stores(ServerLevel level, Colony colony) {
        List<ColonyStorage> out = new ArrayList<>(Tools.stores(level, colony, citizen, workplace(colony)));
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

    /**
     * What most needs doing: taking out what the furnace made and loading it with ore (it works while they do the
     * rest), then tools the town is short of, then armour for the guards, then mending.
     */
    Task plan(ServerLevel level, Colony colony) {
        List<ColonyStorage> stores = stores(level, colony);
        Map<Item, Integer> stock = Smithing.stock(stores);
        Optional<AbstractFurnaceBlockEntity> oven = oven(level, colony);
        if (oven.isPresent()) {
            AbstractFurnaceBlockEntity f = oven.get();
            if (!f.getItem(SLOT_RESULT).isEmpty()) {
                return new Task(Kind.COLLECT, null, Map.of(), ItemStack.EMPTY, null, 0);
            }
            Task load = load(level, f, stores, stock);
            if (load != null) {
                return load;
            }
        }
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
        for (net.minecraft.world.entity.EquipmentSlot slot : dev.eiriksb.stad.war.Combat.ARMOUR) {
            if (dev.eiriksb.stad.war.Combat.armourShortage(level, colony, slot) <= 0) {
                continue;
            }
            for (Item item : ARMOUR.get(slot)) {
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
        return null;
    }

    /**
     * Loading the furnace, when it's empty and there's ore it smelts (a batch of one kind, with the coal to smelt it),
     * or when its fire has gone out with ore still in it (just the coal). Null when there's nothing to load or nothing
     * to burn.
     */
    private Task load(ServerLevel level, AbstractFurnaceBlockEntity f, List<ColonyStorage> stores, Map<Item, Integer> stock) {
        Item ore = null;
        int units = 0;
        if (f.getItem(SLOT_INPUT).isEmpty()) {
            for (Map.Entry<Item, Integer> e : stock.entrySet()) {
                if (smeltable(level, f, new ItemStack(e.getKey()))) {
                    ore = e.getKey();
                    units = Math.min(ORE_BATCH, e.getValue());
                    break;
                }
            }
            if (ore == null) {
                return null;
            }
        } else if (!outOfFuel(f)) {
            return null;
        }
        int toSmelt = units + f.getItem(SLOT_INPUT).getCount();
        ItemStack inFuel = f.getItem(SLOT_FUEL);
        int covered = inFuel.isEmpty() ? 0 : inFuel.getCount() * (inFuel.is(net.minecraft.world.item.Items.COAL_BLOCK) ? PER_COAL * 10 : PER_COAL);
        Map<Item, Integer> fuel = new java.util.HashMap<>();
        if (covered < toSmelt) {
            int need = (toSmelt - covered + PER_COAL - 1) / PER_COAL;
            for (Map.Entry<Item, Integer> e : stock.entrySet()) {
                if (Smithing.fuel(new ItemStack(e.getKey())) && (inFuel.isEmpty() || inFuel.is(e.getKey()))) {
                    int n = e.getKey() == net.minecraft.world.item.Items.COAL_BLOCK ? (need + 9) / 10 : need;
                    fuel.put(e.getKey(), Math.min(n, e.getValue()));
                    break;
                }
            }
            if (fuel.isEmpty() && (inFuel.isEmpty() && !f.getBlockState().getValue(AbstractFurnaceBlock.LIT))) {
                return null; // nothing to burn: see blocked()
            }
        }
        if (ore == null && fuel.isEmpty()) {
            return null;
        }
        return new Task(Kind.LOAD, ore, fuel, ItemStack.EMPTY, null, units);
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
                String who = Tools.whoNeeds(level, colony, k);
                return Character.toUpperCase(who.charAt(0)) + who.substring(1) + " need" + (who.contains(" and ") || who.endsWith("s") ? " " : "s ")
                        + k.words + ", but there's nothing to make one from: put " + materials(k)
                        + " in the town hall";
            }
        }
        Optional<AbstractFurnaceBlockEntity> oven = oven(level, colony);
        if (oven.isPresent() && !oven.get().getBlockState().getValue(AbstractFurnaceBlock.LIT) && oven.get().getItem(SLOT_FUEL).isEmpty()) {
            Map<Item, Integer> stock = Smithing.stock(stores(level, colony));
            boolean ore = outOfFuel(oven.get()) || stock.keySet().stream().anyMatch(i -> smeltable(level, oven.get(), new ItemStack(i)));
            if (ore && stock.keySet().stream().noneMatch(i -> Smithing.fuel(new ItemStack(i)))) {
                return "There's ore to smelt, but nothing to burn: put coal or charcoal in the town hall";
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
        return oven(level, colony).filter(f -> !f.getItem(SLOT_INPUT).isEmpty()).isPresent() ? "Waiting for the furnace" : "Nothing to make or mend";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "At the anvil";
        }
        return switch (task.kind()) {
            case MAKE -> "Making " + task.target().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
            case MEND -> "Mending " + task.tool().getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            case LOAD -> task.target() == null ? "Stoking the furnace" : "Putting " + task.target().getDescription().getString().toLowerCase(java.util.Locale.ROOT) + " in the furnace";
            case COLLECT -> "Taking what the furnace made";
        };
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        if (!fetched && task.kind != Kind.COLLECT) {
            fetch(level, colony);
            return;
        }
        if (task.kind == Kind.COLLECT && !fetched) {
            fetched = true; // nothing to fetch: straight to the furnace
        }
        boolean atFurnace = task.kind == Kind.LOAD || task.kind == Kind.COLLECT;
        Optional<BlockPos> at = atFurnace ? furnace(colony) : anvil(colony);
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
                level.sendParticles(atFurnace ? ParticleTypes.SMOKE : ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 1.1,
                        p.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.02);
            }
            return;
        }
        switch (task.kind) {
            case MAKE -> make(level, p);
            case MEND -> mend(level, p);
            case LOAD -> load(level, p);
            case COLLECT -> collect(level, p);
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
            case LOAD -> stores.find(s -> Smithing.plain(s) && (task.target != null && s.is(task.target) || task.bill.containsKey(s.getItem())));
            case COLLECT -> null;
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
            case LOAD -> {
                if (task.target != null) {
                    stores.takeUpTo(s -> Smithing.plain(s) && s.is(task.target), task.units).forEach(this::pocket);
                }
                for (Map.Entry<Item, Integer> e : task.bill.entrySet()) {
                    stores.takeUpTo(s -> Smithing.plain(s) && s.is(e.getKey()), e.getValue()).forEach(this::pocket);
                }
            }
            case COLLECT -> {}
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        fetched = true;
        cooldown = pace(task.kind == Kind.LOAD ? 20 : task.kind == Kind.MEND ? 60 : 80);
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

    /** Puts the ore and the fuel they brought in the furnace (what doesn't fit goes back to the stores with the rest). */
    private void load(ServerLevel level, BlockPos furnace) {
        if (level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f) {
            SimpleContainer inv = citizen.inventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (task.target != null && s.is(task.target) && smeltable(level, f, s)) {
                    inv.setItem(i, into(f, SLOT_INPUT, s));
                } else if (Smithing.fuel(s) && task.bill.containsKey(s.getItem())) {
                    inv.setItem(i, into(f, SLOT_FUEL, s));
                }
            }
            f.setChanged();
            level.playSound(null, furnace, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.5f, 1.0f);
            worked(1);
        }
        done();
    }

    /** Puts what fits of a stack into a furnace slot; returns the rest. */
    private static ItemStack into(AbstractFurnaceBlockEntity f, int slot, ItemStack s) {
        ItemStack there = f.getItem(slot);
        if (there.isEmpty()) {
            f.setItem(slot, s);
            return ItemStack.EMPTY;
        }
        if (!ItemStack.isSameItemSameComponents(there, s)) {
            return s;
        }
        int n = Math.min(s.getCount(), there.getMaxStackSize() - there.getCount());
        there.grow(n);
        s.shrink(n);
        return s.isEmpty() ? ItemStack.EMPTY : s;
    }

    /** Takes out what the furnace has made, to go to the stores. */
    private void collect(ServerLevel level, BlockPos furnace) {
        if (level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity f && !f.getItem(SLOT_RESULT).isEmpty()) {
            ItemStack out = f.removeItem(SLOT_RESULT, f.getItem(SLOT_RESULT).getCount());
            pocket(out);
            f.setChanged();
            level.playSound(null, furnace, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.5f, 1.0f);
            worked(out.getCount());
        }
        done();
    }
}
