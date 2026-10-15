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

/**
 * In-game tests for the polish: citizens walking all the way, the ground left as it is, the town's achievements, and
 * buildings planned in an area marked out with the plans.
 */
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

    /** An unfinished building whose pack is gone comes off the list (it'd hold up the builder); one standing stays. */
    @GameTest(template = "lot", batch = "polish_lost")
    public static void lostPlansComeOffTheList(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        Colony colony = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16).colony();
        ResourceLocation gone = ResourceLocation.parse("stad:fjord/stue");
        var planned = colony.addBuilding(gone, "Stue", helper.absolutePos(new BlockPos(3, G, 3)), net.minecraft.world.level.block.Rotation.NONE, 0);
        var standing = colony.addBuilding(gone, "Naust", helper.absolutePos(new BlockPos(9, G, 3)), net.minecraft.world.level.block.Rotation.NONE, 0);
        standing.status = dev.eiriksb.stad.colony.Building.Status.DONE;
        CitizenEntity builder = helper.spawn(StadRegistries.CITIZEN.get(), 4, G, 4);
        helper.assertTrue(colony.workFor(builder.getUUID()).isEmpty(), "nothing a builder can build");
        helper.assertTrue(colony.dropLost().equals(java.util.List.of("Stue")), "the stue comes off");
        helper.assertTrue(colony.building(planned.id).isEmpty() && colony.building(standing.id).isPresent(), "the standing one stays");
        builder.discard();
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }

    /** What the town thinks of you tells each cause once, with what it came to. */
    @GameTest(template = "lot", batch = "polish_standing")
    public static void standingTellsEachCauseOnce(GameTestHelper helper) {
        dev.eiriksb.stad.colony.Standing s = new dev.eiriksb.stad.colony.Standing();
        s.change(3, "the town is happy", 1);
        s.change(3, "the town is happy", 2);
        s.change(-3, "Oda went hungry", 3);
        helper.assertTrue(s.best(5).size() == 1 && s.best(5).getFirst().delta() == 6, "once, +6: " + s.best(5));
        helper.assertTrue(s.worst(5).size() == 1, "the hungry one: " + s.worst(5));
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

    /** A house and a field drawn up to fit a marked area stay inside it; once one's planned there, the next is in its way. */
    @GameTest(template = "lot", batch = "polish_area", timeoutTicks = 200)
    public static void areasAreFilled(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        Colony colony = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 24).colony();
        try {
            areas(helper, owner, colony);
        } finally {
            ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
            helper.getLevel().getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }

    private static void areas(GameTestHelper helper, ServerPlayer owner, Colony colony) {
        colony.research.all();
        var level = helper.getLevel();
        var area = dev.eiriksb.stad.item.PlanArea.of(helper.absolutePos(new BlockPos(2, G - 1, 2)), helper.absolutePos(new BlockPos(13, G - 1, 11)));
        helper.assertTrue(area.sizeX() == 12 && area.sizeZ() == 10 && area.covers(area.box().minX(), area.box().maxZ()), "12 by 10: " + area.size());
        var box = area.box();
        for (ResourceLocation type : java.util.List.of(dev.eiriksb.stad.building.BuildingDef.HOME, dev.eiriksb.stad.building.BuildingDef.FIELD,
                dev.eiriksb.stad.building.BuildingDef.PEN)) {
            for (var turn : new net.minecraft.world.level.block.Rotation[]{net.minecraft.world.level.block.Rotation.NONE,
                    net.minecraft.world.level.block.Rotation.CLOCKWISE_90}) {
                var r = dev.eiriksb.stad.architect.AreaFit.fit(owner, level, colony, area, type, turn, false);
                helper.assertTrue(r.ok() && r.blueprint() != null, type + " " + turn + " fits: " + r.problem());
                var b = Placement.of(r.blueprint(), r.origin(), net.minecraft.world.level.block.Rotation.NONE).bounds();
                helper.assertTrue(b.minX() >= box.minX() && b.maxX() <= box.maxX() && b.minZ() >= box.minZ() && b.maxZ() <= box.maxZ(),
                        type + " inside the area: " + b + " in " + box);
            }
        }
        helper.assertTrue(colony.buildings().isEmpty(), "a preview plans nothing");
        var house = dev.eiriksb.stad.architect.AreaFit.fit(owner, level, colony, area, dev.eiriksb.stad.building.BuildingDef.HOME,
                net.minecraft.world.level.block.Rotation.NONE, true);
        helper.assertTrue(house.ok() && colony.buildings().size() == 1, "the house is planned: " + house.problem());
        var field = dev.eiriksb.stad.architect.AreaFit.fit(owner, level, colony, area, dev.eiriksb.stad.building.BuildingDef.FIELD,
                net.minecraft.world.level.block.Rotation.NONE, true);
        helper.assertTrue(!field.ok() && field.problem().contains("overlaps") && colony.buildings().size() == 1, "the house is in the way: "
                + field.problem());
        var tiny = dev.eiriksb.stad.item.PlanArea.of(helper.absolutePos(new BlockPos(20, G - 1, 2)), helper.absolutePos(new BlockPos(21, G - 1, 3)));
        helper.assertFalse(dev.eiriksb.stad.architect.AreaFit.fit(owner, level, colony, tiny, dev.eiriksb.stad.building.BuildingDef.FIELD,
                net.minecraft.world.level.block.Rotation.NONE, false).ok(), "2 by 2 is too small");
        var half = new dev.eiriksb.stad.item.PlanArea(helper.absolutePos(new BlockPos(20, G - 1, 2)), java.util.Optional.empty());
        helper.assertTrue(dev.eiriksb.stad.architect.AreaFit.problem(level, colony, half) != null, "one corner isn't an area");
        BlockPos table = new BlockPos(20, G, 20);
        helper.setBlock(table, StadRegistries.ARCHITECT_TABLE.get());
        var south = dev.eiriksb.stad.item.PlanArea.of(helper.absolutePos(new BlockPos(18, G - 1, 4)), helper.absolutePos(new BlockPos(24, G - 1, 12)));
        dev.eiriksb.stad.architect.Tables.use(owner, level, helper.absolutePos(table), false, south);
        var site = dev.eiriksb.stad.architect.Tables.project(level, helper.absolutePos(table)).orElseThrow();
        helper.assertTrue(site.hasSite() && site.sx == 7 && site.sz == 9 && site.min.getX() == south.box().minX(),
                "the table's site is the area: " + site.sx + " by " + site.sz);
    }
}
