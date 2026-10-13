package dev.eiriksb.stad;

import dev.eiriksb.stad.agent.DayPlan;
import dev.eiriksb.stad.agent.ThinkBudget;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyChunks;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.war.Raids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for big towns (M10): the think budget, keeping towns loaded, and raid sizes. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScaleGameTests {
    private ScaleGameTests() {}

    private static final int G = 2;

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, G, 2), StadRegistries.TOWN_HALL.get());
        return ColonyManager.get(level.getServer()).found(level, helper.absolutePos(new BlockPos(2, G, 2)), owner.getUUID(),
                owner.getGameProfile().getName(), 12).colony();
    }

    @GameTest(template = "lot", batch = "scale_think", timeoutTicks = 100)
    public static void citizensFarFromPlayersFollowTheirRoutine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        int radius = StadConfig.THINK_RADIUS.get();
        int perDay = StadConfig.PLANS_PER_DAY.get();
        try {
            CitizenEntity farmer = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 5);
            ColonyManager.get(level.getServer()).join(colony, farmer);
            CitizenRecord r = colony.citizen(farmer.getUUID()).orElseThrow();
            r.job = Jobs.FARMER;
            // a routine day, written from code
            RandomSource random = RandomSource.create(7);
            int rests = 0;
            for (int i = 0; i < 80; i++) {
                DayPlan p = DayPlan.routine(colony, r, true, 70, random);
                helper.assertTrue(p.intentions().getFirst().equals("Get something to eat") && p.intentions().contains("Tend the fields")
                        && p.intentions().size() <= 3 && p.visit() == null, "a farmer's day: " + p.intentions());
                helper.assertTrue(p.rest() == p.intentions().contains("Take the afternoon off"), "rest says so: " + p);
                rests += p.rest() ? 1 : 0;
            }
            helper.assertTrue(rests > 2 && rests < 30, "an afternoon off now and then: " + rests + " in 80");
            r.job = null;
            DayPlan idle = DayPlan.routine(colony, r, false, 70, random);
            helper.assertTrue(idle.intentions().contains("Look for something useful to do") && !idle.rest(), "no job, no time off: " + idle);
            helper.assertTrue(DayPlan.routine(colony, r, false, 70, random).intentions().contains("Find a proper bed"), "homeless");
            // who thinks: near a player, and only so many a day
            owner.moveTo(farmer.getX() + 3, farmer.getY(), farmer.getZ());
            helper.assertTrue(ThinkBudget.nearPlayer(farmer), "a player is right there");
            StadConfig.THINK_RADIUS.set(0);
            helper.assertTrue(!ThinkBudget.nearPlayer(farmer), "nobody thinks with the radius at 0");
            StadConfig.PLANS_PER_DAY.set(2);
            long day = 1000;
            helper.assertTrue(ThinkBudget.allowedForTest(colony, day), "fresh day");
            ThinkBudget.spendForTest(colony, day);
            ThinkBudget.spendForTest(colony, day);
            helper.assertTrue(!ThinkBudget.allowedForTest(colony, day) && ThinkBudget.allowedForTest(colony, day + 1), "two a day");
            helper.assertTrue(ThinkBudget.busy(), "no language model in the test server: background thinking waits");
        } finally {
            StadConfig.THINK_RADIUS.set(radius);
            StadConfig.PLANS_PER_DAY.set(perDay);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "scale_chunks", timeoutTicks = 100)
    public static void townsStayLoadedWhileTheirPeopleAreOnline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        StadConfig.KeepLoaded mode = StadConfig.KEEP_LOADED.get();
        try {
            StadConfig.KEEP_LOADED.set(StadConfig.KeepLoaded.WHILE_ONLINE);
            ColonyChunks.update(level.getServer());
            helper.assertTrue(ColonyChunks.held(colony) && ForcedChunkManager.hasForcedChunks(level), "the founder is online");
            level.getServer().getPlayerList().remove(owner);
            ColonyChunks.update(level.getServer());
            helper.assertTrue(!ColonyChunks.held(colony), "let go once they've left");
            StadConfig.KEEP_LOADED.set(StadConfig.KeepLoaded.ALWAYS);
            ColonyChunks.update(level.getServer());
            helper.assertTrue(ColonyChunks.held(colony), "always");
            StadConfig.KEEP_LOADED.set(StadConfig.KeepLoaded.NEVER);
            ColonyChunks.update(level.getServer());
            helper.assertTrue(!ColonyChunks.held(colony), "never");
        } finally {
            StadConfig.KEEP_LOADED.set(mode);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            ColonyChunks.update(level.getServer());
            level.getServer().getPlayerList().remove(owner);
        }
        helper.assertTrue(!ColonyChunks.held(colony), "a dissolved town is let go");
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "scale_raids", timeoutTicks = 100)
    public static void raidsHaveACap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        int max = StadConfig.MAX_RAIDERS.get();
        try {
            for (int i = 0; i < 12; i++) {
                ColonyManager.get(level.getServer()).join(colony, helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3 + i % 6, G, 3 + i / 6));
            }
            int n = Raids.strength(colony);
            helper.assertTrue(n > 3, "a town of 12 gets more than 3 raiders: " + n);
            StadConfig.MAX_RAIDERS.set(3);
            helper.assertTrue(Raids.strength(colony) == 3, "capped: " + Raids.strength(colony));
        } finally {
            StadConfig.MAX_RAIDERS.set(max);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }
}
