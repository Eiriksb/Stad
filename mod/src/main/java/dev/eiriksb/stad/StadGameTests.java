package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.theywilltalk.api.Memory;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
        CompoundTag saved = new CompoundTag();
        citizen.addAdditionalSaveData(saved);
        CitizenEntity copy = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        copy.readAdditionalSaveData(saved);
        helper.assertTrue(copy.isFemale() && copy.skin() == 5, "looks weren't saved: female=" + copy.isFemale() + " skin=" + copy.skin());
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

    /** The room's floor is at y = -1 relative to the test, so y = 0 is the ground floor. */
    private static final BlockPos HALL = new BlockPos(4, 0, 4);

    private static Colony foundHere(GameTestHelper helper, ServerPlayer owner) {
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL),
                owner.getUUID(), owner.getGameProfile().getName(), TEST_RADIUS);
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
        helper.assertTrue(colony.contains(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 0, 6))), "claims its land");
        helper.assertTrue(colony.rank(owner.getUUID()) == Rank.OWNER && colony.rank(rival.getUUID()) == Rank.VISITOR, "ranks");
        ColonyManager.Founding second = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(),
                helper.absolutePos(new BlockPos(6, 0, 6)), rival.getUUID(), "rival", TEST_RADIUS);
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
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, 0, 3);

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
        BlockPos foot = new BlockPos(2, 0, 6);
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(foot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        CitizenEntity first = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, 0, 3);
        CitizenEntity second = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, 0, 3);
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
        // Right beside the hall: pathfinding is unreliable this far out in the game-test world (walking there is
        // checked on the dev server), so this tests the eating.
        CitizenEntity citizen = helper.spawn(StadRegistries.CITIZEN.get(), 3, 0, 4);
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

    private static CompoundTag looks(boolean female, int skin) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Female", female);
        tag.putInt("Skin", skin);
        return tag;
    }
}
