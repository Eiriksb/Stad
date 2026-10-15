package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.agent.DayPlan;
import dev.eiriksb.stad.agent.Leadership;
import dev.eiriksb.stad.agent.Social;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Request;
import dev.eiriksb.stad.colony.Requests;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.api.Memory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** In-game tests: {@code ./gradlew :mod:runGameTestServer}. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StadGameTests {
    private StadGameTests() {}

    @GameTest(template = "empty")
    public static void theyWillTalkIsRunning(GameTestHelper helper) {
        helper.assertTrue(TheyWillTalkApi.get().running(), "They Will Talk isn't running in this world");
        helper.assertTrue(VillagerKind.byId("stad") == Stad.CITIZEN, "the citizen kind isn't registered with They Will Talk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensCanTalk(GameTestHelper helper) {
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        helper.assertTrue(TheyWillTalkApi.get().canTalk(citizen), "They Will Talk can't talk to a citizen");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensKeepTheirLooks(GameTestHelper helper) {
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        citizen.readAdditionalSaveData(looks(true, 5));
        dev.eiriksb.stad.citizen.Looks looks = dev.eiriksb.stad.citizen.Looks.random(net.minecraft.util.RandomSource.create(3));
        citizen.setLooks(looks);
        citizen.setAgeYears(70);
        CompoundTag saved = new CompoundTag();
        citizen.addAdditionalSaveData(saved);
        CitizenEntity copy = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        copy.readAdditionalSaveData(saved);
        helper.assertTrue(copy.isFemale() && copy.looks().equals(looks), "looks weren't saved: female=" + copy.isFemale() + " " + copy.looks());
        helper.assertTrue(copy.shownLooks().born(dev.eiriksb.stad.citizen.Looks.GREY) == 3 && copy.shownLooks().style() == looks.style(),
                "the client sees them grey at 70: " + copy.shownLooks());
        var r = net.minecraft.util.RandomSource.create(9);
        var mum = dev.eiriksb.stad.citizen.Looks.random(r).withBorn(dev.eiriksb.stad.citizen.Looks.SKIN, 10);
        var dad = dev.eiriksb.stad.citizen.Looks.random(r).withBorn(dev.eiriksb.stad.citizen.Looks.SKIN, 50);
        for (int i = 0; i < 20; i++) {
            var child = dev.eiriksb.stad.citizen.Looks.child(mum, dad, r);
            int skin = child.born(dev.eiriksb.stad.citizen.Looks.SKIN);
            helper.assertTrue(skin > 15 && skin < 45, "skin between the parents': " + skin);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensRememberRecallAndGossip(GameTestHelper helper) {
        MemoryApi memory = TheyWillTalkApi.get().memory();
        UUID astrid = UUID.randomUUID();
        UUID bjorn = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Subject aboutPlayer = Subject.player(player);
        memory.remember(NewMemory.of(astrid, aboutPlayer, "Eirik promised to fix the roof of my bakery").withKind("promise").withImportance(7)).join();
        memory.remember(NewMemory.of(astrid, aboutPlayer, "Eirik talked about the weather").withImportance(2)).join();
        memory.remember(NewMemory.of(astrid, Subject.NONE, "The harvest was poor this year").withImportance(5)).join();

        List<Memory> recalled = memory.recall(astrid, "Did anyone ever look at the bakery roof?", 2).join();
        helper.assertTrue(!recalled.isEmpty() && recalled.getFirst().text().contains("roof"), "recall missed the roof: " + recalled);
        helper.assertTrue(memory.about(astrid, aboutPlayer, 10).join().size() == 2, "memories about the player");

        int told = memory.tell(astrid, bjorn, aboutPlayer, 5, 3).join();
        helper.assertTrue(told == 1, "passed on " + told + " memories, expected only the promise");
        Memory heard = memory.about(bjorn, aboutPlayer, 5).join().getFirst();
        helper.assertTrue(heard.hearsay() && heard.importance() == 6 && heard.text().contains("roof"), "hearsay copy: " + heard);
        helper.assertTrue(memory.tell(astrid, bjorn, aboutPlayer, 5, 3).join() == 0, "told the same thing twice");

        memory.adjustRelation(astrid, bjorn, 20, 1);
        memory.setRelationKind(astrid, bjorn, "friend");
        Relation rel = memory.relation(astrid, bjorn).join();
        helper.assertTrue(rel.affinity() == 20 && rel.familiarity() == 1 && "friend".equals(rel.kind()), "relation: " + rel);
        helper.assertTrue(memory.relation(bjorn, astrid).join().familiarity() == 0, "relations go one way");
        helper.succeed();
    }

    // ---- colonies (M2) ---------------------------------------------------------------------------------------------

    /** Test colonies claim only their own test room, so tests running side by side don't overlap. */
    private static final int TEST_RADIUS = 4;

    /**
     * Test structures are placed one block above the test's origin, so a template's floor (its y = 0) is at y = 1 and
     * things stand at y = 2.
     */
    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(4, G, 4);

    private static Colony foundHere(GameTestHelper helper, ServerPlayer owner) {
        return foundAt(helper, owner, HALL);
    }

    private static Colony foundAt(GameTestHelper helper, ServerPlayer owner, BlockPos hall) {
        return foundAt(helper, owner, hall, TEST_RADIUS);
    }

    private static Colony foundAt(GameTestHelper helper, ServerPlayer owner, BlockPos hall, int radius) {
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(hall),
                owner.getUUID(), owner.getGameProfile().getName(), radius);
        helper.assertTrue(f.colony() != null, "couldn't found a colony: " + f.problem());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer... players) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        for (ServerPlayer p : players) {
            helper.getLevel().getServer().getPlayerList().remove(p);
        }
    }

    @GameTest(template = "room")
    public static void foundingClaimsLandAndKeepsOthersAway(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer rival = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        helper.assertTrue(colony.contains(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, G, 6))), "claims its land");
        helper.assertTrue(colony.rank(owner.getUUID()) == Rank.OWNER && colony.rank(rival.getUUID()) == Rank.VISITOR, "ranks");
        ColonyManager.Founding second = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(),
                helper.absolutePos(new BlockPos(6, G, 6)), rival.getUUID(), "rival", TEST_RADIUS);
        helper.assertTrue(second.colony() == null, "a second town overlapping the first was founded");
        ColonyManager.Founding again = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(),
                helper.absolutePos(HALL).offset(500, 0, 500), owner.getUUID(), "owner", TEST_RADIUS);
        helper.assertTrue(again.colony() == null, "one player founded two towns");
        cleanUp(helper, colony, owner, rival);
        helper.succeed();
    }

    @GameTest(template = "room")
    public static void ordersCheckRankAndAreLogged(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer visitor = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);

        Order.Result refused = OrderService.issue(new Order(visitor, OrderKind.INVITE, citizen));
        helper.assertTrue(refused.outcome() == Order.Outcome.NOT_ALLOWED, "a visitor invited someone: " + refused);
        Order.Result invited = OrderService.issue(new Order(owner, OrderKind.INVITE, citizen));
        helper.assertTrue(invited.done(), "the owner's invite: " + invited);
        helper.assertTrue(colony.hasCitizen(citizen.getUUID()) && citizen.colony().orElse(null) == colony, "citizen joined");
        helper.assertTrue(OrderService.issue(new Order(owner, OrderKind.INVITE, citizen)).outcome() == Order.Outcome.INVALID, "invited twice");
        helper.assertTrue(colony.orders().size() == 3 && colony.orders().getFirst().kind() == OrderKind.INVITE, "every order is logged");

        helper.assertTrue(OrderService.issue(new Order(owner, OrderKind.DISMISS, citizen)).done(), "dismiss");
        helper.assertTrue(!colony.hasCitizen(citizen.getUUID()) && citizen.colony().isEmpty(), "citizen left");
        cleanUp(helper, colony, owner, visitor);
        helper.succeed();
    }

    @GameTest(template = "room")
    public static void homelessCitizensGetFreeBeds(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        BlockPos foot = new BlockPos(2, G, 6);
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(foot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        CitizenEntity first = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        CitizenEntity second = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 3);
        ColonyManager manager = ColonyManager.get(helper.getLevel().getServer());
        manager.join(colony, first);
        manager.join(colony, second);

        Homes.update(helper.getLevel(), colony);
        BlockPos head = helper.absolutePos(foot.north());
        long homed = colony.citizens().stream().filter(c -> head.equals(c.home)).count();
        helper.assertTrue(homed == 1, "one bed, one home: " + homed);
        helper.assertTrue(Homes.freeBeds(helper.getLevel(), colony) == 0, "no beds left");

        helper.setBlock(foot, Blocks.AIR);
        helper.setBlock(foot.north(), Blocks.AIR);
        Homes.update(helper.getLevel(), colony);
        helper.assertTrue(colony.citizens().stream().noneMatch(c -> c.home != null), "the bed is gone, so is the home");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "room", timeoutTicks = 400)
    public static void hungryCitizensEatFromTheTownHall(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        Colony colony = foundHere(helper, owner);
        TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(HALL);
        hall.storage().setItem(0, new ItemStack(Items.BREAD, 3));
        CitizenEntity citizen = helper.spawn(StadRegistries.CITIZEN.get(), 1, G, 1);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, citizen);
        citizen.setHunger(3);
        helper.succeedWhen(() -> {
            helper.assertTrue(citizen.hunger() > 3, "still hungry: " + citizen.hunger());
            helper.assertTrue(hall.storage().getItem(0).getCount() < 3, "no bread was taken");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "empty")
    public static void coloniesSurviveSaving(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ColonyManager manager = ColonyManager.get(helper.getLevel().getServer());
        Colony colony = manager.found(helper.getLevel(), helper.absolutePos(BlockPos.ZERO), owner.getUUID(), "Owner", 1).colony();
        helper.assertTrue(colony != null, "found");
        UUID officer = UUID.randomUUID();
        colony.setRank(officer, "Officer", Rank.OFFICER);
        colony.rename("Testvik");
        CompoundTag saved = manager.save(new CompoundTag(), helper.getLevel().registryAccess());
        manager.dissolve(helper.getLevel().getServer(), colony);
        CompoundTag colonyTag = saved.getList("Colonies", 10).stream().map(t -> (CompoundTag) t)
                .filter(t -> t.getString("Name").equals("Testvik")).findFirst().orElseThrow();
        helper.assertTrue(colonyTag.getList("Members", 10).size() == 2, "members saved");
        helper.assertTrue(colonyTag.getUUID("Owner").equals(owner.getUUID()), "owner saved");
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }

    // ---- buildings (M3) ---------------------------------------------------------------------------------------------

    private static final ResourceLocation HUT = ResourceLocation.fromNamespaceAndPath("stad", "medieval/builder_hut");
    private static final ResourceLocation COTTAGE = ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage");

    @GameTest(template = "empty")
    public static void theMedievalPackLoads(GameTestHelper helper) {
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElse(null);
        helper.assertTrue(cottage != null, "no cottage in " + BuildingPacks.all().stream().map(BuildingDef::key).toList());
        helper.assertTrue(cottage.type().equals(BuildingDef.HOME), "cottage type " + cottage.type());
        helper.assertTrue(cottage.blueprint().size().equals(new net.minecraft.core.Vec3i(7, 8, 9)), "size " + cottage.blueprint().size());
        var materials = Placement.of(cottage.blueprint(), BlockPos.ZERO, Rotation.NONE).materials();
        helper.assertTrue(materials.get(Items.RED_BED) == 1 && materials.get(Items.BLUE_BED) == 1, "a bed costs one bed item: " + materials);
        helper.assertTrue(materials.get(Items.OAK_DOOR) == 1, "a door costs one door: " + materials.get(Items.OAK_DOOR));
        helper.assertTrue(BuildingPacks.get(HUT).isPresent() && BuildingPacks.all().size() >= 3, "all three buildings");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rotatedBuildingsStayWhole(GameTestHelper helper) {
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        for (Rotation r : Rotation.values()) {
            Placement p = Placement.of(cottage.blueprint(), BlockPos.ZERO, r);
            var box = p.bounds();
            for (Placement.Step s : p.steps()) {
                helper.assertTrue(box.isInside(s.pos()), r + ": " + s.pos() + " outside " + box);
                if (s.state().getBlock() instanceof BedBlock && s.state().getValue(BedBlock.PART) == BedPart.FOOT) {
                    BlockPos head = s.pos().relative(s.state().getValue(BedBlock.FACING));
                    helper.assertTrue(p.steps().stream().anyMatch(o -> o.pos().equals(head) && o.state().getBlock() == s.state().getBlock()),
                            r + ": bed at " + s.pos() + " lost its head");
                }
            }
        }
        helper.assertTrue(Placement.facingViewer(Direction.SOUTH) == Rotation.NONE && Placement.facingViewer(Direction.NORTH) == Rotation.CLOCKWISE_180,
                "the door faces whoever plans it");
        helper.succeed();
    }

    /** The yard (20 x 12, stone floor) has the town hall in a corner and room for a building. */
    private static final BlockPos YARD_HALL = new BlockPos(2, G, 2);
    private static final BlockPos YARD_SITE = new BlockPos(9, G, 1);

    @GameTest(template = "yard", timeoutTicks = 6000)
    public static void builderBuildsTheHutFromTownStorage(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        int pace = StadConfig.BUILDER_TICKS_PER_BLOCK.get();
        StadConfig.BUILDER_TICKS_PER_BLOCK.set(1);
        helper.setBlock(YARD_HALL, StadRegistries.TOWN_HALL.get());
        Colony colony = foundAt(helper, owner, YARD_HALL);
        TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(YARD_HALL);
        BuildingDef hut = BuildingPacks.get(HUT).orElseThrow();
        BlockPos origin = helper.absolutePos(YARD_SITE);
        Placement placement = Placement.of(hut.blueprint(), origin, Rotation.NONE);
        placement.materials().forEach((item, n) -> {
            ItemStack rest = new ItemStack(item, n);
            while (!rest.isEmpty()) {
                ItemStack part = rest.split(Math.min(rest.getCount(), item.getDefaultMaxStackSize()));
                ItemStack left = hall.storage().addItem(part);
                helper.assertTrue(left.isEmpty(), "the town hall is too small for the hut's materials");
            }
        });
        Building building = colony.addBuilding(hut.key(), hut.name(), origin, Rotation.NONE, helper.getLevel().getGameTime());
        CitizenEntity builder = helper.spawn(StadRegistries.CITIZEN.get(), 5, G, 6);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, builder);
        Order.Result hired = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, builder, Jobs.BUILDER));
        helper.assertTrue(hired.done(), "hiring the builder: " + hired);
        BlockPos doorAt = origin.offset(3, 1, 0);
        BlockPos chestAt = origin.offset(2, 1, 5);
        helper.succeedWhen(() -> {
            helper.assertTrue(building.done(), "building: " + building.status + " " + Math.round(building.fractionDone() * 100) + "% "
                    + building.missing + " builder at " + builder.blockPosition());
            helper.assertTrue(helper.getLevel().getBlockState(doorAt).is(Blocks.OAK_DOOR), "no door");
            helper.assertTrue(helper.getLevel().getBlockState(chestAt).is(Blocks.CHEST), "no chest");
            helper.assertTrue(hall.storage().countItem(Items.COBBLESTONE) == 0, "cobblestone left over");
            StadConfig.BUILDER_TICKS_PER_BLOCK.set(pace);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "yard", timeoutTicks = 1200)
    public static void builderWaitsForMaterialsAndSaysWhat(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        helper.setBlock(YARD_HALL, StadRegistries.TOWN_HALL.get());
        Colony colony = foundAt(helper, owner, YARD_HALL);
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        Building building = colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(YARD_SITE), Rotation.NONE,
                helper.getLevel().getGameTime());
        CitizenEntity builder = helper.spawn(StadRegistries.CITIZEN.get(), 5, G, 6);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, builder);
        colony.citizen(builder.getUUID()).orElseThrow().job = Jobs.BUILDER;
        helper.succeedWhen(() -> {
            helper.assertTrue(building.status == Building.Status.WAITING, "status " + building.status);
            helper.assertTrue(building.missing.getOrDefault(Items.COBBLESTONE, 0) == 63, "missing " + building.missing);
            Requests.update(helper.getLevel(), colony);
            var request = colony.request("minecraft:cobblestone", colony.center);
            helper.assertTrue(request.isPresent() && request.get().count == 63, "the town asks for it: " + colony.requests().stream().map(Request::label).toList());
            cleanUp(helper, colony, owner);
        });
    }

    // ---- jobs, logistics and plans (M4) ---------------------------------------------------------------------------

    /** For job tests: the town hall at the back of the yard, its land covering the yard. */
    private static final BlockPos YARD_MID = new BlockPos(10, G, 9);
    private static final int YARD_RADIUS = 8;

    private static CitizenEntity hire(GameTestHelper helper, Colony colony, String job, int x, int z) {
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), x, G, z);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        colony.citizen(c.getUUID()).orElseThrow().job = job;
        return c;
    }

    @GameTest(template = "yard", timeoutTicks = 3000, batch = "jobs")
    public static void farmerHarvestsReplantsAndDelivers(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        helper.setBlock(YARD_MID, StadRegistries.TOWN_HALL.get());
        Colony colony = foundAt(helper, owner, YARD_MID, YARD_RADIUS);
        TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(YARD_MID);
        BlockPos[] field = {new BlockPos(6, G - 1, 4), new BlockPos(7, G - 1, 4), new BlockPos(8, G - 1, 4)};
        for (BlockPos f : field) {
            helper.setBlock(f, Blocks.FARMLAND);
            helper.setBlock(f.above(), ((net.minecraft.world.level.block.CropBlock) Blocks.WHEAT).getStateForAge(7));
        }
        CitizenEntity farmer = hire(helper, colony, Jobs.FARMER, 7, 6);
        helper.succeedWhen(() -> {
            for (BlockPos f : field) {
                var crop = helper.getBlockState(f.above());
                helper.assertTrue(crop.is(Blocks.WHEAT) && !((net.minecraft.world.level.block.CropBlock) Blocks.WHEAT).isMaxAge(crop),
                        "not harvested and replanted at " + f.above() + ": " + crop);
            }
            helper.assertTrue(hall.storage().countItem(Items.WHEAT) >= 3, "wheat delivered: " + hall.storage().countItem(Items.WHEAT));
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "yard", timeoutTicks = 3000, batch = "jobs")
    public static void lumberjackFellsTreesButNotLogWalls(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        helper.setBlock(YARD_MID, StadRegistries.TOWN_HALL.get());
        Colony colony = foundAt(helper, owner, YARD_MID, YARD_RADIUS);
        TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(YARD_MID);
        hall.storage().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_AXE)); // the town's axe (PLAN.md §6.9)
        BlockPos trunk = new BlockPos(15, G, 3);
        helper.setBlock(trunk.below(), Blocks.DIRT);
        for (int y = 0; y < 4; y++) {
            helper.setBlock(trunk.above(y), Blocks.OAK_LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 3; dy <= 4; dy++) {
                    BlockPos l = trunk.offset(dx, dy, dz);
                    if (!(dx == 0 && dz == 0 && dy == 3)) {
                        helper.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1));
                    }
                }
            }
        }
        BlockPos pillar = new BlockPos(17, G, 5);
        helper.setBlock(pillar.below(), Blocks.DIRT);
        for (int y = 0; y < 3; y++) {
            helper.setBlock(pillar.above(y), Blocks.SPRUCE_LOG);
        }
        hire(helper, colony, Jobs.LUMBERJACK, 13, 6);
        helper.succeedWhen(() -> {
            for (int y = 0; y < 4; y++) {
                helper.assertTrue(!helper.getBlockState(trunk.above(y)).is(Blocks.OAK_LOG), "the tree still stands at " + trunk.above(y));
            }
            helper.assertTrue(hall.storage().countItem(Items.OAK_LOG) >= 4, "logs delivered: " + hall.storage().countItem(Items.OAK_LOG));
            for (int y = 0; y < 3; y++) {
                helper.assertTrue(helper.getBlockState(pillar.above(y)).is(Blocks.SPRUCE_LOG), "the log pillar was chopped");
            }
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "yard", timeoutTicks = 3000, batch = "jobs")
    public static void courierBringsFoodFromTheWarehouse(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        helper.setBlock(YARD_MID, StadRegistries.TOWN_HALL.get());
        Colony colony = foundAt(helper, owner, YARD_MID, YARD_RADIUS);
        TownHallBlockEntity hall = (TownHallBlockEntity) helper.getBlockEntity(YARD_MID);
        BuildingDef warehouse = BuildingPacks.get(ResourceLocation.fromNamespaceAndPath("stad", "medieval/warehouse")).orElseThrow();
        Building w = colony.addBuilding(warehouse.key(), warehouse.name(), helper.absolutePos(new BlockPos(1, G, 1)), Rotation.NONE,
                helper.getLevel().getGameTime());
        w.status = Building.Status.DONE;
        BlockPos chest = new BlockPos(3, G, 3);
        helper.setBlock(chest, Blocks.CHEST);
        ((net.minecraft.world.Container) helper.getBlockEntity(chest)).setItem(0, new ItemStack(Items.BREAD, 16));
        hire(helper, colony, Jobs.COURIER, 6, 6);
        Requests.update(helper.getLevel(), colony);
        helper.assertTrue(colony.request(Request.FOOD, colony.center).isPresent(), "the town hall asks for food");
        helper.succeedWhen(() -> {
            helper.assertTrue(hall.storage().countItem(Items.BREAD) >= 8, "bread in the town hall: " + hall.storage().countItem(Items.BREAD));
            helper.assertTrue(colony.request(Request.FOOD, colony.center).isEmpty(), "the request is still open");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "empty")
    public static void dayPlansAreReadCarefully(GameTestHelper helper) {
        String json = """
                {"intentions": [{"text": "Finish the cottage roof", "kind": "work", "with": ""},
                                {"text": "Supper with Ingrid", "kind": "socialize", "with": "ingrid"},
                                {"text": "Nap in the afternoon", "kind": "rest", "with": ""}], "mood": "content"}""";
        DayPlan plan = DayPlan.parse(json, List.of("Ingrid Berg", "Leif Dal"));
        helper.assertTrue(plan.intentions().size() == 3 && plan.rest(), "plan " + plan);
        helper.assertTrue("Ingrid Berg".equals(plan.visit()), "visit " + plan.visit());
        DayPlan stranger = DayPlan.parse(json.replace("\"ingrid\"", "\"Herobrine\""), List.of("Ingrid Berg"));
        helper.assertTrue(stranger.visit() == null, "visits only townsfolk: " + stranger.visit());
        helper.succeed();
    }

    // ---- leadership and social life (M5) ----------------------------------------------------------------------

    @GameTest(template = "room", batch = "leadership")
    public static void citizensFollowGoodLeadersAndBargainWithBadOnes(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        CitizenRecord record = colony.citizen(c.getUUID()).orElseThrow();

        colony.setStanding(owner.getUUID(), 60);
        Order.Result liked = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, c, Jobs.FARMER));
        helper.assertTrue(liked.outcome() == Order.Outcome.ACCEPTED, "a respected leader is obeyed: " + liked);

        record.job = null;
        colony.setStanding(owner.getUUID(), -80);
        Order.Result distrust = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, c, Jobs.FARMER));
        helper.assertTrue(distrust.outcome() == Order.Outcome.REFUSED && record.condition == null,
                "a despised leader gets a flat no, no bargaining: " + distrust);
        colony.setStanding(owner.getUUID(), -20);
        c.setHunger(2);
        Order.Result refused = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, c, Jobs.FARMER));
        helper.assertTrue(refused.outcome() == Order.Outcome.REFUSED && refused.reason().contains("starving"), "refused: " + refused);
        helper.assertTrue("food".equals(record.condition) && Jobs.FARMER.equals(record.pendingJob) && record.job == null, "bargains for food");
        c.setHunger(20);
        Leadership.checkConditions(helper.getLevel(), colony);
        helper.assertTrue(Jobs.FARMER.equals(record.job) && record.condition == null, "keeps their word once fed: " + record.job);

        record.job = null;
        StadConfig.LEADERSHIP.set(StadConfig.Leadership.LOYAL);
        colony.setStanding(owner.getUUID(), -100);
        Order.Result loyal = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, c, Jobs.FARMER));
        StadConfig.LEADERSHIP.set(StadConfig.Leadership.NORMAL);
        helper.assertTrue(loyal.outcome() == Order.Outcome.GRUDGING && c.grudging(), "loyal citizens grumble but obey: " + loyal);
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "room", batch = "leadership")
    public static void theMorningReviewJudgesTheLeaders(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity hungry = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        CitizenEntity miserable = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 3);
        ColonyManager manager = ColonyManager.get(helper.getLevel().getServer());
        manager.join(colony, hungry);
        manager.join(colony, miserable);
        colony.citizen(hungry.getUUID()).orElseThrow().wentHungry = true;
        CitizenRecord sad = colony.citizen(miserable.getUUID()).orElseThrow();
        sad.happiness = 10;
        sad.unhappyDays = 2;
        colony.citizen(hungry.getUUID()).orElseThrow().happiness = 20;
        colony.setStanding(owner.getUUID(), -40);
        long day = helper.getLevel().getDayTime() / 24000 + 1;
        helper.getLevel().setDayTime(day * 24000 + 1000);
        colony.lastReviewDay = day - 1;
        Leadership.review(helper.getLevel(), colony);
        var standing = colony.standing(owner.getUUID());
        helper.assertTrue(standing.events().stream().anyMatch(e -> e.cause().contains(hungry.getName().getString()) && e.cause().contains("hungry")),
                "a hungry citizen counts against the leader: " + standing.events());
        helper.assertTrue(!colony.hasCitizen(miserable.getUUID()) && miserable.colony().isEmpty(), "three miserable days and they leave");
        helper.assertTrue(colony.onStrike(day), "an unhappy town with a resented leader strikes");
        helper.assertTrue(!hungry.canWork(), "nobody works during a strike");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "room", batch = "leadership")
    public static void hurtingCitizensCostsStanding(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        c.hurt(helper.getLevel().damageSources().playerAttack(owner), 1f);
        var standing = colony.standing(owner.getUUID());
        helper.assertTrue(standing.score() < 0 && standing.events().getFirst().cause().startsWith("you attacked"), "standing " + standing.events());
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "room", batch = "leadership")
    public static void whatCitizensMakeOfAConversationMovesStanding(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        var turn = new dev.eiriksb.theywilltalk.api.TurnInfo(c.getUUID(), "Gunnar Kvam", Stad.CITIZEN, owner.getUUID(), "Owner",
                "I'll bring the stone myself", null, "en", "text", new dev.eiriksb.theywilltalk.villager.VillagerFacts());
        var field = new Leadership.RespectField();
        helper.assertTrue(field.appliesTo(turn), "asked about citizens");
        field.apply(turn, com.google.gson.JsonParser.parseString("{\"change\": 2, \"why\": \"you promised me stone walls\"}"));
        helper.succeedWhen(() -> {
            var s = colony.standing(owner.getUUID());
            helper.assertTrue(s.score() == 4 && s.events().getFirst().cause().equals("you promised me stone walls"), "standing " + s.events());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "room", batch = "leadership")
    public static void couplesCourtMarryAndRaiseChildren(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = foundHere(helper, owner);
        CitizenEntity a = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        CitizenEntity b = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 3);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, a);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, b);
        CitizenRecord ra = colony.citizen(a.getUUID()).orElseThrow(), rb = colony.citizen(b.getUUID()).orElseThrow();

        Social.decide(helper.getLevel(), colony, ra, rb, new Relation(ra.uuid, rb.uuid, rb.name, 45, 5, null, 0),
                new Relation(rb.uuid, ra.uuid, ra.name, 50, 5, null, 0));
        helper.assertTrue(ra.spouse == null, "courting first, not married at once");
        Social.decide(helper.getLevel(), colony, ra, rb, new Relation(ra.uuid, rb.uuid, rb.name, 70, 10, "partner", 0),
                new Relation(rb.uuid, ra.uuid, ra.name, 65, 10, "partner", 0));
        helper.assertTrue(rb.uuid.equals(ra.spouse) && ra.uuid.equals(rb.spouse), "they married");

        CitizenEntity baby = Social.haveChild(helper.getLevel(), colony, ra, rb);
        helper.assertTrue(baby != null && baby.isBaby() && colony.hasCitizen(baby.getUUID()), "a baby in town");
        CitizenRecord rbaby = colony.citizen(baby.getUUID()).orElseThrow();
        helper.assertTrue(rbaby.parents.containsAll(List.of(ra.uuid, rb.uuid)), "parents recorded");
        helper.assertTrue(baby.getName().getString().endsWith(ra.name.substring(ra.name.indexOf(' ') + 1)), "family name: " + baby.getName().getString());
        Order.Result work = OrderService.issue(new Order(owner, OrderKind.ASSIGN_JOB, baby, Jobs.FARMER));
        helper.assertTrue(work.outcome() == Order.Outcome.INVALID, "children don't work: " + work);
        baby.setBorn(helper.getLevel().getGameTime() - StadConfig.CHILD_DAYS.get() * 24000L - 1);
        Social.morning(helper.getLevel(), colony);
        helper.assertTrue(!baby.isBaby(), "grown up");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    private static CompoundTag looks(boolean female, int skin) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Female", female);
        tag.putInt("Skin", skin);
        return tag;
    }
}
