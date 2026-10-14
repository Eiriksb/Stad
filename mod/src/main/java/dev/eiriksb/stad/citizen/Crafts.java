package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The trades that turn the town's raw materials into what its builders and homes need (PLAN.md §6.12): the carpenter
 * (logs into planks, sticks, doors, fences...), the mason (cobblestone into stone, stone bricks, slabs and stairs; sand
 * into glass) and the weaver (wool into beds and carpets). They make what the town has asked for first (the builders'
 * shortages: {@link dev.eiriksb.stad.colony.Request}), then keep a small stock of the basics. Everything is made by the
 * game's own recipes: crafting ({@link Smithing#bill}), the stonecutter and the furnace.
 */
public final class Crafts {
    private Crafts() {}

    public enum Method { CRAFT, CUT, SMELT }

    /** Something a trade keeps in the stores: any item of a group, up to a count ("planks", 64). */
    public record Stock(String words, Predicate<Item> group, int count) {}

    /**
     * A trade: its job and workplace, the block it works at, whether it cuts stone and smelts (with a furnace in the
     * workplace), what it makes, what it keeps in stock, and what it makes things from (for when there's nothing).
     */
    public record Trade(String job, ResourceLocation building, String buildingWords, Predicate<BlockState> bench, String benchWords, boolean cuts,
                        boolean smelts, Predicate<Item> makes, List<Stock> keep, String raw) {}

    /** One batch of work: how, what it makes and how many, and what it takes. */
    public record Make(Method method, Item target, Map<Item, Integer> bill, int yield) {}

    private static final Set<Item> WOODWORK = Set.of(Items.STICK, Items.LADDER, Items.CHEST, Items.BARREL, Items.CRAFTING_TABLE, Items.BOWL,
            Items.COMPOSTER);

    public static final Trade CARPENTER = new Trade(Jobs.CARPENTER, BuildingDef.SAWMILL, "a sawmill (a crafting table and a chest)",
            s -> s.is(Blocks.CRAFTING_TABLE), "crafting table", false, false,
            i -> is(i, ItemTags.PLANKS) || is(i, ItemTags.WOODEN_SLABS) || is(i, ItemTags.WOODEN_STAIRS) || is(i, ItemTags.WOODEN_DOORS)
                    || is(i, ItemTags.WOODEN_TRAPDOORS) || is(i, ItemTags.WOODEN_FENCES) || is(i, ItemTags.FENCE_GATES) || is(i, ItemTags.SIGNS)
                    || is(i, ItemTags.WOODEN_BUTTONS) || is(i, ItemTags.WOODEN_PRESSURE_PLATES) || WOODWORK.contains(i),
            List.of(new Stock("planks", i -> is(i, ItemTags.PLANKS), 64), new Stock("sticks", i -> i == Items.STICK, 32)), "logs");

    public static final Trade MASON = new Trade(Jobs.MASON, BuildingDef.STONEWORKS, "a stoneworks (a stonecutter, a furnace and a chest)",
            s -> s.is(Blocks.STONECUTTER), "stonecutter", true, true, Crafts::masonry,
            List.of(new Stock("stone", i -> i == Items.STONE, 32), new Stock("stone bricks", i -> i == Items.STONE_BRICKS, 64),
                    new Stock("glass", i -> i == Items.GLASS, 16)), "cobblestone or sand");

    public static final Trade WEAVER = new Trade(Jobs.WEAVER, BuildingDef.LOOM_HOUSE, "a loom house (a loom and a chest)", s -> s.is(Blocks.LOOM),
            "loom", false, false, i -> is(i, ItemTags.BEDS) || is(i, ItemTags.WOOL_CARPETS) || is(i, ItemTags.WOOL),
            List.of(new Stock("beds", i -> is(i, ItemTags.BEDS), 2)), "wool (the shepherd shears it) and planks");

    public static final List<Trade> ALL = List.of(CARPENTER, MASON, WEAVER);

    private static boolean is(Item i, net.minecraft.tags.TagKey<Item> tag) {
        return i.builtInRegistryHolder().is(tag);
    }

    /** What the mason makes: blocks of stone, brick and glass (not ore, redstone and the like). */
    static boolean masonry(Item i) {
        if (!(i instanceof BlockItem)) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(i).getPath();
        if (id.contains("ore") || id.contains("redstone") || id.contains("glowstone") || id.contains("lodestone") || id.contains("grindstone")
                || id.contains("stonecutter") || id.contains("end_stone") || id.contains("pressure_plate") || id.contains("button")) {
            return false;
        }
        for (String w : new String[]{"stone", "cobble", "brick", "glass", "andesite", "diorite", "granite", "deepslate", "tuff", "blackstone"}) {
            if (id.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** The trade a job is, or null. */
    public static Trade of(String job) {
        return ALL.stream().filter(t -> t.job().equals(job)).findFirst().orElse(null);
    }

    /** Every item of a group, in the registry's order. */
    public static List<Item> items(Predicate<Item> group) {
        return BuiltInRegistries.ITEM.stream().filter(group).toList();
    }

    /**
     * How to make up to {@code want} of {@code target} from {@code stock}: by the stonecutter or a furnace (when the
     * trade has them), else by crafting; in batches of up to eight. Null when the stock doesn't have what it takes.
     */
    public static Make how(ServerLevel level, Trade trade, Map<Item, Integer> stock, Item target, int want, boolean furnace) {
        if (trade.cuts()) {
            Make m = single(level, RecipeType.STONECUTTING, stock, target, want, Method.CUT);
            if (m != null) {
                return m;
            }
        }
        Map<Item, Integer> bill = Smithing.bill(level, stock, target);
        if (bill != null) {
            int yield = Math.max(1, Smithing.yield(level, target));
            int batches = 1;
            while (batches < 8 && batches * yield < want && covers(stock, bill, batches + 1)) {
                batches++;
            }
            Map<Item, Integer> all = new HashMap<>();
            int b = batches;
            bill.forEach((k, v) -> all.put(k, v * b));
            return new Make(Method.CRAFT, target, all, yield * batches);
        }
        if (trade.smelts() && furnace) {
            return single(level, RecipeType.SMELTING, stock, target, want, Method.SMELT);
        }
        return null;
    }

    private static boolean covers(Map<Item, Integer> stock, Map<Item, Integer> bill, int times) {
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            if (stock.getOrDefault(e.getKey(), 0) < e.getValue() * times) {
                return false;
            }
        }
        return true;
    }

    /** One thing in, some out: the stonecutter's or the furnace's recipes for {@code target}, from what's in stock. */
    private static <I extends net.minecraft.world.item.crafting.RecipeInput, R extends Recipe<I>> Make single(ServerLevel level, RecipeType<R> type,
                                                                                                             Map<Item, Integer> stock, Item target,
                                                                                                             int want, Method method) {
        for (RecipeHolder<R> h : level.getRecipeManager().getAllRecipesFor(type)) {
            ItemStack out = h.value().getResultItem(level.registryAccess());
            if (!out.is(target) || h.value().getIngredients().isEmpty()) {
                continue;
            }
            Ingredient in = h.value().getIngredients().getFirst();
            for (Map.Entry<Item, Integer> e : stock.entrySet()) {
                if (e.getValue() > 0 && in.test(new ItemStack(e.getKey()))) {
                    int n = Math.max(1, Math.min(Math.min(e.getValue(), 8), (int) Math.ceil(want / (double) out.getCount())));
                    return new Make(method, target, Map.of(e.getKey(), n), out.getCount() * n);
                }
            }
        }
        return null;
    }
}
