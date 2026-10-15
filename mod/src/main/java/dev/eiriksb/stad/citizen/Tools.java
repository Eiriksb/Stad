package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SwordItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The town's tools (PLAN.md §6.9): which job needs which, taking the best one from the stores, wearing it out, and
 * putting it back in the workplace at the end of the day. A worker without one makes do by hand, slower. Guards'
 * swords are {@link dev.eiriksb.stad.war.Combat}'s. Server thread.
 */
public final class Tools {
    private Tools() {}

    public enum Kind {
        HOE("a hoe", Items.STONE_HOE, List.of(Items.IRON_HOE, Items.STONE_HOE, Items.WOODEN_HOE)),
        AXE("an axe", Items.STONE_AXE, List.of(Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)),
        PICKAXE("a pickaxe", Items.STONE_PICKAXE, List.of(Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE)),
        FISHING_ROD("a fishing rod", Items.FISHING_ROD, List.of(Items.FISHING_ROD)),
        SHEARS("shears", Items.SHEARS, List.of(Items.SHEARS)),
        SHOVEL("a shovel", Items.STONE_SHOVEL, List.of(Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL)),
        SWORD("a sword", Items.IRON_SWORD, List.of(Items.IRON_SWORD, Items.STONE_SWORD));

        public final String words;
        /** what the town asks for when it has none */
        public final Item basic;
        /** what a smith can make, best first */
        public final List<Item> made;

        Kind(String words, Item basic, List<Item> made) {
            this.words = words;
            this.basic = basic;
            this.made = made;
        }

        public boolean matches(ItemStack s) {
            Item i = s.getItem();
            return switch (this) {
                case HOE -> i instanceof HoeItem;
                case AXE -> i instanceof AxeItem;
                case PICKAXE -> i instanceof PickaxeItem;
                case FISHING_ROD -> i instanceof FishingRodItem;
                case SHEARS -> i instanceof ShearsItem;
                case SHOVEL -> i instanceof net.minecraft.world.item.ShovelItem;
                case SWORD -> i instanceof SwordItem;
            };
        }
    }

    /** The tool a job needs, or null. */
    public static Kind of(String job) {
        if (job == null) {
            return null;
        }
        return switch (job) {
            case Jobs.FARMER -> Kind.HOE;
            case Jobs.LUMBERJACK -> Kind.AXE;
            case Jobs.MINER -> Kind.PICKAXE;
            case Jobs.FISHER -> Kind.FISHING_ROD;
            case Jobs.SHEPHERD -> Kind.SHEARS;
            case Jobs.GUARD -> Kind.SWORD;
            case Jobs.CARPENTER -> Kind.AXE;
            case Jobs.MASON -> Kind.PICKAXE;
            case Jobs.WEAVER, Jobs.BEEKEEPER -> Kind.SHEARS;
            case Jobs.GATHERER -> Kind.SHOVEL;
            default -> null;
        };
    }

    /**
     * Who's short of a kind of tool, in words: "the carpenter and the lumberjacks" (each job once, plural when there's
     * more than one of them); the job that mostly uses it when nobody is.
     */
    public static String whoNeeds(ServerLevel level, Colony colony, Kind k) {
        java.util.Map<String, Integer> jobs = new java.util.LinkedHashMap<>();
        for (CitizenRecord r : colony.citizens()) {
            if (of(r.job) == k && level.getEntity(r.uuid) instanceof CitizenEntity e && !k.matches(e.getItemBySlot(EquipmentSlot.MAINHAND))) {
                jobs.merge(r.job, 1, Integer::sum);
            }
        }
        if (jobs.isEmpty()) {
            return "the " + jobOf(k) + "s";
        }
        List<String> words = new ArrayList<>();
        jobs.forEach((j, n) -> words.add("the " + j + (n > 1 ? "s" : "")));
        return dev.eiriksb.stad.planner.Design.join(words);
    }

    /** The job that mostly uses a kind of tool. */
    public static String jobOf(Kind k) {
        return switch (k) {
            case HOE -> Jobs.FARMER;
            case AXE -> Jobs.LUMBERJACK;
            case PICKAXE -> Jobs.MINER;
            case FISHING_ROD -> Jobs.FISHER;
            case SHEARS -> Jobs.SHEPHERD;
            case SHOVEL -> Jobs.GATHERER;
            case SWORD -> Jobs.GUARD;
        };
    }

    /** Whether something is a tool of any kind. */
    public static Kind kindOf(ItemStack s) {
        for (Kind k : Kind.values()) {
            if (!s.isEmpty() && k.matches(s)) {
                return k;
            }
        }
        return null;
    }

    /** Whether they hold a real tool for their job. */
    public static boolean equipped(CitizenEntity c) {
        Kind k = of(c.job().orElse(null));
        return k != null && k.matches(c.getItemBySlot(EquipmentSlot.MAINHAND));
    }

    /** Whether their job needs a tool they don't have. */
    public static boolean lacking(CitizenEntity c) {
        Kind k = of(c.job().orElse(null));
        return k != null && k != Kind.SWORD && !k.matches(c.getItemBySlot(EquipmentSlot.MAINHAND));
    }

    /** Where a worker looks for a tool or supplies: their own things, their workplace, then the town hall, then the warehouses. */
    public static List<ColonyStorage> stores(ServerLevel level, Colony colony, CitizenEntity c, Optional<Building> workplace) {
        List<ColonyStorage> out = new ArrayList<>();
        out.add(ColonyStorage.of(c.ownBox()));
        out.addAll(stores(level, colony, workplace));
        return out;
    }

