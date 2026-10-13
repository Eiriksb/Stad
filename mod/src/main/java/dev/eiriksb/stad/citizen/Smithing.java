package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.building.ColonyStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the smith works from (PLAN.md §6.9): the game's own crafting recipes, worked out against what's in the town's
 * stores (making the sticks or planks a recipe needs on the way, if it has to); what mends a tool; what ore smelts into.
 */
public final class Smithing {
    private Smithing() {}

    /** How deep the smith goes making what a recipe needs (sticks from planks, planks from logs). */
    private static final int DEPTH = 2;

    /** What's in the stores, by item (only plain things: nothing enchanted, named or worn). */
    public static Map<Item, Integer> stock(List<ColonyStorage> stores) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (ColonyStorage s : stores) {
            for (ColonyStorage.Box b : s.boxes()) {
                for (int i = 0; i < b.container().getContainerSize(); i++) {
                    ItemStack st = b.container().getItem(i);
                    if (plain(st)) {
                        m.merge(st.getItem(), st.getCount(), Integer::sum);
                    }
                }
            }
        }
        return m;
    }

    public static boolean plain(ItemStack s) {
        return !s.isEmpty() && s.getComponentsPatch().isEmpty();
    }

    /** recipes by what they make, for the recipe manager they came from (a reload makes a new one) */
    private static Object cachedFor;
    private static final Map<Item, List<CraftingRecipe>> cache = new HashMap<>();

    private static List<CraftingRecipe> recipes(ServerLevel level, Item target) {
        if (cachedFor != level.getRecipeManager()) {
            cachedFor = level.getRecipeManager();
            cache.clear();
        }
        return cache.computeIfAbsent(target, t -> {
            List<CraftingRecipe> out = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> r : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                if (r.value().getResultItem(level.registryAccess()).is(t) && r.value().getIngredients().stream().anyMatch(i -> !i.isEmpty())) {
                    out.add(r.value());
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * What to take from the stores to make one {@code target}: the raw things, by item. Null when the stores don't
     * have what it takes.
     */
    public static Map<Item, Integer> bill(ServerLevel level, Map<Item, Integer> stock, Item target) {
        Pools p = new Pools(new HashMap<>(stock), new HashMap<>(), new HashMap<>());
        return make(level, p, target, DEPTH) ? p.take : null;
    }

    /** What's left in the stores, what's been taken from them, and the spares of what was made on the way. */
    private record Pools(Map<Item, Integer> left, Map<Item, Integer> take, Map<Item, Integer> made) {
        Pools copy() {
            return new Pools(new HashMap<>(left), new HashMap<>(take), new HashMap<>(made));
        }

        void set(Pools o) {
            left.clear();
            left.putAll(o.left);
            take.clear();
            take.putAll(o.take);
            made.clear();
            made.putAll(o.made);
        }
    }

    /** Makes one {@code target} by any of its recipes; on success the pools say what it took. */
    private static boolean make(ServerLevel level, Pools pools, Item target, int depth) {
        for (CraftingRecipe r : recipes(level, target)) {
            Pools p = pools.copy();
            if (makeBy(level, p, r, depth)) {
                int n = r.getResultItem(level.registryAccess()).getCount();
                p.made.merge(target, n, Integer::sum);
                pools.set(p);
                return true;
            }
        }
        return false;
    }

    private static boolean makeBy(ServerLevel level, Pools p, CraftingRecipe r, int depth) {
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) {
                continue;
            }
            Item spare = first(p.made, ing);
            if (spare != null) {
                p.made.merge(spare, -1, Integer::sum);
                continue;
            }
            Item have = first(p.left, ing);
            if (have != null) {
                p.left.merge(have, -1, Integer::sum);
                p.take.merge(have, 1, Integer::sum);
                continue;
            }
            boolean ok = false;
            for (ItemStack option : depth > 0 ? ing.getItems() : new ItemStack[0]) {
                if (make(level, p, option.getItem(), depth - 1)) {
                    p.made.merge(option.getItem(), -1, Integer::sum); // one of what it made goes in here
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Item first(Map<Item, Integer> pool, Ingredient ing) {
        for (Map.Entry<Item, Integer> e : pool.entrySet()) {
            if (e.getValue() > 0 && ing.test(new ItemStack(e.getKey()))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** What mends a tool (an iron ingot for iron tools, cobblestone for stone...), from the stock, or null. */
    public static Item repairWith(ItemStack tool, Map<Item, Integer> stock) {
        for (Map.Entry<Item, Integer> e : stock.entrySet()) {
            if (e.getValue() > 0 && tool.getItem().isValidRepairItem(tool, new ItemStack(e.getKey()))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Whether the smith smelts it: raw ore and ore blocks. */
    public static boolean ore(ItemStack s) {
        return plain(s) && (s.is(Tags.Items.RAW_MATERIALS) || s.is(Tags.Items.ORES));
    }

    /** What smelting one makes, or nothing. */
    public static ItemStack smelted(ServerLevel level, ItemStack s) {
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(s), level)
                .map(r -> r.value().getResultItem(level.registryAccess()).copy()).orElse(ItemStack.EMPTY);
    }
}
