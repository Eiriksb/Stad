package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Illness;
import dev.eiriksb.stad.citizen.Skills;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Request;
import dev.eiriksb.stad.colony.Requests;
import dev.eiriksb.stad.colony.Trading;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** In-game tests for M14: skills, real tools, and the smith, healer, merchant and teacher (PLAN.md §6.9). */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LaterJobsGameTests {
    private LaterJobsGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(16, G, 16);

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        helper.assertTrue(f.colony() != null, "no town: " + f.problem());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer owner) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
    }

    private static TownHallBlockEntity hall(GameTestHelper helper) {
        return (TownHallBlockEntity) helper.getBlockEntity(HALL);
    }

    private static CitizenEntity worker(GameTestHelper helper, Colony colony, String job, int x, int z) {
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), x, G, z);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        colony.citizen(c.getUUID()).orElseThrow().job = job;
        c.setHunger(20);
        return c;
    }

    /** A bundled building, finished and built. */
    private static Building built(GameTestHelper helper, Colony colony, String key, BlockPos at) {
        BuildingDef def = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse(key)).orElseThrow();
        Building b = colony.addBuilding(def.key(), def.name(), helper.absolutePos(at), Rotation.NONE, 0);
        b.status = Building.Status.DONE;
        for (Placement.Step s : b.placement().orElseThrow().steps()) {
            helper.getLevel().setBlock(s.pos(), s.state(), 2);
        }
        return b;
    }

    @GameTest(template = "lot", batch = "later_skills", timeoutTicks = 100)
    public static void skillsGrowWithWorkAndSchooling(GameTestHelper helper) {
        CitizenRecord r = new CitizenRecord(UUID.randomUUID(), "Ada Smed", 0);
        r.job = Jobs.SMITH;
        helper.assertTrue(Skills.level(r, Jobs.SMITH) == 0 && "a novice smith".equals(Skills.describe(r)), Skills.describe(r));
        boolean titled = Skills.learn(r, Jobs.SMITH, 25);
        helper.assertTrue(titled && Skills.level(r, Jobs.SMITH) == 1 && Skills.title(1).equals("apprentice"), "25 pieces of work: level 1");
        Skills.learn(r, Jobs.SMITH, 2500);
        helper.assertTrue(Skills.level(r, Jobs.SMITH) == Skills.MAX && "a master smith".equals(Skills.describe(r)), "a master: " + Skills.describe(r));
        helper.assertTrue(Math.abs(Skills.speed(Skills.MAX) - 0.8) < 1e-9, "a master works a fifth quicker");
        helper.assertTrue(Skills.level(r, Jobs.FARMER) == 0, "other jobs start from nothing");
        CitizenRecord schooled = new CitizenRecord(UUID.randomUUID(), "Bo Lerd", 0);
        schooled.schooling = Skills.MAX_SCHOOLING;
        Skills.learn(schooled, Jobs.FARMER, 50);
        Skills.learn(r, Jobs.FARMER, 50);
        helper.assertTrue(schooled.skills.get(Jobs.FARMER) == 2 * r.skills.get(Jobs.FARMER), "full schooling: twice as quick to learn");
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "later_tools", timeoutTicks = 3000)
    public static void toolsWearOutAndTheTownAsksForAnother(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        for (int x = 4; x < 8; x++) {
            for (int z = 4; z < 8; z++) {
                helper.setBlock(new BlockPos(x, G - 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.WATER);
            }
        }
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        rod.setDamageValue(rod.getMaxDamage() - 2); // two casts left
        hall(helper).storage().addItem(rod);
        CitizenEntity fisher = worker(helper, colony, Jobs.FISHER, 11, 11);
        boolean[] held = {false};
        helper.succeedWhen(() -> {
            held[0] |= fisher.getMainHandItem().is(Items.FISHING_ROD);
            helper.assertTrue(held[0], "took the town's rod");
            helper.assertTrue(fisher.getMainHandItem().isEmpty() && Tools.lacking(fisher), "the rod broke: " + fisher.getMainHandItem() + " worn "
                    + fisher.getMainHandItem().getDamageValue() + "/" + fisher.getMainHandItem().getMaxDamage() + ", caught "
                    + fisher.record().map(x -> x.workToday).orElse(-1) + "; " + fisher.activity());
            Requests.update(helper.getLevel(), colony);
            helper.assertTrue(colony.requests().stream().anyMatch(q -> q.what.equals(Request.id(Items.FISHING_ROD))), "the town asks for a rod: "
                    + colony.requests().stream().map(Request::label).toList());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "later_tools_night", timeoutTicks = 100)
    public static void toolsGoBackToTheWorkplaceAtNight(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        Building farmhouse = built(helper, colony, "stad:medieval/farmhouse", new BlockPos(3, G - 1, 3));
        CitizenEntity farmer = worker(helper, colony, Jobs.FARMER, 12, 12);
        Workplaces.assign(colony);
        helper.assertTrue(Workplaces.of(colony, farmer.record().orElseThrow()).isPresent(), "the farmer works from the farmhouse");
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        Tools.tidy(farmer, false);
        helper.assertTrue(farmer.getMainHandItem().is(Items.IRON_HOE), "by day they keep it");
        Tools.tidy(farmer, true);
        helper.assertTrue(farmer.getMainHandItem().isEmpty() && Workplaces.storage(helper.getLevel(), farmhouse).count(Items.IRON_HOE) == 1,
                "at night the hoe is put away in the farmhouse: in hand " + farmer.getMainHandItem());
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SHEARS));
        Tools.tidy(farmer, false);
        helper.assertTrue(farmer.getMainHandItem().isEmpty(), "shears aren't a farmer's: back they go");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "later_smith", timeoutTicks = 6000)
    public static void smithMakesMendsAndSmelts(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/smithy", new BlockPos(3, G - 1, 3));
        var hall = hall(helper).storage();
        hall.addItem(new ItemStack(Items.COBBLESTONE, 3));
        hall.addItem(new ItemStack(Items.OAK_PLANKS, 2)); // sticks to be made from these
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(200);
        hall.addItem(axe);
        hall.addItem(new ItemStack(Items.IRON_INGOT, 2));
        hall.addItem(new ItemStack(Items.RAW_IRON, 3));
        hall.addItem(new ItemStack(Items.COAL, 1)); // smelts all three in the smithy's blast furnace
        CitizenEntity smith = worker(helper, colony, Jobs.SMITH, 12, 12);
        CitizenEntity miner = worker(helper, colony, Jobs.MINER, 13, 13); // no mine, so idle: but short of a pickaxe
        helper.succeedWhen(() -> {
            ColonyStorage town = ColonyStorage.deliveries(helper.getLevel(), colony);
            int pick = town.count(Items.STONE_PICKAXE) + (miner.getMainHandItem().is(Items.STONE_PICKAXE) ? 1 : 0);
            helper.assertTrue(pick == 1, "a stone pickaxe made for the miner (from cobblestone and planks): " + pick + "; smith " + smith.activity());
            ItemStack a = ItemStack.EMPTY;
            for (int i = 0; i < hall.getContainerSize(); i++) {
                if (hall.getItem(i).is(Items.IRON_AXE)) {
                    a = hall.getItem(i);
                }
            }
            helper.assertTrue(!a.isEmpty() && a.getDamageValue() <= 75, "the axe mended: " + (a.isEmpty() ? "gone" : a.getDamageValue()));
            helper.assertTrue(town.count(Items.RAW_IRON) == 0 && town.count(Items.IRON_INGOT) >= 1, "the ore smelted: raw " + town.count(Items.RAW_IRON)
                    + ", ingots " + town.count(Items.IRON_INGOT));
            helper.assertTrue(town.count(Items.COBBLESTONE) == 0, "the cobblestone went into the pickaxe");
            helper.assertTrue(town.count(Items.COAL) == 0, "the coal burnt");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "gear", timeoutTicks = 1200)
    public static void citizensUseWhatTheyreGiven(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        CitizenEntity c = worker(helper, colony, Jobs.LUMBERJACK, 12, 12); // no trees, no stores: just what they're given
        var menu = new dev.eiriksb.stad.citizen.CitizenMenu(1, owner.getInventory(), c);
        owner.getInventory().setItem(0, new ItemStack(Items.IRON_HELMET));
        owner.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
        owner.getInventory().setItem(2, new ItemStack(Items.STONE_AXE));
        owner.getInventory().setItem(3, new ItemStack(Items.LEATHER_BOOTS));
        int hotbar = dev.eiriksb.stad.citizen.CitizenMenu.GEAR.length + CitizenEntity.BELONGINGS + CitizenEntity.POCKETS + 27;
        for (int i = 0; i < 3; i++) {
            menu.quickMoveStack(owner, hotbar + i); // shift-clicked across
        }
        helper.assertTrue(c.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the helmet went on their head");
        helper.assertTrue(c.belongings().countItem(Items.BREAD) == 3 && c.belongings().countItem(Items.STONE_AXE) == 1,
                "the bread and the axe are their own things");
        c.belongings().addItem(owner.getInventory().removeItem(3, 1)); // boots given, not worn: they put them on
        c.setHunger(4);
        helper.succeedWhen(() -> {
            helper.assertTrue(c.getMainHandItem().is(Items.STONE_AXE), "their own axe in hand: " + c.getMainHandItem());
            helper.assertTrue(c.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS), "the boots on");
            helper.assertTrue(c.hunger() > 8 && c.belongings().countItem(Items.BREAD) < 3, "ate their own bread: hunger " + c.hunger());
            helper.assertTrue(c.belongings().countItem(Items.BREAD) > 0 || c.hunger() >= 16, "work doesn't hand in their own things");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "gear", timeoutTicks = 400)
    public static void aCitizensGearDropsWhenTheyDie(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        CitizenEntity c = worker(helper, colony, Jobs.FARMER, 12, 12);
        c.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        c.belongings().addItem(new ItemStack(Items.BREAD, 2));
        c.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000); // not killed by a player
        helper.succeedWhen(() -> {
            var items = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, c.getBoundingBox().inflate(4));
            java.util.function.Predicate<net.minecraft.world.item.Item> dropped = it -> items.stream().anyMatch(e -> e.getItem().is(it));
            helper.assertTrue(dropped.test(Items.IRON_CHESTPLATE) && dropped.test(Items.IRON_HOE) && dropped.test(Items.BREAD),
                    "armour, tool and their own things dropped: " + items.stream().map(e -> e.getItem().toString()).toList());
            items.forEach(net.minecraft.world.entity.Entity::discard);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "later_smith_armour", timeoutTicks = 4000)
    public static void smithArmoursTheGuardsAndNeedsCoalToSmelt(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/smithy", new BlockPos(3, G - 1, 3));
        var hall = hall(helper).storage();
        hall.addItem(new ItemStack(Items.IRON_INGOT, 8)); // a chestplate's worth (no planks: no sword)
        hall.addItem(new ItemStack(Items.RAW_COPPER, 4)); // no coal for it
        CitizenEntity smith = worker(helper, colony, Jobs.SMITH, 12, 12);
        CitizenEntity guard = worker(helper, colony, Jobs.GUARD, 13, 13);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD)); // armed: just short of armour
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "the guard wears the chestplate the smith made: "
                    + guard.getItemBySlot(EquipmentSlot.CHEST) + "; smith " + smith.activity());
            helper.assertTrue(ColonyStorage.deliveries(helper.getLevel(), colony).count(Items.RAW_COPPER) == 4, "no ore smelted without coal");
            helper.assertTrue(smith.status().text().contains("nothing to burn"), "the smith says what's missing: " + smith.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "later_healer", timeoutTicks = 3600)
    public static void healerCuresTheIllAndMendsTheWounded(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        CitizenEntity healer = worker(helper, colony, Jobs.HEALER, 12, 12);
        CitizenEntity patient = worker(helper, colony, Jobs.FARMER, 6, 6);
        CitizenRecord r = patient.record().orElseThrow();
        Illness.fallIll(helper.getLevel(), colony, r, "a fever");
        patient.setHealth(6);
        helper.assertTrue(!patient.canWork() && patient.ill(), "ill: no work");
        helper.succeedWhen(() -> {
            helper.assertTrue(r.illness == null, "cured: " + r.illness + " (" + r.treatments + " treatments); healer " + healer.activity());
            helper.assertTrue(patient.getHealth() >= 14, "mended: " + patient.getHealth());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "later_illness", timeoutTicks = 100)
    public static void illnessPassesInAFewDays(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(5 * 24000L + 1000);
        Colony colony = found(helper, owner);
        CitizenEntity c = worker(helper, colony, Jobs.FARMER, 6, 6);
        int well = c.happiness();
        Illness.fallIll(helper.getLevel(), colony, c.record().orElseThrow(), "a cold");
        helper.assertTrue(c.happiness() <= well - 15 && !c.canWork(), "ill: unhappier and off work");
        helper.getLevel().setDayTime((5 + Illness.DAYS - 1) * 24000L + 1000);
        Illness.morning(helper.getLevel(), colony);
        helper.assertTrue(c.ill(), "still ill after " + (Illness.DAYS - 1) + " days");
        helper.getLevel().setDayTime((5 + Illness.DAYS) * 24000L + 1000);
        Illness.morning(helper.getLevel(), colony);
        helper.assertTrue(!c.ill() && c.canWork(), "well again after " + Illness.DAYS + " days");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "later_market", timeoutTicks = 100)
    public static void merchantTradesTheTownsGoods(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        var hall = hall(helper).storage();
        hall.addItem(new ItemStack(Items.COBBLESTONE, 64));
        hall.addItem(new ItemStack(Items.COBBLESTONE, 64));
        hall.addItem(new ItemStack(Items.COBBLESTONE, 64)); // 192: 128 more than the town keeps
        hall.addItem(new ItemStack(Items.EMERALD, 10));
        hall.addItem(new ItemStack(Items.IRON_AXE)); // tools aren't for sale
        colony.need(Request.id(Items.IRON_INGOT), 8, colony.center, "the town hall", "for the smith", 0);
        MerchantOffers offers = Trading.offers(helper.getLevel(), colony, 0);
        MerchantOffer sell = offers.stream().filter(o -> o.getResult().is(Items.COBBLESTONE)).findFirst().orElse(null);
        MerchantOffer buy = offers.stream().filter(o -> o.getCostA().is(Items.IRON_INGOT)).findFirst().orElse(null);
        helper.assertTrue(sell != null && sell.getResult().getCount() == 64 && sell.getCostA().is(Items.EMERALD) && sell.getMaxUses() == 2,
                "sells its spare cobblestone, 64 an emerald, twice: " + (sell == null ? "none" : sell.getResult() + " for " + sell.getCostA() + " x" + sell.getMaxUses()));
        helper.assertTrue(buy != null && buy.getResult().is(Items.EMERALD) && buy.getCostA().getCount() == 5 && buy.getMaxUses() == 2,
                "buys the iron it asked for: " + (buy == null ? "none" : buy.getCostA() + " for " + buy.getResult() + " x" + buy.getMaxUses()));
        helper.assertTrue(offers.stream().noneMatch(o -> o.getResult().is(Items.IRON_AXE) || o.getResult().is(Items.EMERALD) && o.getCostA().is(Items.EMERALD)),
                "no tools or money for sale");
        Trading.trade(helper.getLevel(), colony, sell);
        helper.assertTrue(hall.countItem(Items.COBBLESTONE) == 128 && hall.countItem(Items.EMERALD) == 11, "sold: cobblestone "
                + hall.countItem(Items.COBBLESTONE) + ", emeralds " + hall.countItem(Items.EMERALD));
        Trading.trade(helper.getLevel(), colony, buy);
        helper.assertTrue(hall.countItem(Items.IRON_INGOT) == 5 && hall.countItem(Items.EMERALD) == 10, "bought: iron " + hall.countItem(Items.IRON_INGOT)
                + ", emeralds " + hall.countItem(Items.EMERALD));
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "later_school", timeoutTicks = 5000)
    public static void childrenLearnAtSchool(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/school", new BlockPos(3, G - 1, 3));
        CitizenEntity teacher = worker(helper, colony, Jobs.TEACHER, 12, 12);
        CitizenEntity child = helper.spawn(StadRegistries.CITIZEN.get(), 13, G, 10);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, child);
        child.setChild(true);
        child.setHunger(20);
        Workplaces.assign(colony);
        helper.succeedWhen(() -> {
            CitizenRecord r = child.record().orElseThrow();
            helper.assertTrue(r.schooling == 1, "a day at school: " + r.schooling + "; teacher " + teacher.activity() + "; child " + child.activity());
            cleanUp(helper, colony, owner);
        });
    }
}
