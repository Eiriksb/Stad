package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Fields;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Guide;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for a town that works without the player setting everything up: farmers make their own fields,
 * builders build what they can and get over a cancelled building, lumberjacks plant a grove, shepherds bring animals
 * in, and every citizen says what's stopping them.
 */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayableGameTests {
    private PlayableGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(14, G, 14);
    private static final ResourceLocation COTTAGE = ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage");
    private static final ResourceLocation HUT = ResourceLocation.fromNamespaceAndPath("stad", "medieval/builder_hut");

    private static Colony found(GameTestHelper helper, ServerPlayer owner, BlockPos at, int radius) {
        helper.setBlock(at, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(at), owner.getUUID(),
                owner.getGameProfile().getName(), radius);
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

    private static TownHallBlockEntity hall(GameTestHelper helper, BlockPos at) {
        return (TownHallBlockEntity) helper.getBlockEntity(at);
    }

    /** The lot's floor as grass (it's stone). */
    private static void grass(GameTestHelper helper) {
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.GRASS_BLOCK);
            }
        }
    }

    @GameTest(template = "lot", batch = "play_field", timeoutTicks = 4000)
    public static void farmerMakesTheirOwnField(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        grass(helper);
        Colony colony = found(helper, owner, HALL, 24);
        hall(helper, HALL).storage().addItem(new ItemStack(Items.WHEAT_SEEDS, 20));
        CitizenEntity farmer = worker(helper, colony, Jobs.FARMER, 10, 10);
        helper.succeedWhen(() -> {
            Building field = Fields.of(colony).stream().findFirst().orElse(null);
            helper.assertTrue(field != null, "no field laid out; the farmer says: " + farmer.status().text());
            helper.assertTrue(field.done(), "the builders don't build fields");
            helper.assertTrue(helper.getLevel().getFluidState(Fields.water(field)).isSource(), "no water in the middle");
            int farmland = 0, sown = 0;
            for (BlockPos p : Fields.plots(field)) {
                if (helper.getLevel().getBlockState(p).is(Blocks.FARMLAND)) {
                    farmland++;
                }
                if (helper.getLevel().getBlockState(p.above()).getBlock() instanceof CropBlock) {
                    sown++;
                }
            }
            helper.assertTrue(farmland >= 70, "only " + farmland + " farmland; the farmer says: " + farmer.status().text());
            helper.assertTrue(sown >= 16, "only " + sown + " sown; the farmer says: " + farmer.status().text());
            helper.assertTrue(hall(helper, HALL).storage().countItem(Items.WHEAT_SEEDS) < 20, "the seeds came from the town hall");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "play_seeds", timeoutTicks = 400)
    public static void farmerWithNoSeedsSaysSo(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner, HALL, 8);
        // farmland, but no seeds anywhere and no grass to find them in
        for (int x = 10; x < 13; x++) {
            helper.setBlock(new BlockPos(x, G - 1, 12), Blocks.FARMLAND);
        }
        CitizenEntity farmer = worker(helper, colony, Jobs.FARMER, 10, 10);
        helper.succeedWhen(() -> {
            var s = farmer.status();
            helper.assertTrue(s.problem() && s.text().contains("No seeds"), "the farmer says: " + s.text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "play_build", timeoutTicks = 3000)
    public static void builderBuildsWhatItCanWhileWaiting(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        int pace = StadConfig.BUILDER_TICKS_PER_BLOCK.get();
        StadConfig.BUILDER_TICKS_PER_BLOCK.set(1);
        BlockPos hallAt = new BlockPos(15, G, 15);
        Colony colony = found(helper, owner, hallAt, 16);
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        BlockPos origin = helper.absolutePos(new BlockPos(2, G, 2));
        Placement placement = Placement.of(cottage.blueprint(), origin, Rotation.NONE);
        // everything but the cobblestone (its footing, the first thing it'd build)
        placement.materials().forEach((item, n) -> {
            if (item != Items.COBBLESTONE) {
                ItemStack rest = new ItemStack(item, n);
                while (!rest.isEmpty()) {
                    hall(helper, hallAt).storage().addItem(rest.split(Math.min(rest.getCount(), item.getDefaultMaxStackSize())));
                }
            }
        });
        Building building = colony.addBuilding(cottage.key(), cottage.name(), origin, Rotation.NONE, helper.getLevel().getGameTime());
        CitizenEntity builder = worker(helper, colony, Jobs.BUILDER, 10, 14);
        BoundingBox box = placement.bounds();
        helper.succeedWhen(() -> {
            helper.assertTrue(building.status == Building.Status.WAITING, "status " + building.status + " " + Math.round(building.fractionDone() * 100) + "%");
            helper.assertTrue(building.missing.keySet().equals(java.util.Set.of(Items.COBBLESTONE)), "missing " + building.missing);
            long planks = BlockPos.betweenClosedStream(box).filter(p -> helper.getLevel().getBlockState(p).is(BlockTags.PLANKS)).count();
            helper.assertTrue(planks > 20, "it built ahead with what it had: " + planks + " planks");
            helper.assertTrue(builder.status().problem() && builder.status().text().contains("cobblestone"), "the builder says: " + builder.status().text());
            StadConfig.BUILDER_TICKS_PER_BLOCK.set(pace);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "play_cancel", timeoutTicks = 2400)
    public static void cancellingABuildingFreesTheBuilder(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        int pace = StadConfig.BUILDER_TICKS_PER_BLOCK.get();
        StadConfig.BUILDER_TICKS_PER_BLOCK.set(1);
        BlockPos hallAt = new BlockPos(15, G, 15);
        Colony colony = found(helper, owner, hallAt, 16);
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        Building first = colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(2, G, 2)), Rotation.NONE,
                helper.getLevel().getGameTime());
        CitizenEntity builder = worker(helper, colony, Jobs.BUILDER, 10, 14);
        BuildingDef hut = BuildingPacks.get(HUT).orElseThrow();
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(builder.getUUID().equals(first.builder), "on the cottage first");
            colony.removeBuilding(first.id);
            BlockPos origin = helper.absolutePos(new BlockPos(2, G, 2));
            Placement.of(hut.blueprint(), origin, Rotation.NONE).materials().forEach((item, n) -> {
                ItemStack rest = new ItemStack(item, n);
                while (!rest.isEmpty()) {
                    hall(helper, hallAt).storage().addItem(rest.split(Math.min(rest.getCount(), item.getDefaultMaxStackSize())));
                }
            });
            Building second = colony.addBuilding(hut.key(), hut.name(), origin, Rotation.NONE, helper.getLevel().getGameTime());
            helper.succeedWhen(() -> {
                helper.assertTrue(second.done(), "the hut: " + second.status + " " + Math.round(second.fractionDone() * 100) + "%, the builder says "
                        + builder.status().text());
                StadConfig.BUILDER_TICKS_PER_BLOCK.set(pace);
                cleanUp(helper, colony, owner);
            });
        });
    }

    @GameTest(template = "lot", batch = "play_water")
    public static void buildingsDontGoOnWater(GameTestHelper helper) {
        BuildingDef hut = BuildingPacks.get(HUT).orElseThrow();
        for (int x = 1; x < 10; x++) {
            for (int z = 1; z < 10; z++) {
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.WATER);
            }
        }
        String wet = Planning.ground(helper.getLevel(), hut.blueprint(), helper.absolutePos(new BlockPos(1, G - 1, 1)), Rotation.NONE);
        String dry = Planning.ground(helper.getLevel(), hut.blueprint(), helper.absolutePos(new BlockPos(9, G, 9)), Rotation.NONE);
        helper.assertTrue(wet != null && wet.contains("water"), "on water: " + wet);
        helper.assertTrue(dry == null, "on dry ground: " + dry);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "play_status", timeoutTicks = 400)
    public static void citizensSayWhatsStoppingThem(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner, HALL, 8);
        CitizenEntity cook = worker(helper, colony, Jobs.COOK, 10, 10);
        CitizenEntity idle = worker(helper, colony, null, 8, 10);
        CitizenEntity builder = worker(helper, colony, Jobs.BUILDER, 6, 10);
        helper.succeedWhen(() -> {
            helper.assertTrue(cook.status().problem() && cook.status().text().contains("Nowhere to cook"), "the cook says: " + cook.status().text());
            helper.assertTrue(idle.status().problem() && idle.status().text().contains("No job"), "the idle one says: " + idle.status().text());
            helper.assertTrue(builder.status().text().contains("Nothing to build"), "the builder says: " + builder.status().text());
            helper.assertTrue(Guide.problem(helper.getLevel(), colony, Jobs.COOK) != null, "the guide knows the cook has no stove");
            helper.assertTrue(Guide.steps(helper.getLevel(), colony).getFirst().done(), "someone lives here");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "play_grove", timeoutTicks = 2400)
    public static void lumberjackPlantsAGroveWhenThereAreNoTrees(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        grass(helper);
        Colony colony = found(helper, owner, HALL, 16);
        hall(helper, HALL).storage().addItem(new ItemStack(Items.OAK_SAPLING, 6));
        CitizenEntity lumberjack = worker(helper, colony, Jobs.LUMBERJACK, 10, 10);
        helper.succeedWhen(() -> {
            BlockPos c = helper.absolutePos(HALL);
            long planted = BlockPos.betweenClosedStream(c.offset(-16, -3, -16), c.offset(16, 3, 16))
                    .filter(p -> helper.getLevel().getBlockState(p).is(BlockTags.SAPLINGS)).count();
            helper.assertTrue(planted >= 3, planted + " saplings planted; the lumberjack says: " + lumberjack.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "play_lead", timeoutTicks = 2400)
    public static void shepherdBringsInAnimalsFromNearby(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner, HALL, 5);
        Sheep sheep = helper.spawn(EntityType.SHEEP, 2, G, 2);
        CitizenEntity shepherd = worker(helper, colony, Jobs.SHEPHERD, 12, 12);
        helper.succeedWhen(() -> {
            helper.assertTrue(colony.contains(helper.getLevel().dimension(), sheep.blockPosition()) && !sheep.isLeashed(),
                    "the sheep is at " + helper.relativePos(sheep.blockPosition()) + (sheep.isLeashed() ? " on a lead" : "") + "; the shepherd says: "
                            + shepherd.status().text());
            cleanUp(helper, colony, owner);
        });
    }
}
