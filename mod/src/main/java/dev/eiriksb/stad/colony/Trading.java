package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Smithing;
import dev.eiriksb.stad.citizen.Tools;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The town's market (PLAN.md §6.9): the merchant sells what the town has more of than it keeps and buys what it's
 * asked for, for emeralds, through the game's own trading screen. Every trade moves the goods in and out of the
 * town's real chests. Server thread.
 */
public final class Trading {
    private Trading() {}

    /** how many of a thing the town keeps before it'll sell any */
    public static final int KEEP = 64;
    static final int MAX_SELL = 8, MAX_BUY = 6;

    /** how many of a thing make an emerald (what it's worth), for common goods */
    private static final Map<Item, Double> PER_EMERALD = new HashMap<>();

    static {
        per(20, Items.WHEAT);
        per(26, Items.POTATO);
        per(22, Items.CARROT);
        per(15, Items.BEETROOT);
        per(6, Items.BREAD);
        per(8, Items.BAKED_POTATO);
        per(6, Items.COOKED_COD, Items.COOKED_SALMON);
        per(15, Items.COD);
        per(13, Items.SALMON);
        per(5, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON);
        per(8, Items.COOKED_CHICKEN);
        per(10, Items.BEEF);
        per(7, Items.PORKCHOP, Items.MUTTON);
        per(14, Items.CHICKEN);
        per(8, Items.APPLE);
        per(6, Items.PUMPKIN);
        per(4, Items.MELON);
        per(16, Items.EGG);
        per(6, Items.LEATHER);
        per(24, Items.FEATHER);
        per(14, Items.STRING);
        per(64, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT);
        per(32, Items.STONE, Items.SAND, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TORCH);
        per(20, Items.GRAVEL);
        per(64, Items.STICK);
        per(15, Items.COAL, Items.CHARCOAL);
        per(4, Items.IRON_INGOT);
        per(3, Items.GOLD_INGOT);
        per(12, Items.COPPER_INGOT);
        per(6, Items.RAW_IRON);
        per(4, Items.RAW_GOLD);
        per(20, Items.RAW_COPPER);
        per(26, Items.FLINT);
        per(10, Items.CLAY_BALL);
        per(4, Items.LAPIS_LAZULI);
        per(8, Items.REDSTONE);
        per(0.2, Items.DIAMOND);
        per(16, Items.BONE);
        per(32, Items.ROTTEN_FLESH);
        per(5, Items.GUNPOWDER);
        per(11, Items.GLASS);
    }

    private static void per(double n, Item... items) {
        for (Item i : items) {
            PER_EMERALD.put(i, n);
        }
    }

    /** How many make an emerald (below 1: it's worth more than one). */
    public static double perEmerald(ItemStack s) {
        Double n = PER_EMERALD.get(s.getItem());
        if (n != null) {
            return n;
        }
        if (s.is(ItemTags.LOGS)) {
            return 16;
        }
        if (s.is(ItemTags.PLANKS)) {
            return 64;
        }
        if (s.is(ItemTags.WOOL)) {
            return 18;
        }
        if (Tools.kindOf(s) != null) {
            return s.getMaxDamage() >= 250 ? 1 / 3.0 : 1;
        }
        return switch (s.getRarity()) {
            case COMMON -> 32;
            case UNCOMMON -> 4;
            case RARE -> 1 / 3.0;
            case EPIC -> 1 / 8.0;
        };
    }

    /** Whether the town would sell it: plain goods, not money, tools, weapons or armour. */
    static boolean forSale(ItemStack s) {
        return Smithing.plain(s) && !s.is(Items.EMERALD) && Tools.kindOf(s) == null && !(s.getItem() instanceof ArmorItem)
                && !s.isDamageableItem();
    }

    /** What the town keeps of something before selling: more of food the bigger the town. */
    static int keep(Colony colony, ItemStack s) {
        return ColonyStorage.isFood(s) ? Math.max(KEEP, colony.population() * 8) : KEEP;
    }

    /** The town's stores the market trades from: the town hall and the warehouses. */
    static List<ColonyStorage> stores(ServerLevel level, Colony colony) {
        return List.of(ColonyStorage.supply(level, colony), ColonyStorage.warehouses(level, colony));
    }

    static int count(List<ColonyStorage> stores, java.util.function.Predicate<ItemStack> what) {
        return stores.stream().mapToInt(s -> s.count(what)).sum();
    }

    static List<ItemStack> take(List<ColonyStorage> stores, java.util.function.Predicate<ItemStack> what, int n) {
        List<ItemStack> got = new ArrayList<>();
        for (ColonyStorage s : stores) {
            int have = got.stream().mapToInt(ItemStack::getCount).sum();
            if (have >= n) {
                break;
            }
            got.addAll(s.takeUpTo(what, n - have));
        }
        return got;
    }

    static ItemStack put(ServerLevel level, Colony colony, ItemStack stack) {
        ItemStack rest = ColonyStorage.supply(level, colony).put(stack);
        if (!rest.isEmpty()) {
            rest = ColonyStorage.warehouses(level, colony).put(rest);
        }
        return rest;
    }

