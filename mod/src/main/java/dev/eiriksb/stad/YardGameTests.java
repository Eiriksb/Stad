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
 * farmer tills and sows with the crop it asks for, a pen the shepherd leads animals into through its gate, a grove
 * the lumberjack plants, a quarry, a dock, a post, a wall, market stalls the merchants keep, and roads (routed or drawn).
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
        Yard carrots = new Yard("carrots", "rows", "oak", true, "sheep", "oak", 4, true, 16, "logs", 3, "dirt_path", "striped", 3);
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
        Yard birch = new Yard("wheat", "rows", "none", false, "sheep", "birch", 3, false, 16, "logs", 3, "dirt_path", "striped", 3);
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

    @GameTest(template = "lot", batch = "yard_quarry", timeoutTicks = 3000)
    public static void tableMarksOutAQuarryTheMinerDigs(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        for (int x = 7; x <= 13; x++) { // stone under the grass, to dig
            for (int z = 7; z <= 13; z++) {
                for (int y = G - 3; y < G - 1; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        Project p = site(helper, 7, 7, 13, 13);
        p.type = BuildingDef.QUARRY;
        Yard q = new Yard("wheat", "rows", "oak", false, "sheep", "oak", 4, false, 8, "logs", 3, "dirt_path", "striped", 3);
        helper.assertTrue(Tables.generate(helper.getLevel(), p, q, Direction.NORTH) == null, "laid out");
        helper.assertTrue(Tables.check(helper.getLevel(), p).ready(), "a quarry: " + Tables.check(helper.getLevel(), p).missing());
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error() && said.message().contains("miner"), "planned: " + said.message());
        Building quarry = colony.buildings().stream().filter(b -> b.is(BuildingDef.QUARRY)).findFirst().orElseThrow();
        build(helper, quarry);
        var pits = dev.eiriksb.stad.building.Mine.pits(colony);
        helper.assertTrue(pits.size() == 1 && pits.getFirst().x1() - pits.getFirst().x0() == 4 && pits.getFirst().depth() == 8,
                "a 5 by 5 pit inside the fence, 8 deep: " + pits);
        CitizenEntity miner = worker(helper, colony, Jobs.MINER, 5, 4);
        helper.succeedWhen(() -> {
            int dug = dev.eiriksb.stad.building.Mine.depth(helper.getLevel(), pits.getFirst());
            helper.assertTrue(dug >= 2, "dug " + dug + " layers; the miner says: " + miner.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_dock", timeoutTicks = 2400)
    public static void tableLaysOutADockTheFisherFishesFrom(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        for (int x = 4; x <= 17; x++) { // a pond south of z = 9
            for (int z = 9; z <= 17; z++) {
                helper.setBlock(new BlockPos(x, G - 2, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, G - 1, z), Blocks.WATER);
            }
        }
        Project p = site(helper, 8, 6, 14, 16);
        p.type = BuildingDef.DOCK;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a dock: " + check.missing());
        helper.assertTrue(p.markers.containsValue("stad:fish"), "spots to fish from");
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error(), "planned: " + said.message());
        Building dock = colony.buildings().stream().filter(b -> b.is(BuildingDef.DOCK)).findFirst().orElseThrow();
        build(helper, dock);
        java.util.List<BlockPos> spots = dock.placement().orElseThrow().markers("stad:fish");
        CitizenEntity fisher = worker(helper, colony, Jobs.FISHER, 6, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(spots.stream().anyMatch(sp -> fisher.blockPosition().closerThan(sp.above(), 2.5)),
                    "not on the dock; the fisher is at " + fisher.blockPosition() + " and says: " + fisher.status().text());
            helper.assertTrue(fisher.status().text().contains("Fishing"), "the fisher says: " + fisher.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_post", timeoutTicks = 4000)
    public static void tableDrawsAPostTheGuardsKeepWatchFrom(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 10, 10, 14, 14);
        p.type = BuildingDef.POST;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a post: " + check.missing());
        helper.assertTrue(Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE)).message().contains("guard"), "for the guards");
        Building post = colony.buildings().stream().filter(b -> b.is(BuildingDef.POST)).findFirst().orElseThrow();
        build(helper, post);
        BlockPos spot = post.placement().orElseThrow().markers("stad:post").getFirst();
        CitizenEntity guard = worker(helper, colony, Jobs.GUARD, 6, 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.blockPosition().closerThan(spot.above(), 2.0), "not up on the post; the guard is at " + guard.blockPosition()
                    + " (post " + spot + ") and says: " + guard.status().text());
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_wall", timeoutTicks = 100)
    public static void tableDrawsAWallRoundWhatsThere(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        // a field first, then a wall round it
        Project p = site(helper, 8, 8, 12, 12);
        p.type = BuildingDef.FIELD;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT.withFenceForTest("none"), Direction.NORTH) == null, "field laid out");
        helper.assertTrue(!Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE)).error(), "field planned");
        colony.buildings().forEach(b -> b.status = Building.Status.DONE);
        // something else in the finished field's place is refused (a redesign has to be a field too)
        p.type = BuildingDef.QUARRY;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "quarry laid out");
        Tables.Said over = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(over.error() && over.message().contains("can't go in its place"), "not over the field: " + over.message());
        p = site(helper, 6, 6, 14, 14);
        p.type = BuildingDef.WALL;
        Yard stone = new Yard("wheat", "rows", "oak", false, "sheep", "oak", 4, true, 16, "stone_bricks", 3, "dirt_path", "striped", 3);
        helper.assertTrue(Tables.generate(helper.getLevel(), p, stone, Direction.NORTH) == null, "wall laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready() && check.rows().stream().allMatch(r -> r.met()), "a wall with a door and posts: " + check.rows());
        Project wall = p;
        Integer gap = p.design().entrySet().stream().filter(e -> e.getValue().is(Blocks.STONE_BRICKS) && Project.kx(e.getKey()) == 0
                && Project.kz(e.getKey()) == 3).map(Map.Entry::getKey).toList().stream().findFirst().orElseThrow();
        int gx = Project.kx(gap), gz = Project.kz(gap);
        p.design().keySet().stream().filter(k -> Project.kx(k) == gx && Project.kz(k) == gz).forEach(k -> wall.edits.put(k, Blocks.AIR.defaultBlockState()));
        helper.assertTrue(!Tables.check(helper.getLevel(), p).ready(), "a gap in the wall");
        p.edits.clear();
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error(), "the wall goes round the field: " + said.message());
        helper.assertTrue(colony.buildings().stream().anyMatch(b -> b.is(BuildingDef.FIELD)) && colony.buildings().size() == 2, "the field stays");
        Building w = colony.buildings().stream().filter(b -> b.is(BuildingDef.WALL)).findFirst().orElseThrow();
        helper.assertTrue(w.placement().orElseThrow().markers("stad:post").size() == 4, "a watch spot on each corner");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "yard_stalls", timeoutTicks = 2400)
    public static void tableLaysOutMarketStallsTheMerchantsKeep(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 4, 5, 16, 17); // 13 by 13: stalls on every side, a way in on the north
        p.type = BuildingDef.STALLS;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a market square: " + check.missing());
        long stalls = p.markers.values().stream().filter("stad:work"::equals).count();
        helper.assertTrue(stalls == 9, "nine stalls round the square, not " + stalls);
        helper.assertTrue(p.design().values().stream().anyMatch(st -> st.is(Blocks.BELL)), "a bell in the middle");
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error() && said.message().contains("merchant"), "planned: " + said.message());
        Building square = colony.buildings().stream().filter(b -> b.is(BuildingDef.STALLS)).findFirst().orElseThrow();
        build(helper, square);
        java.util.List<BlockPos> spots = square.placement().orElseThrow().markers("stad:work");
        CitizenEntity a = worker(helper, colony, Jobs.MERCHANT, 6, 3), b = worker(helper, colony, Jobs.MERCHANT, 8, 3);
        helper.succeedWhen(() -> {
            BlockPos sa = spots.stream().min(java.util.Comparator.comparingDouble(sp -> sp.distSqr(a.blockPosition()))).orElseThrow();
            BlockPos sb = spots.stream().min(java.util.Comparator.comparingDouble(sp -> sp.distSqr(b.blockPosition()))).orElseThrow();
            helper.assertTrue(a.blockPosition().closerThan(sa, 2.5) && b.blockPosition().closerThan(sb, 2.5), "not at their stalls: "
                    + a.blockPosition() + " says " + a.status().text() + ", " + b.blockPosition() + " says " + b.status().text());
            helper.assertTrue(!sa.equals(sb), "both at the same stall, " + sa);
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_road", timeoutTicks = 400)
    public static void tableRoutesARoadFromTheTownHallToADoor(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        helper.setBlock(new BlockPos(7, G, 5), Blocks.CHEST); // in the way, maybe
        var cottage = dev.eiriksb.stad.building.BuildingPacks.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage"))
                .orElseThrow();
        Building home = colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(9, G, 8)), net.minecraft.world.level.block.Rotation.NONE,
                helper.getLevel().getGameTime());
        build(helper, home);
        Project p = site(helper, 0, 0, 17, 17);
        p.type = BuildingDef.ROAD;
        var route = Tables.route(helper.getLevel(), helper.absolutePos(TABLE), p);
        helper.assertTrue(route.problem() == null, "routed: " + route.message());
        helper.assertTrue(route.joined().contains("the town hall") && route.joined().stream().anyMatch(j -> j.contains(cottage.name())),
                "joins the hall and the cottage: " + route.message());
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a road: " + check.missing());
        BlockPos chestAt = helper.absolutePos(new BlockPos(7, G, 5)), min = p.min;
        helper.assertTrue(p.design().keySet().stream().noneMatch(k -> min.offset(Project.kx(k), Project.ky(k), Project.kz(k)).equals(chestAt)
                || min.offset(Project.kx(k), Project.ky(k), Project.kz(k)).equals(helper.absolutePos(TABLE))
                || min.offset(Project.kx(k), Project.ky(k), Project.kz(k)).equals(helper.absolutePos(HALL))), "round the chest, the table and the hall");
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error(), "planned: " + said.message());
        Building road = colony.buildings().stream().filter(b -> b.is(BuildingDef.ROAD)).findFirst().orElseThrow();
        helper.assertTrue(colony.buildings().contains(home), "the cottage stays");
        build(helper, road);
        java.util.List<BlockPos> paved = road.placement().orElseThrow().markers("stad:road");
        helper.assertTrue(paved.size() >= 20 && paved.stream().allMatch(c -> helper.getLevel().getBlockState(c).is(Blocks.DIRT_PATH)
                || helper.getLevel().getBlockState(c).is(BlockTags.PLANKS)), "paved: " + paved.size());
        helper.assertTrue(dev.eiriksb.stad.building.Roads.on(helper.getLevel(), colony, paved.get(paved.size() / 2)), "on the town's road");
        BlockPos stand = paved.get(paved.size() / 2).above();
        CitizenEntity c = worker(helper, colony, null, 1, 1);
        c.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        double base = c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue();
        helper.succeedWhen(() -> {
            helper.assertTrue(c.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) > base * 1.2,
                    "faster on the road (at " + c.blockPosition() + ")");
            cleanUp(helper, colony, owner);
        });
    }

    @GameTest(template = "lot", batch = "yard_road_drawn", timeoutTicks = 100)
    public static void aRoadIsPavedWhereItsDrawn(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        Project p = site(helper, 4, 4, 16, 16);
        p.type = BuildingDef.ROAD;
        for (int x = 1; x <= 10; x++) { // a line along z = 6, then up to z = 10
            p.markers.put(Project.key(x, 0, 6), "stad:road");
        }
        for (int z = 7; z <= 10; z++) {
            p.markers.put(Project.key(10, 0, z), "stad:road");
        }
        Yard one = Yard.parse("{\"width\": 1, \"extra\": false, \"paving\": \"cobblestone\"}");
        helper.assertTrue(Tables.generate(helper.getLevel(), p, one, Direction.NORTH) == null, "laid");
        long cobble = p.design().values().stream().filter(st -> st.is(Blocks.COBBLESTONE)).count();
        helper.assertTrue(cobble == 14, "one wide, where it was drawn: " + cobble);
        helper.assertTrue(p.design().get(Project.key(5, 0, 6)).is(Blocks.COBBLESTONE) && p.design().get(Project.key(5, 0, 7)) == null, "on the line");
        Yard wide = Yard.parse("{\"width\": 3, \"extra\": true}");
        helper.assertTrue(Tables.generate(helper.getLevel(), p, wide, Direction.NORTH) == null, "laid wider");
        long path = p.design().values().stream().filter(st -> st.is(Blocks.DIRT_PATH)).count();
        helper.assertTrue(path > 40, "three wide: " + path);
        helper.assertTrue(p.design().values().stream().anyMatch(st -> st.is(Blocks.LANTERN)), "lamp posts beside it");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "yard_town_roads", timeoutTicks = 100)
    public static void layRoadsJoinsTheWholeTown(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        var cottage = dev.eiriksb.stad.building.BuildingPacks.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage"))
                .orElseThrow();
        Building home = colony.addBuilding(cottage.key(), cottage.name(), helper.absolutePos(new BlockPos(9, G, 8)), net.minecraft.world.level.block.Rotation.NONE,
                helper.getLevel().getGameTime());
        build(helper, home);
        var first = dev.eiriksb.stad.architect.TownRoads.plan(owner, helper.getLevel(), colony, Yard.DEFAULT);
        helper.assertTrue(!first.error() && first.message().contains(cottage.name()), "roads planned: " + first.message());
        Building roads = colony.buildings().stream().filter(b -> b.is(BuildingDef.ROAD)).findFirst().orElseThrow();
        java.util.List<BlockPos> paved = roads.placement().orElseThrow().markers("stad:road");
        helper.assertTrue(paved.size() >= 15, "paved: " + paved.size());
        BlockPos hall = helper.absolutePos(HALL), table = helper.absolutePos(TABLE);
        helper.assertTrue(roads.placement().orElseThrow().steps().stream().noneMatch(st -> st.pos().equals(hall) || st.pos().equals(table)
                || home.placement().orElseThrow().bounds().isInside(st.pos())), "not over the hall, the table or the cottage");
        build(helper, roads);
        var again = dev.eiriksb.stad.architect.TownRoads.plan(owner, helper.getLevel(), colony, Yard.DEFAULT);
        helper.assertTrue(!again.error() && again.message().contains("already"), "nothing more to join: " + again.message());
        helper.assertTrue(colony.buildings().stream().filter(b -> b.is(BuildingDef.ROAD)).count() == 1, "no second road");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "yard_keys", timeoutTicks = 20)
    public static void aTableSavedWithSmallSitesStillLoads(GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putString("Name", "Old");
        t.putString("Type", "stad:home");
        t.put("Min", net.minecraft.nbt.NbtUtils.writeBlockPos(new BlockPos(0, 64, 0)));
        t.putIntArray("Size", new int[]{40, 12, 50});
        net.minecraft.nbt.ListTag pal = new net.minecraft.nbt.ListTag();
        pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        t.put("Palette", pal);
        int old = 37 | 45 << 6 | 3 << 12; // x 37, z 45, y 3, packed the old way
        t.putIntArray("Keys", new int[]{old});
        t.putIntArray("States", new int[]{0});
        Project p = Project.load(t);
        helper.assertTrue(p.edits.containsKey(Project.key(37, 3, 45)), "moved to the new packing: " + p.edits.keySet());
        Project big = new Project();
        big.setSite(new BlockPos(0, 64, 0), new BlockPos(299, 70, 199));
        big.edits.put(Project.key(299, 11, 199), Blocks.STONE.defaultBlockState());
        Project back = Project.load(big.save());
        helper.assertTrue(back.sx == 300 && back.edits.containsKey(Project.key(299, 11, 199)) && Project.kx(Project.key(299, 11, 199)) == 299
                && Project.kz(Project.key(299, 11, 199)) == 199 && Project.ky(Project.key(299, 11, 199)) == 11, "a 300 by 200 site keeps its edits");
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "yard_apiary", timeoutTicks = 3000)
    public static void tableLaysOutAnApiaryTheBeekeeperKeeps(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.getLevel().setDayTime(1000);
        Colony colony = found(helper, owner);
        Project p = site(helper, 6, 6, 16, 14);
        p.type = BuildingDef.APIARY;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, Yard.DEFAULT, Direction.NORTH) == null, "laid out");
        var check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready() && check.rows().stream().allMatch(r -> r.met()), "an apiary with hives, flowers and a fence: " + check.rows());
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error() && said.message().contains("beekeeper"), "planned: " + said.message());
        Building apiary = colony.buildings().stream().filter(b -> b.is(BuildingDef.APIARY)).findFirst().orElseThrow();
        Placement pl = apiary.placement().orElseThrow();
        helper.assertTrue(pl.materials().containsKey(Items.OAK_PLANKS) && !pl.materials().containsKey(Items.BEEHIVE), "hives from planks: " + pl.materials());
        java.util.List<BlockPos> flowers = pl.markers("stad:flower");
        helper.assertTrue(flowers.size() >= 4 && pl.steps().stream().noneMatch(st -> st.state().is(BlockTags.SMALL_FLOWERS)), "the flowers are the beekeeper's");
        build(helper, apiary);
        BlockPos full = pl.steps().stream().filter(st -> st.state().is(Blocks.BEEHIVE)).map(Placement.Step::pos).findFirst().orElseThrow();
        helper.getLevel().setBlock(full, helper.getLevel().getBlockState(full).setValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL, 5), 3);
        hall(helper).storage().addItem(new ItemStack(Items.GLASS_BOTTLE, 2));
        CitizenEntity keeper = worker(helper, colony, Jobs.BEEKEEPER, 4, 4);
        helper.succeedWhen(() -> {
            var town = dev.eiriksb.stad.building.ColonyStorage.deliveries(helper.getLevel(), colony);
            helper.assertTrue(town.count(Items.HONEY_BOTTLE) + keeper.inventory().countItem(Items.HONEY_BOTTLE) >= 1, "honey taken: " + keeper.status().text());
            helper.assertTrue(helper.getLevel().getBlockState(full).getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) == 0, "the hive emptied");
            long sown = flowers.stream().filter(f -> helper.getLevel().getBlockState(f).is(BlockTags.SMALL_FLOWERS)).count();
            helper.assertTrue(sown == flowers.size(), "flowers sown: " + sown + "/" + flowers.size() + "; " + keeper.status().text());
            helper.assertTrue(helper.getLevel().getBlockEntity(full) instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity be && be.getOccupantCount() > 0
                    || !helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new net.minecraft.world.phys.AABB(full).inflate(16),
                    b -> full.equals(b.getHivePos())).isEmpty(), "bees in the hive");
            cleanUp(helper, colony, owner);
        });
    }
}
