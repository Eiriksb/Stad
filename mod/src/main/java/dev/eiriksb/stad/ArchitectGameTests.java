package dev.eiriksb.stad;

import dev.eiriksb.stad.architect.DesignCheck;
import dev.eiriksb.stad.architect.Grid;
import dev.eiriksb.stad.architect.Project;
import dev.eiriksb.stad.architect.Tables;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.MyPack;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-game tests for the architect's table (PLAN.md §6.8, M12). */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArchitectGameTests {
    private ArchitectGameTests() {}

    private static final int G = 2;
    private static final BlockPos HALL = new BlockPos(2, G, 2), TABLE = new BlockPos(3, G, 2);
    private static final String HOME = "{\"name\":\"Lakeside House\",\"type\":\"home\",\"base\":\"new\",\"width\":7,\"depth\":7,\"floors\":1,"
            + "\"roof\":\"gable\",\"walls\":\"spruce\",\"frame\":\"dark_oak\",\"roof_material\":\"dark_oak\",\"floor\":\"spruce\",\"beds\":2,"
            + "\"rooms\":[],\"features\":[]}";

    private static Colony found(GameTestHelper helper, ServerPlayer owner) {
        helper.setBlock(HALL, StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(HALL), owner.getUUID(),
                owner.getGameProfile().getName(), 16);
        helper.assertTrue(f.colony() != null, "no town: " + f.problem());
        helper.setBlock(TABLE, StadRegistries.ARCHITECT_TABLE.get());
        return f.colony();
    }

    private static void cleanUp(GameTestHelper helper, Colony colony, ServerPlayer... players) {
        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        for (ServerPlayer p : players) {
            helper.getLevel().getServer().getPlayerList().remove(p);
        }
    }

    /** The table's project with an 11 x 11 site on the lot's ground. */
    private static Project site(GameTestHelper helper) {
        Project p = Tables.project(helper.getLevel(), helper.absolutePos(TABLE)).orElseThrow();
        p.setSite(helper.absolutePos(new BlockPos(5, G - 1, 5)), helper.absolutePos(new BlockPos(15, G + 4, 15)));
        return p;
    }

    private static List<Integer> keysOf(Project p, java.util.function.Predicate<BlockState> what) {
        return p.design().entrySet().stream().filter(e -> what.test(e.getValue())).map(Map.Entry::getKey).toList();
    }

    @GameTest(template = "empty", batch = "architect")
    public static void designsTravelAndKeep(GameTestHelper helper) {
        // only blocks a survival player could place
        helper.assertTrue(Tables.allowed(Blocks.AIR.defaultBlockState()) && Tables.allowed(Blocks.OAK_PLANKS.defaultBlockState())
                && Tables.allowed(Blocks.WALL_TORCH.defaultBlockState()), "ordinary blocks are fine");
        helper.assertTrue(!Tables.allowed(Blocks.COMMAND_BLOCK.defaultBlockState()) && !Tables.allowed(Blocks.BEDROCK.defaultBlockState())
                && !Tables.allowed(Blocks.WATER.defaultBlockState()) && !Tables.allowed(Blocks.STRUCTURE_BLOCK.defaultBlockState()), "no game-master blocks");
        // the land travels run-length encoded
        Grid g = new Grid(5, 4, 3);
        g.set(1, 2, 0, Blocks.STONE.defaultBlockState());
        g.set(4, 3, 2, Blocks.OAK_STAIRS.defaultBlockState());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        g.write(buf);
        Grid back = Grid.read(buf);
        helper.assertTrue(back.sx == 5 && back.get(1, 2, 0).is(Blocks.STONE) && back.get(4, 3, 2).equals(Blocks.OAK_STAIRS.defaultBlockState())
                && back.get(0, 0, 0).isAir(), "grid round trip");
        // a project keeps its site, spec, edits and markers
        Project p = new Project();
        p.setSite(new BlockPos(10, 64, 10), new BlockPos(20, 66, 18));
        helper.assertTrue(p.sx == 11 && p.sz == 9 && p.sy == Project.MIN_HEIGHT, "at least " + Project.MIN_HEIGHT + " high: " + p.sy);
        p.name = "Fishery";
        p.spec = BuildSpec.parse(HOME);
        p.door = Direction.WEST;
        p.floorY = 2;
        p.edits.put(Project.key(3, 4, 5), Blocks.BARREL.defaultBlockState());
        p.edits.put(Project.key(10, 11, 8), Blocks.AIR.defaultBlockState());
        p.markers.put(Project.key(2, 3, 1), "stad:work");
        Map<Integer, BlockState> refused = new HashMap<>();
        refused.put(Project.key(11, 0, 0), Blocks.STONE.defaultBlockState()); // outside the site
        refused.put(Project.key(1, 1, 1), Blocks.COMMAND_BLOCK.defaultBlockState());
        helper.assertTrue(Tables.applyForTest(p, refused) == 2, "edits outside the site and forbidden blocks are refused");
        Project q = Project.load(p.save());
        helper.assertTrue(q.min.equals(p.min) && q.sx == 11 && q.sy == p.sy && q.sz == 9 && q.name.equals("Fishery") && q.door == Direction.WEST
                && q.floorY == 2 && q.spec.equals(p.spec), "the project's settings kept");
        helper.assertTrue(q.edits.equals(p.edits) && q.markers.equals(p.markers), "edits and markers kept: " + q.edits);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "architect_fit", timeoutTicks = 100)
    public static void tableGeneratesABuildingThatFitsItsSite(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        Project p = site(helper);
        String problem = Tables.generate(helper.getLevel(), p, BuildSpec.parse(HOME), Direction.EAST);
        helper.assertTrue(problem == null, "generated: " + problem);
        helper.assertTrue(p.generated.keySet().stream().allMatch(p::inside), "all inside the site");
        helper.assertTrue(p.spec.width() >= 7 && p.spec.width() <= 11 && p.spec.depth() >= 7, "as big as the site lets it be: " + p.spec);
        helper.assertTrue(p.floorY == 0, "standing on the ground: " + p.floorY);
        List<Integer> doors = keysOf(p, s -> s.getBlock() instanceof DoorBlock);
        helper.assertTrue(!doors.isEmpty() && doors.stream().allMatch(k -> Project.kx(k) > p.sx / 2), "the door is on the east side: "
                + doors.stream().map(k -> Project.kx(k) + "," + Project.kz(k)).toList());
        helper.assertTrue(keysOf(p, s -> s.getBlock() instanceof BedBlock).size() == 4, "two beds");
        DesignCheck.Result check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(check.ready(), "a home: " + check.rows() + " / " + check.contents());
        // take the door out: it's open to the weather and has no door
        for (int k : doors) {
            p.edits.put(k, Blocks.AIR.defaultBlockState());
        }
        check = Tables.check(helper.getLevel(), p);
        helper.assertTrue(!check.ready() && check.missing().contains("a door"), "no door: " + check.missing());
        // generating again keeps hand edits on top
        p.edits.clear();
        p.edits.put(Project.key(0, 1, 0), Blocks.LANTERN.defaultBlockState());
        helper.assertTrue(Tables.generate(helper.getLevel(), p, BuildSpec.parse(HOME.replace("\"gable\"", "\"hip\"")), Direction.EAST) == null, "hip");
        helper.assertTrue(p.at(Project.key(0, 1, 0)).is(Blocks.LANTERN), "the hand edit stays");
        // three floors don't fit 12 high: the site grows upwards
        int sy = p.sy;
        helper.assertTrue(Tables.generate(helper.getLevel(), p, BuildSpec.parse(HOME.replace("\"floors\":1", "\"floors\":3")), Direction.EAST) == null,
                "three floors");
        helper.assertTrue(p.sy > sy && p.sy <= Project.MAX_HEIGHT, "grew from " + sy + " to " + p.sy);
        // too small a site says so
        Project small = new Project();
        small.setSite(helper.absolutePos(new BlockPos(5, G - 1, 5)), helper.absolutePos(new BlockPos(9, G + 2, 9)));
        String tooSmall = Tables.generate(helper.getLevel(), small, BuildSpec.parse(HOME), Direction.NORTH);
        helper.assertTrue(tooSmall != null && tooSmall.contains("too small"), "small: " + tooSmall);
        // a mine gets its shaft marker
        Project mine = site(helper);
        mine.type = BuildingDef.MINE;
        helper.assertTrue(Tables.generate(helper.getLevel(), mine, BuildSpec.parse(HOME), Direction.NORTH) == null, "mine");
        helper.assertTrue(mine.markers.containsValue("stad:shaft") && Tables.check(helper.getLevel(), mine).ready(), "with a shaft marker");
        cleanUp(helper, colony, owner);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "architect_plan", timeoutTicks = 100)
    public static void tablePlansAndSavesTheDesign(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        Project p = site(helper);
        helper.assertTrue(Tables.generate(helper.getLevel(), p, BuildSpec.parse(HOME), Direction.SOUTH) == null, "generated");
        p.name = "Lakeside House";
        Tables.Said nope = Tables.planNow(stranger, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(nope.error() && nope.message().contains("officers"), "only officers plan: " + nope.message());
        Tables.Said said = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!said.error(), "planned: " + said.message());
        helper.assertTrue(colony.buildings().size() == 1, "one building");
        Building b = colony.buildings().getFirst();
        helper.assertTrue(b.name.equals("Lakeside House") && b.is(BuildingDef.HOME) && b.design != null, "a home from the table: " + b.type());
        var bounds = b.placement().orElseThrow().bounds();
        BlockPos min = p.min, max = p.min.offset(p.sx - 1, p.sy - 1, p.sz - 1);
        helper.assertTrue(bounds.minX() >= min.getX() && bounds.minZ() >= min.getZ() && bounds.maxX() <= max.getX() && bounds.maxZ() <= max.getZ()
                && bounds.minY() >= min.getY(), "inside the site: " + bounds);
        for (Blueprint.Entry e : b.design.blueprint().entries()) {
            BlockState there = helper.getLevel().getBlockState(b.origin.offset(e.pos()));
            helper.assertTrue(!there.equals(e.state()), "nothing that's already there: " + e);
        }
        // again: it overlaps the one that isn't built yet; once that one's done, it's redesigned in its place
        Tables.Said again = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(again.error() && again.message().contains("isn't finished"), "overlap: " + again.message());
        b.status = Building.Status.DONE;
        again = Tables.planNow(owner, helper.getLevel(), helper.absolutePos(TABLE));
        helper.assertTrue(!again.error() && again.message().contains("in place of") && colony.buildings().size() == 1, "replaced: " + again.message());
        // saved into My pack: from the floor up, turned so the door faces north like pack buildings
        Tables.Said saved = Tables.saveNow(owner, helper.getLevel(), helper.absolutePos(TABLE), "Table Test House");
        helper.assertTrue(!saved.error(), "saved: " + saved.message());
        ResourceLocation key = MyPack.key("table_test_house");
        BuildingDef def = BuildingPacks.get(key).orElse(null);
        helper.assertTrue(def != null && def.type().equals(BuildingDef.HOME), "in My pack: " + saved.message());
        int depth = def.blueprint().size().getZ();
        helper.assertTrue(def.blueprint().entries().stream().filter(e -> e.state().getBlock() instanceof DoorBlock)
                .allMatch(e -> e.pos().getZ() < depth / 2), "the door faces north");
        try {
            MyPack.delete(helper.getLevel().getServer(), key);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        cleanUp(helper, colony, owner, stranger);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "architect_wand", timeoutTicks = 100)
    public static void wandSetsTheSiteAndTheTableKeepsItsProject(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner);
        BlockPos a = helper.absolutePos(new BlockPos(5, G - 1, 5)), b = helper.absolutePos(new BlockPos(12, G + 3, 14));
        dev.eiriksb.stad.pack.Scans.corner(owner, a, true);
        dev.eiriksb.stad.pack.Scans.corner(owner, b, false);
        Tables.use(owner, helper.getLevel(), helper.absolutePos(TABLE), true);
        Project p = Tables.project(helper.getLevel(), helper.absolutePos(TABLE)).orElseThrow();
        helper.assertTrue(p.hasSite() && p.min.equals(a) && p.sx == 8 && p.sz == 10 && p.sy == Project.MIN_HEIGHT, "the wand's corners: " + p.min);
        // off the town's land is refused
        dev.eiriksb.stad.pack.Scans.corner(owner, helper.absolutePos(new BlockPos(5, G - 1, 5)).offset(200, 0, 0), true);
        Tables.use(owner, helper.getLevel(), helper.absolutePos(TABLE), true);
        helper.assertTrue(p.min.equals(a), "kept the old site");
        p.name = "Kept";
        p.edits.put(Project.key(1, 1, 1), Blocks.LANTERN.defaultBlockState());
        helper.getLevel().destroyBlock(helper.absolutePos(TABLE), true);
        var items = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(TABLE)).inflate(2));
        helper.assertTrue(items.size() == 1 && items.getFirst().getItem().is(StadRegistries.ARCHITECT_TABLE_ITEM.get()), "dropped the table");
        var data = items.getFirst().getItem().get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null && data.copyTag().getCompound("Project").getString("Name").equals("Kept"), "with its project: " + data);
        items.forEach(net.minecraft.world.entity.Entity::discard);
        cleanUp(helper, colony, owner);
        helper.succeed();
    }
}
