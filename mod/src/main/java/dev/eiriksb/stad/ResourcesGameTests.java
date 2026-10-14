package dev.eiriksb.stad;

import dev.eiriksb.stad.architect.Yard;
import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Mine;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Warehouse;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * In-game tests for the town's basic resources (PLAN.md §6.12): mines strip-mined at the ore levels and dug to
 * bedrock, the miner following veins, the gatherer digging and foraging, and the warehouse's shelves the courier keeps.
 */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResourcesGameTests {
    private ResourcesGameTests() {}

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
        var def = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse(key)).orElseThrow();
        Building b = colony.addBuilding(def.key(), def.name(), helper.absolutePos(at), Rotation.NONE, 0);
        b.status = Building.Status.DONE;
        for (Placement.Step s : b.placement().orElseThrow().steps()) {
            helper.getLevel().setBlock(s.pos(), s.state(), 2);
        }
        return b;
    }

    private static int carried(CitizenEntity c, net.minecraft.world.item.Item item) {
        return c.inventory().countItem(item);
    }

    // ---- mining ---------------------------------------------------------------------------------------------------

    /** At an ore level a shaft's tunnels come first: out from the shaft, two high, lit, and floored over a cave. */
    @GameTest(template = "lot", batch = "res_tunnels")
    public static void shaftsAreStripMinedOutward(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        Colony colony = found(helper, owner);
        BlockPos mid = helper.absolutePos(new BlockPos(4, G, 8));
        int y = mid.getY();
        for (int dx = -3; dx <= 11; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    level.setBlock(mid.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) { // the shaft dug down to here
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(mid.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(mid.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        BlockPos cave = mid.offset(5, -1, 0); // under the tunnel's third block
        level.setBlock(cave, Blocks.AIR.defaultBlockState(), 2);
        Mine.Pit pit = new Mine.Pit(mid.getX() - 2, mid.getZ() - 2, mid.getX() + 2, mid.getZ() + 2, y + 1, 2, true);
        List<Mine.Tunnel> tunnels = Mine.tunnels(pit);
        helper.assertTrue(tunnels.size() == 4 + 4 * (Mine.CORRIDOR / Mine.SPACING), "east and west with side tunnels, north and south: " + tunnels.size());
        helper.assertTrue(tunnels.getFirst().at(0, y).equals(mid.offset(3, 0, 0)), "the first starts at the shaft's edge: " + tunnels.getFirst());
        List<BlockPos> order = new java.util.ArrayList<>();
        boolean floored = false;
        for (int i = 0; i < 12; i++) {
            Mine.Task t = Mine.tunnels(level, colony, pit, y).orElseThrow();
            if (t.step()) {
                helper.assertTrue(t.pos().equals(cave), "a floor only over the cave: " + t);
                level.setBlock(t.pos(), Blocks.COBBLESTONE.defaultBlockState(), 2);
                floored = true;
                continue;
            }
            order.add(t.pos());
            level.removeBlock(t.pos(), false);
        }
        helper.assertTrue(floored, "the tunnel's floor over the cave");
        helper.assertTrue(order.get(0).equals(mid.offset(3, 1, 0)) && order.get(1).equals(mid.offset(3, 0, 0)) && order.get(2).equals(mid.offset(4, 1, 0)),
                "head high first, then the feet, a column at a time: " + order);
        helper.assertTrue(level.getBlockState(mid.offset(6, 1, 0)).is(Blocks.WALL_TORCH), "a torch on the wall: " + level.getBlockState(mid.offset(6, 1, 0)));
        helper.assertTrue(Mine.level(-58) && Mine.level(16) && !Mine.level(17), "the ore levels");
        // a quarry can be laid out down to bedrock
        Yard deep = Yard.parse("{\"depth\":" + Mine.BEDROCK + "}");
        helper.assertTrue(deep.depth() == Mine.BEDROCK && Yard.DEPTHS.contains(Mine.BEDROCK), "down to bedrock: " + deep.depth());
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    /** Ore beside the shaft is dug out, the whole vein, as far as can be reached from where it was found. */
    @GameTest(template = "lot", batch = "res_vein", timeoutTicks = 6000)
    public static void minersFollowVeinsOfOre(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        level.setDayTime(1000);
        Colony colony = found(helper, owner);
        int depth = StadConfig.MINE_DEPTH.get();
        StadConfig.MINE_DEPTH.set(2);
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                for (int y = -2; y < G - 1; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        var def = BuildingPacks.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "medieval/mine")).orElseThrow();
        BlockPos origin = helper.absolutePos(new BlockPos(2, G, 2));
        Placement placement = Placement.of(def.blueprint(), origin, Rotation.NONE);
        for (Placement.Step s : placement.steps()) {
            level.setBlock(s.pos(), s.state(), 2);
        }
        Building mine = colony.addBuilding(def.key(), def.name(), origin, Rotation.NONE, level.getGameTime());
        mine.status = Building.Status.DONE;
        BlockPos top = Mine.shafts(colony).getFirst();
        // a vein of coal running out from the shaft's wall, and a lump of iron off on its own
        BlockPos a = top.offset(3, -1, 0), b = top.offset(4, -1, 0), c = top.offset(4, -1, 1), alone = top.offset(5, -1, -2);
        for (BlockPos p : List.of(a, b, c)) {
            level.setBlock(p, Blocks.COAL_ORE.defaultBlockState(), 2);
        }
        level.setBlock(alone, Blocks.IRON_ORE.defaultBlockState(), 2);
        CitizenEntity miner = worker(helper, colony, Jobs.MINER, 12, 12);
        miner.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        helper.succeedWhen(() -> {
            helper.assertTrue(Mine.depth(level, top) == 2, "dug " + Mine.depth(level, top) + " layers: " + miner.status().text());
            for (BlockPos p : List.of(a, b, c)) {
                helper.assertTrue(!level.getBlockState(p).is(Blocks.COAL_ORE), "the vein dug out at " + p + ": " + miner.status().text());
            }
            helper.assertTrue(level.getBlockState(alone).is(Blocks.IRON_ORE), "not ore it couldn't see");
            int coal = carried(miner, Items.COAL) + ColonyStorage.deliveries(level, colony).count(Items.COAL);
            helper.assertTrue(coal >= 3, "brought up the coal: " + coal);
            StadConfig.MINE_DEPTH.set(depth);
            cleanUp(helper, colony, owner);
        });
    }

    // ---- the town's land ------------------------------------------------------------------------------------------

    /** Each finished home grows the town's land, but not into another town's. */
    @GameTest(template = "lot", batch = "res_growth")
    public static void landGrowsWithHomes(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        Colony colony = found(helper, owner);
        int per = StadConfig.LAND_PER_HOME.get();
        var cottage = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse("stad:medieval/cottage")).orElseThrow();
        helper.assertTrue(!dev.eiriksb.stad.colony.Growth.update(level, colony, ColonyManager.get(level.getServer()).all()), "no homes, no growth");
        for (int i = 0; i < 2; i++) {
            colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(2 + 8 * i, G - 1, 2)), Rotation.NONE, 0).status
                    = Building.Status.DONE;
        }
        dev.eiriksb.stad.colony.Growth.update(level, colony, ColonyManager.get(level.getServer()).all());
        int room = dev.eiriksb.stad.colony.Growth.room(colony, ColonyManager.get(level.getServer()).all());
        helper.assertTrue(colony.radius == Math.min(16 + 2 * per, room), "two homes: " + colony.radius + " (room " + room + ")");
        helper.assertTrue(colony.contains(level.dimension(), colony.center.offset(colony.radius - 1, 0, 0)), "the new land is the town's");
        // a neighbour: the land stops at theirs
        BlockPos near = colony.center.offset(16 + 2 * per + 20, 0, 0);
        ColonyManager.Founding other = ColonyManager.get(level.getServer()).found(level, near, java.util.UUID.randomUUID(), "Neighbour", 16);
        helper.assertTrue(other.colony() != null, "a neighbour: " + other.problem());
        for (int i = 0; i < 10; i++) {
            colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(2, G - 1, 10)), Rotation.NONE, 0).status
                    = Building.Status.DONE;
        }
        dev.eiriksb.stad.colony.Growth.update(level, colony, ColonyManager.get(level.getServer()).all());
        long gap = (long) Math.sqrt(colony.center.distSqr(near)) - colony.radius - other.colony().radius;
        helper.assertTrue(gap >= 0, "grew into the neighbour's land: " + colony.radius + ", gap " + gap);
        ColonyManager.get(level.getServer()).dissolve(level.getServer(), other.colony());
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    // ---- the gatherer ---------------------------------------------------------------------------------------------

    @GameTest(template = "lot", batch = "res_gather", timeoutTicks = 4000)
    public static void gathererDigsAndForagesTheBasics(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        level.setDayTime(1000);
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                helper.setBlock(new BlockPos(x, G - 1, z), x > 3 && x < 7 && z > 8 && z < 12 ? Blocks.SAND : x > 8 && x < 12 && z > 3 && z < 7 ? Blocks.GRAVEL
                        : Blocks.GRASS_BLOCK);
                helper.setBlock(new BlockPos(x, G - 2, z), Blocks.DIRT);
            }
        }
        Colony colony = found(helper, owner);
        hall(helper).storage().addItem(new ItemStack(Items.DIRT, 60)); // a few more wanted
        helper.setBlock(new BlockPos(5, G, 5), Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        ItemEntity sapling = new ItemEntity(level, helper.absolutePos(new BlockPos(4, G, 8)).getX() + 0.5, helper.absolutePos(new BlockPos(4, G, 8)).getY(),
                helper.absolutePos(new BlockPos(4, G, 8)).getZ() + 0.5, new ItemStack(Items.BIRCH_SAPLING, 2));
        CompoundTag t = sapling.saveWithoutId(new CompoundTag());
        t.putShort("Age", (short) 1000); // it's been lying there a while
        t.putShort("PickupDelay", (short) 0);
        sapling.load(t);
        level.addFreshEntity(sapling);
        CitizenEntity gatherer = worker(helper, colony, Jobs.GATHERER, 6, 6);
        BlockPos bush = helper.absolutePos(new BlockPos(5, G, 5));
        boolean[] picked = {false};
        helper.succeedWhen(() -> {
            picked[0] |= level.getBlockState(bush).is(Blocks.SWEET_BERRY_BUSH) && level.getBlockState(bush).getValue(SweetBerryBushBlock.AGE) < 2;
            ColonyStorage town = ColonyStorage.deliveries(level, colony);
            String says = gatherer.status().text() + "; carries " + gatherer.inventory().getItems().stream().filter(s -> !s.isEmpty()).toList();
            helper.assertTrue(town.count(Items.BIRCH_SAPLING) + carried(gatherer, Items.BIRCH_SAPLING) == 2, "picked up the saplings: " + says);
            helper.assertTrue(picked[0], "picked the berries (they're food: someone may have eaten them since): " + says);
            helper.assertTrue(level.getBlockState(bush).is(Blocks.SWEET_BERRY_BUSH), "left the bush");
            helper.assertTrue(town.count(Items.DIRT) >= 64, "dug dirt: " + town.count(Items.DIRT) + "; " + says);
            helper.assertTrue(town.count(Items.SAND) >= 2, "dug sand: " + town.count(Items.SAND) + "; " + says);
            helper.assertTrue(town.count(Items.GRAVEL) + town.count(Items.FLINT) >= 2, "dug gravel: " + says);
            // the ground taken down evenly: nowhere a hole deeper than the ground round it
            for (int x = 1; x < 17; x++) {
                for (int z = 1; z < 17; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, 0, z));
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        int n = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX() + d.getStepX(), p.getZ() + d.getStepZ());
                        helper.assertTrue(n - h <= 1, "a hole at " + x + ", " + z + ": " + h + " beside " + n);
                    }
                }
            }
            cleanUp(helper, colony, owner);
        });
    }

    // ---- the warehouse --------------------------------------------------------------------------------------------

    @GameTest(template = "empty")
    public static void goodsHaveTheirShelves(GameTestHelper helper) {
        record Case(net.minecraft.world.item.Item item, Warehouse.Shelf shelf) {}
        for (Case c : List.of(new Case(Items.OAK_LOG, Warehouse.Shelf.WOOD), new Case(Items.SPRUCE_PLANKS, Warehouse.Shelf.WOOD),
                new Case(Items.BREAD, Warehouse.Shelf.FOOD), new Case(Items.WHEAT, Warehouse.Shelf.FOOD), new Case(Items.IRON_INGOT, Warehouse.Shelf.ORE),
                new Case(Items.RAW_COPPER, Warehouse.Shelf.ORE), new Case(Items.COAL, Warehouse.Shelf.ORE), new Case(Items.COBBLESTONE, Warehouse.Shelf.BUILDING),
                new Case(Items.SAND, Warehouse.Shelf.BUILDING), new Case(Items.WHEAT_SEEDS, Warehouse.Shelf.FARM), new Case(Items.OAK_SAPLING, Warehouse.Shelf.FARM),
                new Case(Items.WHITE_WOOL, Warehouse.Shelf.CLOTH), new Case(Items.RED_BED, Warehouse.Shelf.CLOTH),
                new Case(Items.IRON_PICKAXE, Warehouse.Shelf.TOOLS), new Case(Items.IRON_SWORD, Warehouse.Shelf.TOOLS), new Case(Items.ENDER_PEARL, Warehouse.Shelf.OTHER))) {
            helper.assertTrue(Warehouse.of(new ItemStack(c.item())) == c.shelf(), c.item() + " goes on " + c.shelf() + ", not " + Warehouse.of(new ItemStack(c.item())));
        }
        helper.assertTrue(Warehouse.Shelf.named("Food") == Warehouse.Shelf.FOOD && Warehouse.Shelf.named("my ores") == Warehouse.Shelf.ORE
                && Warehouse.Shelf.named("Chest") == null, "shelves by name");
        helper.succeed();
    }

    /** A great warehouse's racks are named for their goods, which go onto their own shelves; the courier sorts the rest. */
    @GameTest(template = "lot", batch = "res_warehouse", timeoutTicks = 3000)
    public static void warehouseShelvesAreKeptInOrder(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        level.setDayTime(1000);
        Colony colony = found(helper, owner);
        Building w = built(helper, colony, "stad:medieval/great_warehouse", new BlockPos(1, G - 1, 1));
        int named = Warehouse.label(level, colony);
        ColonyStorage store = ColonyStorage.warehouses(level, colony);
        helper.assertTrue(store.size() >= 1500, "a big warehouse: " + store.size() + " slots");
        helper.assertTrue(named >= 20 && Warehouse.label(level, colony) == 0, "named its racks once: " + named);
        java.util.Map<Warehouse.Shelf, Integer> shelves = new java.util.EnumMap<>(Warehouse.Shelf.class);
        for (ColonyStorage.Box b : store.boxes()) {
            Warehouse.Shelf s = Warehouse.of(b.container());
            helper.assertTrue(s != null, "every container a shelf: " + b.pos());
            shelves.merge(s, 1, Integer::sum);
        }
        helper.assertTrue(shelves.size() == Warehouse.Shelf.values().length, "a shelf for each kind of goods: " + shelves);
        long signs = w.placement().orElseThrow().steps().stream().filter(st -> level.getBlockEntity(st.pos()) instanceof SignBlockEntity sign
                && !sign.getFrontText().getMessage(1, false).getString().isEmpty()).count();
        helper.assertTrue(signs >= 10, "the shelves written on the signs over the racks: " + signs);
        // deliveries go onto their own shelves
        store.put(new ItemStack(Items.COBBLESTONE, 64));
        store.put(new ItemStack(Items.BREAD, 16));
        ColonyStorage.Box stone = store.find(s -> s.is(Items.COBBLESTONE)), bread = store.find(s -> s.is(Items.BREAD));
        helper.assertTrue(Warehouse.of(stone.container()) == Warehouse.Shelf.BUILDING && Warehouse.of(bread.container()) == Warehouse.Shelf.FOOD,
                "on their shelves: " + Warehouse.of(stone.container()) + ", " + Warehouse.of(bread.container()));
        // logs left on the food shelf, and a town hall that's full
        ((Container) bread.container()).setItem(26, new ItemStack(Items.OAK_LOG, 32));
        TownHallBlockEntity hall = hall(helper);
        for (int i = 0; i < hall.storage().getContainerSize(); i++) {
            hall.storage().setItem(i, new ItemStack(i < 4 ? Items.BREAD : Items.COBBLESTONE, 64));
        }
        CitizenEntity courier = worker(helper, colony, Jobs.COURIER, 12, 12);
        helper.succeedWhen(() -> {
            String says = courier.status().text();
            int free = 0;
            for (int i = 0; i < hall.storage().getContainerSize(); i++) {
                free += hall.storage().getItem(i).isEmpty() ? 1 : 0;
            }
            helper.assertTrue(free >= 6, "room made in the town hall: " + free + " free; " + says);
            helper.assertTrue(hall.storage().countItem(Items.BREAD) == 4 * 64, "the food stays in the town hall");
            ColonyStorage.Box logs = ColonyStorage.warehouses(level, colony).find(s -> s.is(Items.OAK_LOG));
            helper.assertTrue(logs != null && Warehouse.of(logs.container()) == Warehouse.Shelf.WOOD, "the logs moved to the wood: "
                    + (logs == null ? "gone" : Warehouse.of(logs.container())) + "; " + says);
            helper.assertTrue(((BaseContainerBlockEntity) logs.container()).getDisplayName().getString().equals("Wood"), "the chest says so");
            cleanUp(helper, colony, owner);
        });
    }
}
