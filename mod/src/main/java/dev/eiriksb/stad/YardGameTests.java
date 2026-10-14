package dev.eiriksb.stad;

import dev.eiriksb.stad.architect.Project;
import dev.eiriksb.stad.architect.Tables;
import dev.eiriksb.stad.architect.Yard;
import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Fields;
import dev.eiriksb.stad.building.Groves;
import dev.eiriksb.stad.building.Pens;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * In-game tests for land designed at the architect's table (PLAN.md §6.11): a field round water channels that the
 * farmer tills and sows with the crop it asks for, a pen the shepherd leads animals into through its gate, and a grove
 * the lumberjack plants.
 */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class YardGameTests {
    private YardGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(2, G, 2), TABLE = new BlockPos(3, G, 2);

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.GRASS_BLOCK); // the lot is stone
            }
        }
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        helper.assertTrue(f.colony() != null, "no town: " + f.problem());
        helper.setBlock(TABLE, StadRegistries.ARCHITECT_TABLE.get());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer owner) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
    }

    private static Project site(GameTestHelper helper, int x0, int z0, int x1, int z1) {
        Project p = Tables.project(helper.getLevel(), helper.absolutePos(TABLE)).orElseThrow();
        p.setSite(helper.absolutePos(new BlockPos(x0, G - 1, z0)), helper.absolutePos(new BlockPos(x1, G + 4, z1)));
        return p;
    }

    private static CitizenEntity worker(GameTestHelper helper, Colony colony, String job, int x, int z) {
        CitizenEntity c = helper.spawn(StadRegistries.CITIZEN.get(), x, G, z);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, c);
        colony.citizen(c.getUUID()).orElseThrow().job = job;
        c.setHunger(20);
        return c;
    }

    private static TownHallBlockEntity hall(GameTestHelper helper) {
        return (TownHallBlockEntity) helper.getBlockEntity(HALL);
    }

    /** Builds a planned building at once, as a builder would. */
    private static void build(GameTestHelper helper, Building b) {
        for (Placement.Step s : b.placement().orElseThrow().steps()) {
            helper.getLevel().setBlock(s.pos(), s.state(), 3);
        }
        b.status = Building.Status.DONE;
    }

    @GameTest(template = "lot", batch = "yard_field", timeoutTicks = 6000)
    public static void tableLaysOutAFieldTheFarmerSows(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 4, 5, 17, 17); // 14 by 13: inside the fence and path, 10 by 9 of farmland round a channel
        p.type = BuildingDef.FIELD;
        Yard carrots = new Yard("carrots", "rows", "oak", true, "sheep", "oak", 4, true);
        helper.assertTrue(Tables.generate(helper.getLevel(), p, carrots, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a field: missing " + check.missing());
        Map<Integer, BlockState> design = p.design();
        long water = design.values().stream().filter(s -> s.is(Blocks.WATER)).count();
        long farmland = design.values().stream().filter(s -> s.is(Blocks.FARMLAND)).count();
        helper.assertTrue(water == 10 && farmland == 80, "a channel and the plots round it: " + water + " water, " + farmland + " farmland");
        helper.assertTrue(design.values().stream().filter(s -> s.getBlock() instanceof FenceGateBlock).count() == 1, "one gate");
        // without its water, the farmland's dry
        Map<Integer, BlockState> before = Map.copyOf(p.edits);
        design.forEach((k, s) -> {
            if (s.is(Blocks.WATER)) {
                p.edits.put(k, Blocks.DIRT.defaultBlockState());
            }
        });
        var dry = Tables.check(helper.getLevel(), p);
        helper.assertTrue(!dry.ready() && dry.missing().getFirst().contains("water"), "dry: " + dry.missing());
        p.edits.clear();
        p.edits.putAll(before);
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error() && said.message().contains("farmer"), "planned: " + said.message());
        Building field = Fields.of(colony).getFirst();
        helper.assertTrue(Fields.plots(field).size() == 80 && Fields.waters(field).size() == 10, "markers for the farmer: "
                + Fields.plots(field).size() + " plots, " + Fields.waters(field).size() + " water");
        helper.assertTrue(Fields.crops(field).values().stream().allMatch("carrots"::equals), "the plots ask for carrots");
        Placement placement = field.placement().orElseThrow();
        helper.assertTrue(placement.steps().stream().noneMatch(s -> s.state().is(Blocks.FARMLAND) || s.state().is(Blocks.CARROTS)),
                "the builders don't till or sow");
        helper.assertTrue(placement.steps().stream().anyMatch(s -> s.state().getBlock() instanceof FenceBlock), "the builders put up the fence");
        hall(helper).storage().addItem(new ItemStack(Items.WHEAT_SEEDS, 20));
        hall(helper).storage().addItem(new ItemStack(Items.CARROT, 20));
        CitizenEntity farmer = worker(helper, colony, Jobs.FARMER, 8, 3);
        helper.succeedWhen(() -> {
            long wet = Fields.waters(field).stream().filter(w -> helper.getLevel().getFluidState(w).isSource()).count();
            helper.assertTrue(wet == 10, "only " + wet + " water; the farmer says: " + farmer.status().text());
            int tilled = 0, sown = 0, wrong = 0;
            for (BlockPos pl : Fields.plots(field)) {
                BlockState above = helper.getLevel().getBlockState(pl.above());
                tilled += helper.getLevel().getBlockState(pl).is(Blocks.FARMLAND) ? 1 : 0;
                sown += above.is(Blocks.CARROTS) ? 1 : 0;
                wrong += above.is(Blocks.WHEAT) ? 1 : 0;
            }
            helper.assertTrue(tilled >= 60, "only " + tilled + " tilled; the farmer says: " + farmer.status().text());
            helper.assertTrue(sown >= 12, "only " + sown + " carrots; the farmer says: " + farmer.status().text());
            helper.assertTrue(wrong == 0 || hall(helper).storage().countItem(Items.CARROT) == 0, wrong + " sown with wheat while there were carrots");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_pen", timeoutTicks = 3000)
    public static void tableFencesAPenTheShepherdFills(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 7, 7, 16, 16);
        p.type = BuildingDef.PEN;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready() && check.rows().stream().allMatch(r -> r.met()), "a pen with everything: " + check.rows());
        // a gap in the fence: animals would get out
        Integer post = p.design().entrySet().stream().filter(e -> e.getValue().getBlock() instanceof FenceBlock
                && Project.kx(e.getKey()) == 0 && Project.kz(e.getKey()) == 2).map(Map.Entry::getKey).findFirst().orElseThrow();
        p.edits.put(post, Blocks.AIR.defaultBlockState());
        var gap = Tables.check(helper.getLevel(), p);
        helper.assertTrue(!gap.ready() && gap.missing().contains("a fence all round"), "a gap: " + gap.missing());
        p.edits.remove(post);
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error(), "planned: " + said.message());
        Building pen = Pens.of(colony).getFirst();
        helper.assertTrue(Pens.kind(pen) == EntityType.SHEEP, "for sheep");
        build(helper, pen);
        helper.assertTrue(Pens.gate(helper.getLevel(), pen).isPresent(), "a gate in the fence");
        Sheep a = helper.spawn(EntityType.SHEEP, 3, G, 12);
        Sheep b = helper.spawn(EntityType.SHEEP, 4, G, 15);
        a.setSheared(true);
        b.setSheared(true);
        CitizenEntity shepherd = worker(helper, colony, Jobs.SHEPHERD, 6, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(Pens.inside(pen, a.blockPosition()) && Pens.inside(pen, b.blockPosition()), "not in the pen yet; the shepherd says: "
                    + shepherd.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_grove", timeoutTicks = 3000)
    public static void tableMarksOutAGroveTheLumberjackPlants(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 7, 7, 15, 15);
        p.type = BuildingDef.GROVE;
        Yard birch = new Yard("wheat", "rows", "none", false, "sheep", "birch", 3, false);
        helper.assertTrue(Tables.generate(helper.getLevel(), p, birch, Direction.NORTH) == null, "laid out");
        long saplings = p.design().values().stream().filter(s -> s.is(Blocks.BIRCH_SAPLING)).count();
        helper.assertTrue(saplings == 9, "a 3 by 3 of birch: " + saplings);
        helper.assertTrue(Tables.check(helper.getLevel(), p).ready(), "a grove");
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error() && said.message().contains("lumberjack"), "planned: " + said.message());
        Building grove = Groves.of(colony).getFirst();
        helper.assertTrue(grove.done(), "nothing for the builders");
        Map<BlockPos, String> spots = Groves.spots(colony);
        helper.assertTrue(spots.size() == 9 && spots.values().stream().allMatch("birch"::equals), "spots: " + spots);
        hall(helper).storage().addItem(new ItemStack(Items.OAK_SAPLING, 4));
        hall(helper).storage().addItem(new ItemStack(Items.BIRCH_SAPLING, 12));
        CitizenEntity lumberjack = worker(helper, colony, Jobs.LUMBERJACK, 6, 4);
        helper.succeedWhen(() -> {
            long planted = spots.keySet().stream().filter(s -> helper.getLevel().getBlockState(s).is(BlockTags.SAPLINGS)).count();
            long birches = spots.keySet().stream().filter(s -> helper.getLevel().getBlockState(s).is(Blocks.BIRCH_SAPLING)).count();
            helper.assertTrue(planted == 9, "only " + planted + " planted; the lumberjack says: " + lumberjack.status().text());
            helper.assertTrue(birches >= 7, "birch where it asks for birch: " + birches);
            cleanUp(helper, colony, owner);
        });
    }
}