    /** What the town wants to buy, and how many: what it's asked for (and food, when it's short). */
    static Map<Item, Integer> wants(Colony colony) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (Request r : colony.requests()) {
            if (Request.FOOD.equals(r.what)) {
                m.merge(Items.BREAD, r.count, Integer::sum);
                m.merge(Items.BAKED_POTATO, r.count, Integer::sum);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(r.what);
                Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
                if (item != Items.AIR && item != Items.EMERALD) {
                    m.merge(item, r.count, Integer::sum);
                }
            }
        }
        return m;
    }

    /** The offers: what the town sells (its surplus, the most valuable first), then what it buys. */
    public static MerchantOffers offers(ServerLevel level, Colony colony, int skill) {
        List<ColonyStorage> stores = stores(level, colony);
        MerchantOffers offers = new MerchantOffers();
        double edge = 0.02 * skill; // a skilled merchant drives a better bargain for the town
        // selling
        Map<Item, Integer> stock = new LinkedHashMap<>();
        for (ColonyStorage s : stores) {
            for (ColonyStorage.Box b : s.boxes()) {
                for (int i = 0; i < b.container().getContainerSize(); i++) {
                    ItemStack st = b.container().getItem(i);
                    if (forSale(st)) {
                        stock.merge(st.getItem(), st.getCount(), Integer::sum);
                    }
                }
            }
        }
        record Sale(Item item, int count, int price, int uses, double worth) {}
        List<Sale> sales = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : stock.entrySet()) {
            ItemStack one = new ItemStack(e.getKey());
            int spare = e.getValue() - keep(colony, one);
            double per = perEmerald(one);
            int count = per >= 1 ? Math.max(1, Math.min(one.getMaxStackSize(), (int) Math.floor(per * (1 - edge)))) : 1;
            int price = per >= 1 ? 1 : Math.min(64, (int) Math.ceil((1 / per) * (1 + edge)));
            int uses = spare / count;
            if (uses > 0) {
                sales.add(new Sale(e.getKey(), count, price, Math.min(uses, 64), spare / per));
            }
        }
        sales.sort(Comparator.comparingDouble(s -> -s.worth));
        for (Sale s : sales.subList(0, Math.min(MAX_SELL, sales.size()))) {
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, s.price), new ItemStack(s.item, s.count), s.uses, 0, 0f));
        }
        // buying, with the town's emeralds
        int emeralds = count(stores, st -> st.is(Items.EMERALD));
        int n = 0;
        for (Map.Entry<Item, Integer> e : wants(colony).entrySet()) {
            if (n >= MAX_BUY || emeralds <= 0) {
                break;
            }
            ItemStack one = new ItemStack(e.getKey());
            double per = perEmerald(one);
            int count = per >= 1 ? Math.max(1, Math.min(one.getMaxStackSize(), (int) Math.ceil(per * 1.25 * (1 + edge)))) : 1;
            int pay = per >= 1 ? 1 : Math.max(1, (int) Math.floor((1 / per) * 0.8 * (1 - edge)));
            int uses = Math.min((e.getValue() + count - 1) / count, emeralds / pay);
            if (uses > 0) {
                offers.add(new MerchantOffer(new ItemCost(e.getKey(), count), new ItemStack(Items.EMERALD, pay), uses, 0, 0f));
                n++;
            }
        }
        return offers;
    }

    /** Opens the market for a player: the merchant's trades in the game's trading screen. Returns why not, or null. */
    public static String open(ServerPlayer player, CitizenEntity merchant) {
        Colony colony = merchant.colony().orElse(null);
        if (colony == null || !Jobs.MERCHANT.equals(merchant.job().orElse(null))) {
            return merchant.getName().getString() + " isn't a merchant.";
        }
        if (!merchant.canWork()) {
            return merchant.getName().getString() + "'s market is closed" + (merchant.isNight() ? " until morning." : " for now.");
        }
        if (player.distanceToSqr(merchant) > 8 * 8) {
            return "Go up to " + merchant.getName().getString() + " to trade.";
        }
        TownMerchant m = new TownMerchant((ServerLevel) merchant.level(), colony, merchant, player);
        if (m.getOffers().isEmpty()) {
            return colony.name() + " has nothing spare to sell and nothing it needs to buy right now.";
        }
        m.openTradingScreen(player, Component.literal(merchant.getName().getString() + "'s market"), 0);
        return null;
    }

    /** The town, as the trading screen's other side. */
    static final class TownMerchant implements Merchant {
        private final ServerLevel level;
        private final Colony colony;
        private final CitizenEntity merchant;
        private Player trading;
        private MerchantOffers offers;

        TownMerchant(ServerLevel level, Colony colony, CitizenEntity merchant, Player trading) {
            this.level = level;
            this.colony = colony;
            this.merchant = merchant;
            this.trading = trading;
            this.offers = Trading.offers(level, colony, merchant.skill());
        }

        @Override
        public void setTradingPlayer(Player player) {
            trading = player;
        }

        @Override
        public Player getTradingPlayer() {
            return trading;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
            this.offers = offers;
        }

        /** A trade went through: the town's goods and emeralds move to match. */
        @Override
        public void notifyTrade(MerchantOffer offer) {
            offer.increaseUses();
            trade(level, colony, offer);
            merchant.didWork(1);
            merchant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
        }

        @Override
        public int getVillagerXp() {
            return 0;
        }

        @Override
        public void overrideXp(int xp) {
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }

    /** Moves the goods for one trade: what the player paid goes into the town's chests, what they got comes out. */
    public static void trade(ServerLevel level, Colony colony, MerchantOffer offer) {
        List<ColonyStorage> stores = stores(level, colony);
        ItemStack paid = offer.getCostA().copy();
        ItemStack got = offer.getResult();
        take(stores, s -> Smithing.plain(s) && s.is(got.getItem()), got.getCount());
        ItemStack rest = put(level, colony, paid);
        if (!rest.isEmpty()) {
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level, colony.center.getX() + 0.5,
                    colony.center.getY() + 1.2, colony.center.getZ() + 0.5, rest);
            level.addFreshEntity(drop);
        }
        colony.changed();
    }
}
