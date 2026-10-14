package dev.eiriksb.stad;

import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.goal.Movement;
import dev.eiriksb.stad.colony.Achievements;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the polish: citizens walking all the way, the ground left as it is, and the town's achievements. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PolishGameTests {
    private PolishGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(16, G, 16);

    /** A long walk round a wall that's going well isn't cut short: no jumps on the way, and they get there. */
    @GameTest(template = "lot", batch = "polish_walk", timeoutTicks = 1200)
    public static void longWalksAreWalked(GameTestHelper helper) {
        for (int z = 0; z <= 14; z++) {
            for (int y = G; y < G + 3; y++) {
                helper.setBlock(new BlockPos(8, y, z), Blocks.STONE_BRICKS);
            }
        }
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), 2, G, 1);
        c.goalSelector.removeAllGoals(g -> true);
        BlockPos to = helper.absolutePos(new BlockPos(15, G, 1));
        Movement.Approach approach = new Movement.Approach();
        Vec3[] last = {c.position()};
        double[] jump = {0};
        boolean[] there = {false};
        helper.onEachTick(() -> {
            if (!there[0]) {
                there[0] = approach.tick(c, to, 2.0, 0.6);
            }
            jump[0] = Math.max(jump[0], c.position().distanceTo(last[0]));
            last[0] = c.position();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(there[0], "not there yet: at " + c.blockPosition());
            helper.assertTrue(helper.getTick() > Movement.PATIENCE, "the walk round the wall is longer than their patience: " + helper.getTick());
            helper.assertTrue(jump[0] < 1.5, "walked, didn't jump: " + jump[0]);
        });
    }

    /** A building's dirt floor (or a path's) leaves the grass that's there, and only fills in a hole. */
    @GameTest(template = "lot", batch = "polish_ground")
    public static void groundIsLeftAsItIs(GameTestHelper helper) {
        BlockPos grass = helper.absolutePos(new BlockPos(3, G - 1, 3)), hole = grass.east();
        helper.getLevel().setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        helper.getLevel().setBlock(hole, Blocks.AIR.defaultBlockState(), 2);
        Placement.Step dirt = new Placement.Step(grass, Blocks.DIRT.defaultBlockState(), Items.DIRT, 1);
        Placement.Step filling = new Placement.Step(hole, Blocks.DIRT.defaultBlockState(), Items.DIRT, 1);
        Placement.Step lawn = new Placement.Step(grass, Blocks.GRASS_BLOCK.defaultBlockState(), Items.DIRT, 1);
        Placement.Step stone = new Placement.Step(grass, Blocks.STONE.defaultBlockState(), Items.STONE, 1);
        helper.assertTrue(Construction.satisfied(helper.getLevel(), dirt), "dirt wanted, grass there: it stays");
        helper.assertTrue(Construction.satisfied(helper.getLevel(), lawn), "grass wanted, grass there");
        helper.assertFalse(Construction.satisfied(helper.getLevel(), filling), "a hole is filled");
        helper.assertFalse(Construction.satisfied(helper.getLevel(), stone), "stone is still built");
        helper.succeed();
    }

    /** The town earns its achievements, and its members get them. */
    @GameTest(template = "lot", batch = "polish_feats")
    public static void townsEarnAchievements(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        Colony colony = f.colony();
        helper.assertTrue(colony != null, "no town: " + f.problem());
        Achievements.update(helper.getLevel(), colony);
        helper.assertTrue(colony.feats.contains(Achievements.ROOT) && !colony.feats.contains("first_citizen"), "founded, no one yet: " + colony.feats);
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), 4, G, 4);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        colony.citizen(c.getUUID()).orElseThrow().job = Jobs.FARMER;
        Achievements.feat(colony, Achievements.CHILD);
        Achievements.update(helper.getLevel(), colony);
        helper.assertTrue(colony.feats.containsAll(java.util.List.of("first_citizen", "first_job", Achievements.CHILD)), "earned: " + colony.feats);
        helper.assertFalse(colony.feats.contains("village"), "not a village yet");
        for (String id : colony.feats) {
            AdvancementHolder a = helper.getLevel().getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, id));
            helper.assertTrue(a != null, "an advancement for " + id);
            helper.assertTrue(owner.getAdvancements().getOrStartProgress(a).isDone(), "the owner got " + id);
        }
        Colony back = Colony.load(colony.save());
        helper.assertTrue(back.feats.equals(colony.feats), "kept: " + back.feats);
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }
}
