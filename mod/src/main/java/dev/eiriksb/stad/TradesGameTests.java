package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the trades (PLAN.md §6.12): the carpenter, the mason and the weaver at their workplaces. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TradesGameTests {
    private TradesGameTests() {}

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

    private static Building built(GameTestHelper helper, Colony colony, String key, BlockPos at) {
        BuildingDef def = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse(key)).orElseThrow();
        Building b = colony.addBuilding(def.key(), def.name(), helper.absolutePos(at), Rotation.NONE, 0);
        b.status = Building.Status.DONE;
        for (Placement.Step s : b.placement().orElseThrow().steps()) {
            helper.getLevel().setBlock(s.pos(), s.state(), 2);
        }
        return b;
    }

    @GameTest(template = "lot", batch = "trades_carpenter", timeoutTicks = 3000)
    public static void carpenterMakesWhatTheBuildersAskForAndPlanks(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Building mill = built(helper, colony, "stad:medieval/sawmill", new BlockPos(3, G - 1, 3));
        helper.assertTrue(Workplaces.jobFor(mill.type()).orElse("").equals(Jobs.CARPENTER), "a sawmill is the carpenter's");
        hall(helper).storage().addItem(new ItemStack(Items.OAK_LOG, 8));
        hall(helper).storage().addItem(new ItemStack(Items.STONE_AXE));
        // a house the builder is waiting on a door for (the town asks for it)
        BuildingDef cottage = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse("stad:medieval/cottage")).orElseThrow();
        Building house = colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(3, G - 1, 11)), Rotation.NONE, 0);
        house.status = Building.Status.WAITING;
        house.missing.put(Items.OAK_DOOR, 1);
        CitizenEntity carpenter = worker(helper, colony, Jobs.CARPENTER, 12, 12);
        boolean[] axe = {false};
        helper.succeedWhen(() -> {
            axe[0] |= carpenter.getMainHandItem().is(Items.STONE_AXE);
            helper.assertTrue(axe[0], "works with the town's axe");
            ColonyStorage town = ColonyStorage.deliveries(helper.getLevel(), colony);
            helper.assertTrue(town.count(Items.OAK_DOOR) >= 1, "a door for the builder: " + town.count(Items.OAK_DOOR) + "; " + carpenter.status().text());
            helper.assertTrue(town.count(Items.OAK_PLANKS) >= 8 && town.count(Items.OAK_LOG) == 0, "the logs sawn into planks: "
                    + town.count(Items.OAK_PLANKS) + " planks, " + town.count(Items.OAK_LOG) + " logs left; " + carpenter.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "trades_mason", timeoutTicks = 4000)
    public static void masonBurnsAndCutsStoneAndMakesGlass(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/stoneworks", new BlockPos(3, G - 1, 3));
        hall(helper).storage().addItem(new ItemStack(Items.COBBLESTONE, 12));
        hall(helper).storage().addItem(new ItemStack(Items.SAND, 4));
        CitizenEntity mason = worker(helper, colony, Jobs.MASON, 12, 12);
        helper.succeedWhen(() -> {
            helper.assertTrue(colony.requests().stream().anyMatch(r -> r.what.equals("minecraft:stone_pickaxe") && r.reason.contains("mason")),
                    "no pickaxe: the town asks for one for the mason: " + colony.requests().stream().map(r -> r.what + " " + r.reason).toList());
            ColonyStorage town = ColonyStorage.deliveries(helper.getLevel(), colony);
            helper.assertTrue(town.count(Items.STONE_BRICKS) >= 4, "stone bricks cut from burnt cobblestone: " + town.count(Items.STONE_BRICKS) + " (stone "
                    + town.count(Items.STONE) + ", cobblestone " + town.count(Items.COBBLESTONE) + "); " + mason.status().text());
            helper.assertTrue(town.count(Items.GLASS) == 4, "glass from the sand: " + town.count(Items.GLASS) + "; " + mason.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "trades_weaver", timeoutTicks = 3000)
    public static void weaverMakesBedsForNewHomes(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/loom_house", new BlockPos(3, G - 1, 3));
        hall(helper).storage().addItem(new ItemStack(Items.WHITE_WOOL, 6));
        hall(helper).storage().addItem(new ItemStack(Items.OAK_LOG, 2)); // planks made from these on the way
        CitizenEntity weaver = worker(helper, colony, Jobs.WEAVER, 12, 12);
        helper.succeedWhen(() -> {
            ColonyStorage town = ColonyStorage.deliveries(helper.getLevel(), colony);
            helper.assertTrue(town.count(Items.WHITE_BED) == 2, "two beds: " + town.count(Items.WHITE_BED) + "; " + weaver.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "trades_full", timeoutTicks = 1200)
    public static void craftersStopWhenTheTownHallIsFull(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        built(helper, colony, "stad:medieval/sawmill", new BlockPos(3, G - 1, 3));
        var store = hall(helper).storage();
        store.addItem(new ItemStack(Items.OAK_LOG, 8));
        store.addItem(new ItemStack(Items.STONE_AXE));
        for (int i = 0; i < store.getContainerSize(); i++) {
            if (store.getItem(i).isEmpty()) {
                store.setItem(i, new ItemStack(Items.COBBLESTONE, 64)); // full up
            }
        }
        CitizenEntity carpenter = worker(helper, colony, Jobs.CARPENTER, 12, 12);
        helper.succeedWhen(() -> {
            helper.assertTrue(carpenter.status().text().contains("full"), "says the hall is full: " + carpenter.status().text());
            int logs = 0;
            for (int i = 0; i < store.getContainerSize(); i++) {
                logs += store.getItem(i).is(Items.OAK_LOG) ? store.getItem(i).getCount() : 0;
            }
            helper.assertTrue(logs == 8, "the logs left alone: " + logs);
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(40)).isEmpty(), "nothing dropped");
            cleanUp(helper, colony, owner);
        });
    }
}
