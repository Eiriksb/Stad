package dev.eiriksb.stad;

import dev.eiriksb.stad.architect.DesignCheck;
import dev.eiriksb.stad.architect.FreeBuild;
import dev.eiriksb.stad.architect.Project;
import dev.eiriksb.stad.architect.Tables;
import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Workplaces;
import dev.eiriksb.stad.planner.BuildSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** In-game tests for M13: workplaces, and free build in an architect's table's site. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkplaceGameTests {
    private WorkplaceGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(2, G, 2), TABLE = new BlockPos(3, G, 2);

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        helper.assertTrue(f.colony() != null, "no town: " + f.problem());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer... players) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        for (ServerPlayer p : players) {
            helper.getLevel().getServer().getPlayerList().remove(p);
        }
    }

    /** A bundled building, finished (in the town's books; built in the world when {@code build}). */
    private static Building done(GameTestHelper helper, Colony colony, String key, BlockPos at, boolean build) {
        BuildingDef def = BuildingPacks.get(net.minecraft.resources.ResourceLocation.parse(key)).orElseThrow();
        Building b = colony.addBuilding(def.key(), def.name(), helper.absolutePos(at), Rotation.NONE, 0);
        b.status = Building.Status.DONE;
        if (build) {
            for (Placement.Step s : b.placement().orElseThrow().steps()) {
                helper.getLevel().setBlock(s.pos(), s.state(), 2);
            }
        }
        return b;
    }

    private static CitizenRecord worker(Colony colony, String job, String name) {
        CitizenRecord r = colony.addCitizen(UUID.randomUUID(), name, 0);
        r.job = job;
        return r;
    }

    @GameTest(template = "lot", batch = "workplaces_slots", timeoutTicks = 100)
    public static void workersGetWorkplacesWithRoom(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        Building fishery = done(helper, colony, "stad:medieval/fishery", new BlockPos(5, G - 1, 5), false);
        helper.assertTrue(Workplaces.slots(fishery) == 2, "a fishery with two barrels has room for two: " + Workplaces.slots(fishery));
        CitizenRecord a = worker(colony, Jobs.FISHER, "Ada Fisk"), b = worker(colony, Jobs.FISHER, "Bo Fisk"), c = worker(colony, Jobs.FISHER, "Cy Fisk");
        CitizenRecord farmer = worker(colony, Jobs.FARMER, "Dag Aaker");
        Workplaces.assign(colony);
        helper.assertTrue(Workplaces.of(colony, a).isPresent() && Workplaces.of(colony, b).isPresent(), "two fishers placed");
        helper.assertTrue(Workplaces.of(colony, c).isEmpty() && Workplaces.workers(colony, fishery).size() == 2, "the third has no room");
        helper.assertTrue(Workplaces.of(colony, farmer).isEmpty(), "no farmhouse, no workplace");
        helper.assertTrue(Workplaces.move(colony, c, fishery) != null, "full: " + Workplaces.move(colony, c, fishery));
        helper.assertTrue(Workplaces.move(colony, farmer, fishery) != null, "not for a farmer");
        // a farmhouse comes: the farmer gets it; changing job lets go
        Building farmhouse = done(helper, colony, "stad:fjord/gardshus", new BlockPos(5, G - 1, 12), false);
        Workplaces.assign(colony);
        helper.assertTrue(Workplaces.of(colony, farmer).map(x -> x.id == farmhouse.id).orElse(false), "the farmer works from the gardshus");
        a.job = Jobs.FARMER;
        Workplaces.assign(colony);
        helper.assertTrue(Workplaces.of(colony, c).isPresent(), "Ada's place went to Cy once Ada became a farmer");
        helper.assertTrue(Workplaces.describe(colony, farmer).equals("the Gardshus") && "a fishery".equals(Workplaces.wanted(Jobs.FISHER)),
                Workplaces.describe(colony, farmer));
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "workplaces_courier", timeoutTicks = 2400)
    public static void courierCarriesTheHaulOnFromAWorkplace(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Building farmhouse = done(helper, colony, "stad:medieval/farmhouse", new BlockPos(6, G - 1, 8), true);
        ColonyStorage own = Workplaces.storage(helper.getLevel(), farmhouse);
        helper.assertTrue(!own.isEmpty(), "the farmhouse has a chest");
        own.put(new ItemStack(Items.WHEAT, 20));
        CitizenEntity courier = helper.spawn(StadRegistries.CITIZEN.get(), 4, G, 4);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, courier);
        colony.citizen(courier.getUUID()).orElseThrow().job = Jobs.COURIER;
        courier.setHunger(20);
        helper.succeedWhen(() -> {
            TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(HALL);
            helper.assertTrue(hall.storage().countItem(Items.WHEAT) == 20, "the wheat reached the town hall: " + hall.storage().countItem(Items.WHEAT)
                    + ", still at the farmhouse " + Workplaces.storage(helper.getLevel(), farmhouse).count(Items.WHEAT));
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "workplaces_barn", timeoutTicks = 100)
    public static void tableGeneratesAWorkingBarn(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        helper.setBlock(TABLE, StadRegistries.ARCHITECT_TABLE.get());
        Project p = Tables.project(helper.getLevel(), helper.absolutePos(TABLE)).orElseThrow();
        p.setSite(helper.absolutePos(new BlockPos(5, G - 1, 5)), helper.absolutePos(new BlockPos(15, G + 4, 15)));
        p.type = BuildingDef.BARN;
        String json = "{\"name\":\"\",\"type\":\"home\",\"floors\":1,\"roof\":\"gable\",\"walls\":\"oak\",\"frame\":\"spruce\","
                + "\"roof_material\":\"spruce\",\"floor\":\"oak\",\"beds\":0,\"rooms\":[],\"features\":[]}";
        helper.assertTrue(Tables.generate(helper.getLevel(), p, BuildSpec.parse(json), Direction.NORTH) == null, "generated");
        DesignCheck.Result check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a barn, with a loom and hay put in: " + check.rows());
        helper.assertTrue(check.rows().stream().anyMatch(r -> r.what().contains("pen") && !r.met()), "no pen round it yet: " + check.rows());
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "free_build", timeoutTicks = 100)
    public static void freeBuildHappensInTheDraftingWorld(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.setGameMode(GameType.SURVIVAL);
        owner.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        BlockPos stand = helper.absolutePos(new BlockPos(1, G, 16));
        owner.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        Colony colony = found(helper, owner);
        helper.setBlock(TABLE, StadRegistries.ARCHITECT_TABLE.get());
        helper.setBlock(new BlockPos(6, G, 6), Blocks.CHEST);
        Project p = Tables.project(helper.getLevel(), helper.absolutePos(TABLE)).orElseThrow();
        p.setSite(helper.absolutePos(new BlockPos(5, G - 1, 5)), helper.absolutePos(new BlockPos(12, G + 4, 12)));
        p.edits.put(Project.key(3, 1, 3), Blocks.OAK_PLANKS.defaultBlockState()); // in the design before they start
        String problem = FreeBuild.start(owner, helper.getLevel(), helper.absolutePos(TABLE), p);
        helper.assertTrue(problem == null, "started: " + problem);
        ServerLevel draft = FreeBuild.draftLevel(owner.server);
        BlockPos o = FreeBuild.origin(owner);
        helper.assertTrue(draft != null && owner.level() == draft, "in the drafting world: " + owner.level().dimension());
        helper.assertTrue(owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE && owner.getInventory().countItem(Items.DIAMOND) == 0, "creative, with an empty inventory");
        helper.assertTrue(draft.getBlockState(o.offset(3, 1, 3)).is(Blocks.OAK_PLANKS) && draft.getBlockState(o.offset(1, 1, 1)).is(Blocks.CHEST),
                "the design is there to build on, on a copy of the land");
        helper.assertTrue(draft.getBlockState(o.offset(0, -Project.MARGIN - 1, 0)).is(Blocks.BARRIER), "with a floor under it");
        helper.assertTrue(helper.getBlockState(new BlockPos(8, G, 8)).isAir(), "the real site isn't touched");
        // they build (and break a block, and pick things up, and drop something)
        draft.setBlock(o.offset(2, 1, 2), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        draft.setBlock(o.offset(3, 1, 3), Blocks.AIR.defaultBlockState(), 3);
        owner.getInventory().add(new ItemStack(Items.NETHERITE_INGOT, 64));
        ItemEntity drop = new ItemEntity(draft, o.getX() + 4, o.getY() + 2, o.getZ() + 4, new ItemStack(Items.DIAMOND_BLOCK, 64));
        helper.assertTrue(!draft.addFreshEntity(drop), "nothing becomes an item in the drafting world");
        String done = FreeBuild.finish(owner, true);
        helper.assertTrue(done != null && done.contains("Kept"), "kept: " + done);
        helper.assertTrue(p.edits.get(Project.key(2, 1, 2)).is(Blocks.STONE_BRICKS) && p.at(Project.key(3, 1, 3)) == null,
                "what they built is the design (the planks they took away are gone from it): " + p.edits);
        helper.assertTrue(draft.getBlockState(o.offset(2, 1, 2)).isAir() && draft.getBlockState(o.offset(1, 1, 1)).isAir(), "their stretch of the drafting world is cleared");
        helper.assertTrue(owner.level() == helper.getLevel() && owner.blockPosition().equals(stand), "back where they were: " + owner.blockPosition());
        helper.assertTrue(owner.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && owner.getInventory().countItem(Items.DIAMOND) == 3
                && owner.getInventory().countItem(Items.NETHERITE_INGOT) == 0, "their own things back, nothing from the drafting world: diamonds "
                + owner.getInventory().countItem(Items.DIAMOND) + ", netherite " + owner.getInventory().countItem(Items.NETHERITE_INGOT)
                + ", mode " + owner.gameMode.getGameModeForPlayer());
        helper.assertTrue(helper.getBlockState(new BlockPos(7, G, 7)).isAir(), "still nothing built for real");
        // the server stops mid-build: they carry on where they were, and can still throw it away
        helper.assertTrue(FreeBuild.start(owner, helper.getLevel(), helper.absolutePos(TABLE), p) == null, "again");
        BlockPos o2 = FreeBuild.origin(owner);
        draft.setBlock(o2.offset(4, 1, 4), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        FreeBuild.restartForTest(owner);
        helper.assertTrue(FreeBuild.building(owner) && owner.level() == draft && draft.getBlockState(o2.offset(4, 1, 4)).is(Blocks.GOLD_BLOCK),
                "still designing after the restart");
        String cancelled = FreeBuild.finish(owner, false);
        helper.assertTrue(cancelled != null && p.at(Project.key(4, 1, 4)) == null, "thrown away: " + cancelled);
        helper.assertTrue(owner.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && owner.getInventory().countItem(Items.DIAMOND) == 3,
                "their things back");
        helper.assertTrue(FreeBuild.sites().isEmpty(), "nobody building");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }
}