    /** The town's places for tools: the workplace, then the town hall, then the warehouses. */
    public static List<ColonyStorage> stores(ServerLevel level, Colony colony, Optional<Building> workplace) {
        List<ColonyStorage> out = new ArrayList<>();
        workplace.ifPresent(b -> out.add(Workplaces.storage(level, b)));
        out.add(ColonyStorage.supply(level, colony));
        out.add(ColonyStorage.warehouses(level, colony));
        return out;
    }

    /** How good a tool is: the toughest lasts longest (iron over stone over wood). */
    static int rank(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue() / 2;
    }

    /** Takes the best tool for their job from the stores into their hand; returns whether they have one now. */
    public static boolean equip(CitizenEntity c, ServerLevel level, Colony colony) {
        Kind k = of(c.job().orElse(null));
        if (k == null || k == Kind.SWORD) {
            return false;
        }
        if (equipped(c)) {
            return true;
        }
        Optional<Building> workplace = c.record().flatMap(r -> Workplaces.of(colony, r));
        ColonyStorage best = null;
        ItemStack bestTool = ItemStack.EMPTY;
        for (ColonyStorage s : stores(level, colony, c, workplace)) {
            if (best != null && best.boxes().getFirst().container() == c.belongings()) {
                break; // one of their own: that's the one they use
            }
            for (ColonyStorage.Box b : s.boxes()) {
                for (int i = 0; i < b.container().getContainerSize(); i++) {
                    ItemStack st = b.container().getItem(i);
                    if (k.matches(st) && (bestTool.isEmpty() || rank(st) > rank(bestTool))) {
                        bestTool = st;
                        best = ColonyStorage.of(b);
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        ItemStack want = bestTool;
        List<ItemStack> got = best.takeUpTo(st -> st == want, 1);
        if (got.isEmpty()) {
            return false;
        }
        ItemStack held = c.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty()) {
            c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            putBack(c, level, colony, held);
        }
        c.setItemSlot(EquipmentSlot.MAINHAND, got.getFirst());
        c.setDropChance(EquipmentSlot.MAINHAND, 1.0f);
        return true;
    }

    /** One use of the tool in their hand (it breaks when it's worn through). */
    public static void wear(CitizenEntity c) {
        if (equipped(c)) {
            c.getItemBySlot(EquipmentSlot.MAINHAND).hurtAndBreak(1, c, EquipmentSlot.MAINHAND);
        }
    }

    /** Puts something back: in their workplace when there's room, else wherever the town keeps things. */
    private static void putBack(CitizenEntity c, ServerLevel level, Colony colony, ItemStack stack) {
        ItemStack rest = stack;
        Optional<Building> workplace = c.record().flatMap(r -> Workplaces.of(colony, r));
        if (workplace.isPresent()) {
            rest = Workplaces.storage(level, workplace.get()).put(rest);
        }
        if (!rest.isEmpty()) {
            rest = ColonyStorage.deliveries(level, colony).put(rest);
        }
        if (!rest.isEmpty()) {
            c.spawnAtLocation(rest);
        }
    }

    /**
     * Every few seconds: a tool that isn't for their job goes back to the town, and at night their tool goes back to
     * their workplace (where they'll pick it up in the morning).
     */
    static void tidy(CitizenEntity c) {
        tidy(c, c.isNight());
    }

    /** {@link #tidy(CitizenEntity)}, as if it were night or not (for tests: the clock is the whole server's). */
    public static void tidy(CitizenEntity c, boolean night) {
        if (!(c.level() instanceof ServerLevel level)) {
            return;
        }
        Colony colony = c.colony().orElse(null);
        ItemStack held = c.getItemBySlot(EquipmentSlot.MAINHAND);
        Kind k = kindOf(held);
        if (colony == null || k == null || k == Kind.SWORD) {
            return;
        }
        if (Jobs.SMITH.equals(c.job().orElse(null))) {
            return; // what a smith holds, they're mending
        }
        Kind mine = of(c.job().orElse(null));
        boolean nightAtWork = night && c.record().flatMap(r -> Workplaces.of(colony, r)).isPresent();
        if (k != mine || nightAtWork) {
            c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            putBack(c, level, colony, held);
        }
    }

    /** How many tools of a kind the town's workers are short of (workers without one, less spares in the stores). */
    public static int shortage(ServerLevel level, Colony colony, Kind k) {
        int lacking = 0;
        for (CitizenRecord r : colony.citizens()) {
            if (of(r.job) == k && level.getEntity(r.uuid) instanceof CitizenEntity e && !k.matches(e.getItemBySlot(EquipmentSlot.MAINHAND))
                    && (k != Kind.SWORD || dev.eiriksb.stad.war.Combat.unarmed(e))) {
                lacking++;
            }
        }
        if (lacking == 0) {
            return 0;
        }
        int spare = 0;
        List<ColonyStorage> all = new ArrayList<>(stores(level, colony, Optional.empty()));
        for (Building b : colony.buildings()) {
            if (Workplaces.isWorkplace(b) && b.done()) {
                all.add(Workplaces.storage(level, b));
            }
        }
        for (ColonyStorage s : all) {
            spare += s.count(k::matches);
        }
        return Math.max(0, lacking - spare);
    }

    /** The worst-worn tool first. */
    static final Comparator<ItemStack> MOST_WORN = Comparator.comparingDouble(s -> -(double) s.getDamageValue() / Math.max(1, s.getMaxDamage()));
}
