package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Mine;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.FishGoal;
import dev.eiriksb.stad.colony.Adoption;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** In-game tests for the M11 jobs (cook, fisher, shepherd, miner) and the bundled building packs. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JobsGameTests {
    private JobsGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(14, G, 14);

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        return found(helper, owner, HALL);
    }

    private static Colony found(GameTestHelper helper, ServerPlayer owner, BlockPos at) {
        helper.setBlock(at, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(at), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        helper.assertTrue(f.colony() != null, "no town: " + f.problem());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer owner) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
    }

    private static CitizenEntity worker(GameTestHelper helper, Colony colony, String job, int x, int z) {
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), x, G, z);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        colony.citizen(c.getUUID()).orElseThrow().job = job;
        c.setHunger(20);
        return c;
    }

    private static TownHallBlockEntity hall(GameTestHelper helper) {
        return hall(helper, HALL);
    }

    private static TownHallBlockEntity hall(GameTestHelper helper, BlockPos at) {
        return (TownHallBlockEntity) helper.getBlockEntity(at);
    }

    private static final BlockPos MIDDLE = new BlockPos(9, G, 9);

    private static int carried(CitizenEntity c, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (what.test(c.inventory().getItem(i))) {
                n += c.inventory().getItem(i).getCount();
            }
        }
        return n;
    }

    @GameTest(template = "lot", batch = "jobs_cook", timeoutTicks = 2400)
    public static void cookBakesBreadAndCooksForTheTownHall(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        helper.setBlock(new BlockPos(6, G, 6), Blocks.FURNACE);
        hall(helper).storage().addItem(new ItemStack(Items.WHEAT, 9));
        hall(helper).storage().addItem(new ItemStack(Items.BEEF, 2));
        hall(helper).storage().addItem(new ItemStack(Items.WHEAT_SEEDS, 5)); // not food
        CitizenEntity cook = worker(helper, colony, Jobs.COOK, 10, 10);
        helper.succeedWhen(() -> {
            var s = hall(helper).storage();
            helper.assertTrue(s.countItem(Items.BREAD) == 3 && s.countItem(Items.COOKED_BEEF) == 2,
                    "bread " + s.countItem(Items.BREAD) + ", cooked beef " + s.countItem(Items.COOKED_BEEF) + "; the cook carries "
                            + carried(cook, x -> !x.isEmpty()) + " at " + cook.blockPosition());
            helper.assertTrue(s.countItem(Items.WHEAT) == 0 && s.countItem(Items.BEEF) == 0 && s.countItem(Items.WHEAT_SEEDS) == 5, "all used, seeds left");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "jobs_fish", timeoutTicks = 3000)
    public static void fisherFishesFromTheShore(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        for (int x = 4; x < 8; x++) {
            for (int z = 4; z < 8; z++) {
                helper.setBlock(new BlockPos(x, G - 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.WATER);
            }
        }
        // the game's own fishing loot: fish, mostly
        List<ItemStack> catches = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            catches.addAll(FishGoal.catchAt(helper.getLevel(), helper.absolutePos(new BlockPos(5, G - 1, 5)), new ItemStack(Items.FISHING_ROD)));
        }
        long fish = catches.stream().filter(s -> s.is(ItemTags.FISHES)).count();
        helper.assertTrue(fish >= 20, "mostly fish: " + fish + " of " + catches.size());
        hall(helper).storage().addItem(new ItemStack(Items.FISHING_ROD)); // the town's rod (PLAN.md §6.9)
        CitizenEntity fisher = worker(helper, colony, Jobs.FISHER, 11, 11);
        helper.succeedWhen(() -> {
            helper.assertTrue(fisher.getMainHandItem().is(Items.FISHING_ROD) && hall(helper).storage().countItem(Items.FISHING_ROD) == 0,
                    "the town's rod in hand");
            helper.assertTrue(carried(fisher, s -> !s.isEmpty()) + hall(helper).storage().countItem(Items.COD) + hall(helper).storage().countItem(Items.SALMON) >= 2,
                    "caught: " + carried(fisher, s -> !s.isEmpty()) + " at " + fisher.blockPosition());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "jobs_sheep", timeoutTicks = 2400)
    public static void shepherdShearsAndBreeds(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner, MIDDLE); // the land covers the whole plot, wherever the sheep wander
        hall(helper, MIDDLE).storage().addItem(new ItemStack(Items.WHEAT, 8));
        Sheep a = helper.spawn(EntityType.SHEEP, 5, G, 5);
        Sheep b = helper.spawn(EntityType.SHEEP, 6, G, 5);
        CitizenEntity shepherd = worker(helper, colony, Jobs.SHEPHERD, 10, 10);
        AABB plot = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(18, 8, 18);
        List<String> trace = new ArrayList<>();
        String[] last = {""};
        helper.onEachTick(() -> {
            String now = shepherd.activity().replaceAll(", food.*", "") + " love " + a.isInLove() + "/" + b.isInLove() + " sheared " + a.isSheared() + "/" + b.isSheared()
                    + " day " + helper.getLevel().getDayTime() % 24000 / 1000;
            if (!now.equals(last[0]) && trace.size() < 40) {
                trace.add(helper.getTick() + ": " + now);
                last[0] = now;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(a.isSheared() && b.isSheared(), "sheared " + trace);
            int wool = carried(shepherd, s -> s.is(ItemTags.WOOL)) + hall(helper, MIDDLE).storage().countItem(Items.WHITE_WOOL);
            helper.assertTrue(wool >= 2, "picked up the wool: " + wool + "; lying about: " + helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, plot.inflate(8)).stream().map(e -> e.getItem() + "@" + e.blockPosition()).toList()
                    + "; shepherd " + shepherd.activity() + " at " + shepherd.blockPosition());
            helper.assertTrue(!helper.getLevel().getEntitiesOfClass(Sheep.class, plot, Sheep::isBaby).isEmpty(), "a lamb; in love: " + a.isInLove()
                    + " " + b.isInLove() + ", ages " + a.getAge() + " " + b.getAge() + "; shepherd " + shepherd.activity() + " at " + shepherd.blockPosition()
                    + " carrying " + carried(shepherd, s -> !s.isEmpty()) + ", hall wheat " + hall(helper, MIDDLE).storage().countItem(Items.WHEAT) + " " + trace);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "jobs_butcher", timeoutTicks = 2400)
    public static void shepherdButchersTheExtras(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner, MIDDLE);
        int herd = StadConfig.HERD_SIZE.get();
        StadConfig.HERD_SIZE.set(2);
        for (int i = 0; i < 8; i++) {
            Chicken c = helper.spawn(EntityType.CHICKEN, 3 + i, G, 4);
            c.setAge(0);
        }
        Chicken pet = helper.spawn(EntityType.CHICKEN, 3, G, 8);
        pet.setCustomName(net.minecraft.network.chat.Component.literal("Henrietta"));
        CitizenEntity shepherd = worker(helper, colony, Jobs.SHEPHERD, 10, 10);
        AABB plot = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(18, 8, 18);
        helper.succeedWhen(() -> {
            int left = helper.getLevel().getEntitiesOfClass(Chicken.class, plot, c -> c.isAlive() && !c.hasCustomName()).size();
            helper.assertTrue(left <= 6, "down to the herd and four more: " + left);
            helper.assertTrue(pet.isAlive(), "named animals are pets");
            helper.assertTrue(carried(shepherd, s -> s.is(Items.CHICKEN)) + hall(helper, MIDDLE).storage().countItem(Items.CHICKEN) >= 2, "the meat");
            StadConfig.HERD_SIZE.set(herd);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "jobs_mine", timeoutTicks = 6000)
    public static void minerDigsASpiralShaft(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        level.setDayTime(1000);
        Colony colony = found(helper, owner);
        int depth = StadConfig.MINE_DEPTH.get();
        StadConfig.MINE_DEPTH.set(4);
        // rock under the plot, with a spring in it
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                for (int y = -4; y < G - 1; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BuildingDef def = BuildingPacks.get(ResourceLocation.fromNamespaceAndPath("stad", "medieval/mine")).orElseThrow();
        BlockPos origin = helper.absolutePos(new BlockPos(2, G, 2));
        Placement placement = Placement.of(def.blueprint(), origin, Rotation.NONE);
        for (Placement.Step s : placement.steps()) {
            level.setBlock(s.pos(), s.state(), 2);
        }
        Building mine = colony.addBuilding(def.key(), def.name(), origin, Rotation.NONE, level.getGameTime());
        mine.status = Building.Status.DONE;
        BlockPos top = Mine.shafts(colony).getFirst();
        helper.assertTrue(top.equals(origin.offset(4, -1, 4)), "the shaft is under the marker: " + top);
        BlockPos spring = top.offset(3, -2, 0); // just outside the shaft, two layers down
        level.setBlock(spring, Blocks.WATER.defaultBlockState(), 3);
        CitizenEntity miner = worker(helper, colony, Jobs.MINER, 12, 12);
        helper.succeedWhen(() -> {
            helper.assertTrue(Mine.depth(level, top) == 4, "dug " + Mine.depth(level, top) + " layers; the miner is at " + miner.blockPosition()
                    + " (shaft top " + top + "): " + miner.activity() + ", next " + Mine.next(level, top, miner.blockPosition()));
            for (int d = 0; d < 4; d++) {
                int[] step = Mine.step(d);
                helper.assertTrue(!level.getBlockState(top.offset(step[0], -d, step[1])).isAir(), "the step in layer " + d);
            }
            helper.assertTrue(level.getBlockState(spring).is(Blocks.COBBLESTONE), "the spring walled off: " + level.getBlockState(spring));
            int stone = carried(miner, s -> s.is(Items.COBBLESTONE)) + hall(helper).storage().countItem(Items.COBBLESTONE);
            helper.assertTrue(stone >= 60, "brought up the stone: " + stone);
            StadConfig.MINE_DEPTH.set(depth);
            cleanUp(helper, colony, owner);
        });
    }

    /** A mine on a slope: where the shaft starts above the ground, the miner puts the steps in first. */
    @GameTest(template = "empty", batch = "jobs_mine_slope")
    public static void shaftsAboveTheGroundGetSteps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos top = helper.absolutePos(new BlockPos(3, 3, 3));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = 0; y < 3; y++) {
                    level.setBlock(top.offset(dx, -2 - y, dz), Blocks.STONE.defaultBlockState(), 2);
                }
                level.setBlock(top.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(top.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int d = 0; d < 2; d++) {
            Mine.Task t = Mine.task(level, top, top).orElseThrow();
            int[] step = Mine.step(d);
            helper.assertTrue(t.step() && t.pos().equals(top.offset(step[0], -d, step[1])), "a step first, in layer " + d + ": " + t);
            level.setBlock(t.pos(), Blocks.COBBLESTONE.defaultBlockState(), 2);
        }
        Mine.Task dig = Mine.task(level, top, top).orElseThrow();
        helper.assertTrue(!dig.step() && dig.pos().getY() == top.getY() - 2, "then digging in the ground: " + dig);
        helper.succeed();
    }

    /** Every building in the bundled packs is what it says it is: a home has a bed, a warehouse its chests... */
    @GameTest(template = "lot", batch = "jobs_packs", timeoutTicks = 200)
    public static void bundledBuildingsMeetTheirNeeds(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        for (int y = 14; y <= 20; y++) { // open sky, so outdoors is outdoors
            for (int x = -1; x <= 19; x++) {
                for (int z = -1; z <= 19; z++) {
                    if (level.getBlockState(helper.absolutePos(new BlockPos(x, y, z))).is(Blocks.BARRIER)) {
                        level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        Colony colony = found(helper, owner, new BlockPos(15, G, 3));
        List<String> checked = new ArrayList<>();
        try {
            for (BuildingDef def : BuildingPacks.all()) {
                String pack = def.key().getPath().split("/")[0];
                if (!def.key().getNamespace().equals("stad") || !List.of("medieval", "oasis").contains(pack) || def.type().equals(BuildingDef.MINE)) {
                    continue;
                }
                BlockPos origin = helper.absolutePos(new BlockPos(2, G, 2));
                Placement placement = Placement.of(def.blueprint(), origin, Rotation.NONE);
                for (Placement.Step s : placement.steps()) {
                    level.setBlock(s.pos(), s.state(), 2);
                }
                BlockPos inside = BlockPos.betweenClosedStream(placement.bounds()).filter(p -> p.getY() == origin.getY() + 1
                        && p.getX() > origin.getX() + 1 && p.getZ() > origin.getZ() + 1 && level.getBlockState(p).isAir()
                        && level.getBlockState(p.above()).isAir()).findFirst().orElseThrow().immutable();
                Adoption.Check check = Adoption.check(level, colony, inside, def.type());
                helper.assertTrue(check.problem() == null && check.missing().isEmpty(), def.key() + " as " + def.type() + ": " + check.problem() + " " + check.missing());
                checked.add(def.key().getPath());
                for (BlockPos p : BlockPos.betweenClosed(placement.bounds().minX(), placement.bounds().minY(), placement.bounds().minZ(),
                        placement.bounds().maxX(), placement.bounds().maxY(), placement.bounds().maxZ())) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            helper.assertTrue(checked.size() >= 10, "checked " + checked);
        } finally {
            cleanUp(helper, colony, owner);
        }
        helper.succeed();
    }
}
