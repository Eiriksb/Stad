package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Crafts;
import dev.eiriksb.stad.citizen.Smithing;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Request;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The carpenter, the mason and the weaver (PLAN.md §6.12), at their workplace's bench: they make what the town has asked
 * for that their trade makes (the builders' shortages), else top up the trade's stock of the basics ({@link Crafts}),
 * from what's in the stores. What they make goes to the town hall, where the builders take their materials.
 */
public class CrafterGoal extends WorkGoal {
    private final Crafts.Trade trade;
    private Crafts.Make task;
    private boolean fetched;
    private int planIn;

    public CrafterGoal(CitizenEntity citizen, Crafts.Trade trade) {
        super(citizen, trade.job());
        this.trade = trade;
    }

    @Override
    protected ColonyStorage deliverTo(ServerLevel level, Colony colony) {
        return ColonyStorage.deliveries(level, colony); // where the builders look
    }

    @Override
    protected boolean keep(ItemStack s) {
        return task != null && !s.isEmpty() && task.bill().containsKey(s.getItem()) && !s.is(task.target());
    }

    private Optional<BlockPos> find(Colony colony, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> what) {
        return workplace(colony).flatMap(Building::placement)
                .flatMap(p -> p.steps().stream().filter(s -> what.test(s.state())).map(s -> s.pos()).findFirst());
    }

    private Optional<BlockPos> bench(Colony colony) {
        return find(colony, trade.bench());
    }

    private Optional<BlockPos> furnace(Colony colony) {
        return find(colony, s -> s.getBlock() instanceof AbstractFurnaceBlock);
    }

    private List<ColonyStorage> stores(ServerLevel level, Colony colony) {
        return Tools.stores(level, colony, workplace(colony));
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty() || bench(colony).isEmpty()) {
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

    /** What's in the stores of a group of items. */
    private static int have(Map<Item, Integer> stock, java.util.function.Predicate<Item> group) {
        return stock.entrySet().stream().filter(e -> group.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
    }

    /** Whether {@code storage} has room for some of {@code item}: a free slot, or a stack of it that isn't full. */
    private static boolean roomFor(ColonyStorage storage, Item item) {
        for (ColonyStorage.Box b : storage.boxes()) {
            for (int i = 0; i < b.container().getContainerSize(); i++) {
                ItemStack s = b.container().getItem(i);
                if (s.isEmpty() || s.is(item) && s.getCount() < s.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** set when the last plan found something to make but nowhere to put it */
    private boolean full;

    /** What most needs making (and there's room to put away): what the town has asked for, then the stock of the basics. */
    Crafts.Make plan(ServerLevel level, Colony colony) {
        Crafts.Make m = choose(level, colony);
        ColonyStorage to = deliverTo(level, colony);
        full = m != null && (to.isEmpty() || !roomFor(to, m.target()));
        int free = 0;
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            free += citizen.inventory().getItem(i).isEmpty() ? 1 : 0;
        }
        if (m != null && (full || free < (m.yield() + m.target().getDefaultMaxStackSize() - 1) / m.target().getDefaultMaxStackSize())) {
            return null; // take in what they're carrying first, or wait for room
        }
        return m;
    }

    private Crafts.Make choose(ServerLevel level, Colony colony) {
        Map<Item, Integer> stock = new java.util.HashMap<>(Smithing.stock(stores(level, colony)));
        SimpleContainer pockets = citizen.inventory(); // what they've made and not taken in yet counts too
        for (int i = 0; i < pockets.getContainerSize(); i++) {
            if (Smithing.plain(pockets.getItem(i))) {
                stock.merge(pockets.getItem(i).getItem(), pockets.getItem(i).getCount(), Integer::sum);
            }
        }
        boolean furnace = furnace(colony).isPresent();
        for (Request r : colony.requests()) {
            if (Request.FOOD.equals(r.what)) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(r.what);
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            int short_ = r.count - stock.getOrDefault(item, 0);
            if (item == Items.AIR || short_ <= 0 || !trade.makes().test(item)) {
                continue;
            }
            Crafts.Make m = Crafts.how(level, trade, stock, item, short_, furnace);
            if (m != null) {
                return m;
            }
        }
        for (Crafts.Stock k : trade.keep()) {
            int want = k.count() - have(stock, k.group());
            if (want <= 0) {
                continue;
            }
            for (Item item : Crafts.items(k.group())) {
                Crafts.Make m = Crafts.how(level, trade, stock, item, want, furnace);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty() || bench(colony).isEmpty()) {
            return "No " + trade.buildingWords().replaceFirst("^an? ", "") + " to work in: build one";
        }
        if (full) {
            return "The town hall is full: nowhere to put what they make (build a warehouse, or empty the town hall)";
        }
        Map<Item, Integer> stock = Smithing.stock(stores(level, colony));
        for (Crafts.Stock k : trade.keep()) {
            if (have(stock, k.group()) < k.count()) {
                return "Nothing to make " + k.words() + " from: put " + trade.raw() + " in the town hall";
            }
        }
        return null;
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Nothing to make: the town has the " + String.join(" and ", trade.keep().stream().map(Crafts.Stock::words).toList()) + " it needs";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "At the " + trade.benchWords();
        }
        String what = task.target().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
        return switch (task.method()) {
            case CUT -> "Cutting " + what;
            case SMELT -> "Burning " + what;
            default -> "Making " + what;
        };
    }

    @Override
    public void stop() {
        super.stop();
        fetched = false;
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
        Optional<BlockPos> at = task.method() == Crafts.Method.SMELT ? furnace(colony) : bench(colony);
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
                level.sendParticles(task.method() == Crafts.Method.SMELT ? ParticleTypes.SMOKE : ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 1.1,
                        p.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.02);
            }
            return;
        }
        make(level, p);
    }

    /** To the stores for what the batch takes. */
    private void fetch(ServerLevel level, Colony colony) {
        List<ColonyStorage.Box> all = new ArrayList<>();
        stores(level, colony).forEach(s -> all.addAll(s.boxes()));
        ColonyStorage stores = ColonyStorage.of(all);
        ColonyStorage.Box box = stores.find(s -> Smithing.plain(s) && task.bill().containsKey(s.getItem()));
        if (box == null) {
            task = null;
            return;
        }
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return;
        }
        for (Map.Entry<Item, Integer> e : task.bill().entrySet()) {
            int short_ = e.getValue() - count(e.getKey());
            if (short_ > 0) {
                stores.takeUpTo(s -> Smithing.plain(s) && s.is(e.getKey()), short_).forEach(this::pocket);
            }
        }
        if (!has(task.bill())) {
            task = null; // someone took it first: what they got goes back
            return;
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        fetched = true;
        cooldown = pace(task.method() == Crafts.Method.SMELT ? 30 + 10 * task.bill().values().stream().mapToInt(Integer::intValue).sum() : 60);
        approach.reset();
    }

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

    private void make(ServerLevel level, BlockPos at) {
        if (has(task.bill())) {
            task.bill().forEach(this::use);
            int left = task.yield();
            while (left > 0) {
                ItemStack out = new ItemStack(task.target(), Math.min(left, task.target().getDefaultMaxStackSize()));
                left -= out.getCount();
                pocket(out);
            }
            level.playSound(null, at, switch (task.method()) {
                case CUT -> SoundEvents.UI_STONECUTTER_TAKE_RESULT;
                case SMELT -> SoundEvents.FURNACE_FIRE_CRACKLE;
                default -> trade == Crafts.WEAVER ? SoundEvents.UI_LOOM_TAKE_RESULT : SoundEvents.WOOD_PLACE;
            }, SoundSource.BLOCKS, 0.7f, 1.0f);
            worked(Math.max(1, task.yield() / 4));
        }
        task = null;
        fetched = false;
        planIn = 0;
        approach.reset();
    }
}
