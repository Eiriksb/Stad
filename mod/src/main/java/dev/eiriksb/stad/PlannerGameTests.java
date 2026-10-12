package dev.eiriksb.stad;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.planner.Drafts;
import dev.eiriksb.stad.planner.Generator;
import dev.eiriksb.stad.planner.MyPack;
import dev.eiriksb.stad.planner.Restyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** In-game tests for talk-to-plan (M6): specs, the generator, restyling, drafts and My pack. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlannerGameTests {
    private PlannerGameTests() {}

    private static final int G = 2;
    private static final ResourceLocation COTTAGE = ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage");

    private static BuildSpec spec(String json) {
        return BuildSpec.parse(json);
    }

    /** A spread of houses; each is also written to run/gametest/stad-designs/ to look at. */
    static final List<String> SAMPLES = List.of(
            "{\"name\":\"Small Cottage\",\"type\":\"home\",\"base\":\"new\",\"width\":7,\"depth\":7,\"floors\":1,\"roof\":\"gable\",\"walls\":\"oak\",\"frame\":\"spruce\",\"roof_material\":\"spruce\",\"floor\":\"oak\",\"beds\":2,\"rooms\":[],\"features\":[]}",
            "{\"name\":\"Stone House\",\"type\":\"home\",\"base\":\"new\",\"width\":9,\"depth\":11,\"floors\":2,\"roof\":\"gable\",\"walls\":\"stone_bricks\",\"frame\":\"oak\",\"roof_material\":\"dark_oak\",\"floor\":\"spruce\",\"beds\":3,\"rooms\":[\"kitchen\",\"living\",\"bedroom\",\"bedroom\"],\"features\":[\"chimney\",\"porch\"]}",
            "{\"name\":\"Long Hall\",\"type\":\"home\",\"base\":\"new\",\"width\":15,\"depth\":9,\"floors\":2,\"roof\":\"hip\",\"walls\":\"cobblestone\",\"frame\":\"dark_oak\",\"roof_material\":\"spruce\",\"floor\":\"oak\",\"beds\":6,\"rooms\":[\"kitchen\",\"storage\",\"living\",\"bedroom\",\"bedroom\",\"bedroom\"],\"features\":[\"big_windows\"]}",
            "{\"name\":\"Warehouse\",\"type\":\"warehouse\",\"base\":\"new\",\"width\":11,\"depth\":9,\"floors\":1,\"roof\":\"flat\",\"walls\":\"bricks\",\"frame\":\"none\",\"roof_material\":\"stone\",\"floor\":\"stone_bricks\",\"beds\":0,\"rooms\":[],\"features\":[]}",
            "{\"name\":\"Tower\",\"type\":\"home\",\"base\":\"new\",\"width\":7,\"depth\":7,\"floors\":3,\"roof\":\"hip\",\"walls\":\"sandstone\",\"frame\":\"none\",\"roof_material\":\"red_sandstone\",\"floor\":\"birch\",\"beds\":2,\"rooms\":[\"bedroom\",\"library\"],\"features\":[]}",
            "{\"name\":\"Deep Workshop\",\"type\":\"workshop\",\"base\":\"new\",\"width\":8,\"depth\":13,\"floors\":2,\"roof\":\"gable\",\"walls\":\"spruce\",\"frame\":\"dark_oak\",\"roof_material\":\"deepslate_bricks\",\"floor\":\"oak\",\"beds\":1,\"rooms\":[\"workshop\",\"storage\",\"bedroom\"],\"features\":[\"chimney\"]}");

    @GameTest(template = "empty", batch = "planner")
    public static void specsAreClampedToWhatCanBeBuilt(GameTestHelper helper) {
        BuildSpec s = spec("{\"name\":\"\",\"type\":\"palace\",\"width\":40,\"depth\":2,\"floors\":9,\"roof\":\"dome\",\"walls\":\"gold\","
                + "\"frame\":\"stone\",\"roof_material\":\"oak\",\"beds\":-3,\"rooms\":[\"bedroom\",\"dungeon\"],\"features\":[\"moat\",\"chimney\",\"chimney\"]}");
        helper.assertTrue(s.width() == BuildSpec.MAX_SIZE && s.depth() == BuildSpec.MIN_SIZE && s.floors() == BuildSpec.MAX_FLOORS, "sizes " + s);
        helper.assertTrue(s.type().equals("home") && s.name().equals("House") && s.roof().equals("gable"), "fallbacks " + s);
        helper.assertTrue(s.walls().equals("oak") && s.frame().equals("none") && s.beds() == 0, "materials " + s);
        helper.assertTrue(s.rooms().equals(List.of("bedroom")) && s.features().equals(List.of("chimney")), "lists " + s);
        helper.assertTrue(BuildSpec.parse(s.toJson()).equals(s), "round trip");
        var schema = BuildSpec.schema(List.of("medieval/cottage"));
        helper.assertTrue(schema.getAsJsonArray("required").size() == 14, "every field required");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "planner")
    public static void generatedHousesHaveWhatTheyShould(GameTestHelper helper) {
        Path dump = helper.getLevel().getServer().getServerDirectory().resolve("stad-designs");
        for (String json : SAMPLES) {
            BuildSpec s = spec(json);
            Generator.Result r = Generator.generate(s);
            Blueprint bp = r.blueprint();
            String what = s.name() + ": ";
            long doors = bp.entries().stream().filter(e -> e.state().getBlock() instanceof DoorBlock
                    && e.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER).count();
            helper.assertTrue(doors == 1, what + doors + " doors");
            BlockPos door = bp.entries().stream().filter(e -> e.state().getBlock() instanceof DoorBlock).findFirst().orElseThrow().pos();
            helper.assertTrue(door.getY() == 1, what + "door at " + door);
            helper.assertTrue(bp.entries().stream().noneMatch(e -> e.pos().getZ() < door.getZ() && e.pos().getY() == 1
                    && e.pos().getX() == door.getX() && !e.state().isAir()), what + "something blocks the door");
            BlockState inside = state(bp, door.south());
            helper.assertTrue(inside == null || inside.isAir(), what + "the way in is blocked by " + inside);
            long heads = bp.entries().stream().filter(e -> e.state().getBlock() instanceof BedBlock && e.state().getValue(BedBlock.PART) == BedPart.HEAD).count();
            long feet = bp.entries().stream().filter(e -> e.state().getBlock() instanceof BedBlock && e.state().getValue(BedBlock.PART) == BedPart.FOOT).count();
            helper.assertTrue(heads == feet && heads == r.beds(), what + heads + " heads, " + feet + " feet, " + r.beds() + " beds");
            int wanted = s.beds() == 0 && s.type().equals("home") ? 2 : s.beds();
            helper.assertTrue(r.beds() == wanted, what + r.beds() + " of " + wanted + " beds fitted: " + r.notes());
            for (var e : bp.entries()) {
                if (e.state().getBlock() instanceof BedBlock && e.state().getValue(BedBlock.PART) == BedPart.FOOT) {
                    BlockState head = state(bp, e.pos().relative(e.state().getValue(BedBlock.FACING)));
                    helper.assertTrue(head != null && head.getBlock() == e.state().getBlock(), what + "a bed without its head at " + e.pos());
                }
            }
            if (s.floors() > 1) {
                boolean climb = bp.entries().stream().anyMatch(e -> e.state().is(Blocks.LADDER) || (e.state().getBlock() instanceof StairBlock
                        && e.pos().getY() > 0 && e.pos().getY() < s.floors() * 4 && e.state().getBlock() == dev.eiriksb.stad.planner.Materials.get(s.floor())
                        .orElseThrow().stairs()));
                helper.assertTrue(climb, what + "no way upstairs");
            }
            if (s.type().equals("warehouse")) {
                long chests = bp.entries().stream().filter(e -> e.state().is(Blocks.CHEST)).count();
                helper.assertTrue(chests >= 8, what + "only " + chests + " chests");
            }
            if (s.has("chimney")) {
                helper.assertTrue(bp.entries().stream().anyMatch(e -> e.state().is(Blocks.CAMPFIRE)), what + "no chimney smoke");
            }
            Design d = Design.make(s);
            helper.assertTrue(!d.adapted() && d.blueprint().entries().size() == bp.entries().size(), what + "Design.make generates it");
            helper.assertTrue(Design.load(d.save()).blueprint().equals(d.blueprint()), what + "saves and loads the same");
            try {
                Files.createDirectories(dump);
                NbtIo.writeCompressed(bp.toStructure(), dump.resolve(d.slug() + ".nbt"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            helper.assertTrue(Generator.generate(s).blueprint().equals(bp), what + "the same spec gives the same house");
        }
        helper.succeed();
    }

    private static BlockState state(Blueprint bp, BlockPos pos) {
        return bp.entries().stream().filter(e -> e.pos().equals(pos)).map(Blueprint.Entry::state).findFirst().orElse(null);
    }

    @GameTest(template = "empty", batch = "planner")
    public static void packBuildingsCanBeRestyled(GameTestHelper helper) {
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        Restyle.Info info = Restyle.info(cottage);
        helper.assertTrue(info.width() == 7 && info.depth() == 9 && info.floors() == 1 && info.beds() == 2, "cottage: " + info.describe());
        helper.assertTrue(info.roof().equals("hip") && info.walls().equals("oak") && info.roofMaterial().equals("dark_oak")
                && info.floor().equals("cobblestone") && info.frame().equals("oak"), "cottage: " + info.describe());
        BuildSpec same = info.spec();
        helper.assertTrue(info.matches(same), "its own spec matches");
        BuildSpec stone = new BuildSpec("Stone Cottage", "home", "medieval/cottage", 7, 9, 1, "hip", "stone_bricks", "spruce",
                "spruce", "oak", 2, List.of(), List.of());
        Design d = Design.make(stone);
        helper.assertTrue(d.adapted() && d.type().equals(BuildingDef.HOME), "restyled, not generated: " + d.describe());
        helper.assertTrue(d.blueprint().size().equals(cottage.blueprint().size()), "same size");
        var mats = Placement.of(d.blueprint(), BlockPos.ZERO, Rotation.NONE).materials();
        helper.assertTrue(mats.get(net.minecraft.world.item.Items.STONE_BRICKS) == 65 && mats.get(net.minecraft.world.item.Items.OAK_PLANKS) >= 63 // floor and ceiling
                && !mats.containsKey(net.minecraft.world.item.Items.COBBLESTONE), "walls stone, floor oak: " + mats);
        helper.assertTrue(mats.containsKey(net.minecraft.world.item.Items.SPRUCE_STAIRS) && !mats.containsKey(net.minecraft.world.item.Items.DARK_OAK_STAIRS),
                "roof swapped: " + mats);
        helper.assertTrue(mats.containsKey(net.minecraft.world.item.Items.SPRUCE_LOG) && mats.get(net.minecraft.world.item.Items.RED_BED) == 1,
                "frame swapped, beds kept: " + mats);
        BuildSpec bigger = new BuildSpec("Big Cottage", "home", "medieval/cottage", 9, 11, 2, "hip", "oak", "oak", "dark_oak", "oak", 4,
                List.of(), List.of());
        Design g = Design.make(bigger);
        helper.assertTrue(!g.adapted() && g.beds() == 4, "a bigger one is drawn anew: " + g.describe());
        helper.succeed();
    }

    /** Every sample with stairs (not a ladder), built for real: a citizen at the door can find their way to every bed. */
    @GameTest(template = "lot", batch = "planner", timeoutTicks = 200)
    public static void upstairsBedroomsCanBeWalkedTo(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(0, G - 1, 0));
        int tested = 0;
        for (String json : SAMPLES) {
            BuildSpec s = spec(json);
            if (s.floors() == 1 || Math.min(s.width(), s.depth()) < 8) {
                continue;
            }
            Blueprint bp = Generator.generate(s).blueprint();
            Placement placement = Placement.of(bp, origin, Rotation.NONE);
            for (Placement.Step step : placement.steps()) {
                helper.getLevel().setBlock(step.pos(), step.state(), 2 | 16);
            }
            BlockPos door = bp.entries().stream().filter(e -> e.state().getBlock() instanceof DoorBlock).findFirst().orElseThrow().pos();
            BlockPos start = origin.offset(door.south());
            CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), helper.relativePos(start));
            c.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
            c.setOnGround(true); // only mobs on the ground look for paths
            for (var e : bp.entries()) {
                if (e.state().getBlock() instanceof BedBlock && e.state().getValue(BedBlock.PART) == BedPart.HEAD) {
                    BlockPos bed = origin.offset(e.pos());
                    net.minecraft.world.level.pathfinder.Path path = c.getNavigation().createPath(Set.of(bed), 2);
                    helper.assertTrue(path != null && path.canReach(), s.name() + ": no way from the door (" + door.south() + ") to the bed at "
                            + e.pos() + ": " + (path == null ? "no path" : "got to " + path.getEndNode()));
                }
            }
            c.discard();
            for (var box : List.of(placement.bounds())) {
                BlockPos.betweenClosedStream(box).forEach(p -> helper.getLevel().setBlock(p, p.getY() == origin.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16));
            }
            tested++;
        }
        helper.assertTrue(tested >= 3, "only " + tested + " houses walked");
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "planner", timeoutTicks = 200)
    public static void draftsAreDrawnPlacedAndApproved(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos hall = helper.absolutePos(new BlockPos(2, G, 2));
        helper.setBlock(new BlockPos(2, G, 2), StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), hall, owner.getUUID(),
                owner.getGameProfile().getName(), 12);
        Colony colony = f.colony();
        helper.assertTrue(colony != null, "no colony: " + f.problem());
        BlockPos stand = helper.absolutePos(new BlockPos(2, G, 15));
        owner.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        CitizenEntity builder = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 9);
        ColonyManager.get(helper.getLevel().getServer()).join(colony, builder);
        colony.citizen(builder.getUUID()).orElseThrow().job = Jobs.BUILDER;
        BuildSpec s = spec(SAMPLES.get(0));
        Drafts.Draft d = Drafts.draw(owner, colony, builder.getUUID(), s, Drafts.Where.AUTO, null);
        String problem = Drafts.problem(d, colony, helper.getLevel());
        helper.assertTrue(problem == null, "the draft was put somewhere it can't go: " + problem + " at " + d.site + "; built in the lot: "
                + Drafts.builtKinds(helper.getLevel(), new net.minecraft.world.level.levelgen.structure.BoundingBox(helper.absolutePos(BlockPos.ZERO).getX(),
                helper.absolutePos(BlockPos.ZERO).getY(), helper.absolutePos(BlockPos.ZERO).getZ(), helper.absolutePos(new BlockPos(17, 15, 17)).getX(),
                helper.absolutePos(new BlockPos(17, 15, 17)).getY(), helper.absolutePos(new BlockPos(17, 15, 17)).getZ())));
        helper.assertTrue(!d.placement().bounds().isInside(owner.blockPosition()), "not on top of the player");
        helper.assertTrue(Drafts.where(d, owner).contains("blocks"), "where: " + Drafts.where(d, owner));
        Rotation before = d.rotation;
        Drafts.move(owner, Drafts.Where.TURN, null);
        helper.assertTrue(d.rotation == before.getRotated(Rotation.CLOCKWISE_90), "turned");
        Drafts.move(owner, Drafts.Where.TURN, null);
        Drafts.move(owner, Drafts.Where.TURN, null);
        Drafts.move(owner, Drafts.Where.TURN, null);
        // a revision keeps the place
        BlockPos site = d.site;
        BuildSpec stone = spec(SAMPLES.get(0).replace("\"walls\":\"oak\"", "\"walls\":\"cobblestone\""));
        Drafts.draw(owner, colony, builder.getUUID(), stone, Drafts.Where.SAME, null);
        helper.assertTrue(d.site.equals(site) && d.design.spec().walls().equals("cobblestone"), "revised in place");

        // it won't knock down what's already there
        BlockPos shed = d.placement().bounds().getCenter().atY(d.site.getY() + 1);
        helper.getLevel().setBlock(shed, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        String torn = Drafts.problem(d, colony, helper.getLevel());
        helper.assertTrue(torn != null && torn.contains("tear down"), "built blocks in the way: " + torn);
        helper.assertTrue(Drafts.approve(owner).outcome() == Order.Outcome.INVALID, "can't approve over a building");
        helper.getLevel().setBlock(shed, Blocks.AIR.defaultBlockState(), 3);

        // a resented leader's builder won't build it, and the draft stays
        colony.setStanding(owner.getUUID(), -90);
        Drafts.Approval no = Drafts.approve(owner);
        helper.assertTrue(no.outcome() == Order.Outcome.REFUSED && no.building() == null, "refused: " + no);
        helper.assertTrue(Drafts.get(owner.getUUID()).isPresent(), "a refused draft is kept");
        colony.setStanding(owner.getUUID(), 50);

        Drafts.Approval a = Drafts.approve(owner);
        helper.assertTrue(a.building() != null && (a.outcome() == Order.Outcome.ACCEPTED || a.outcome() == Order.Outcome.GRUDGING),
                "approved: " + a);
        Building b = a.building();
        helper.assertTrue(b.design != null && b.placement().isPresent() && b.is(BuildingDef.HOME), "a planned design");
        helper.assertTrue(Drafts.get(owner.getUUID()).isEmpty(), "the draft is gone once approved");
        helper.assertTrue(colony.workFor(builder.getUUID()).map(x -> x == b).orElse(false), "the builder will build it");

        ColonyManager.get(helper.getLevel().getServer()).dissolve(helper.getLevel().getServer(), colony);
        helper.getLevel().getServer().getPlayerList().remove(owner);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "planner")
    public static void designsCanBeKeptInMyPack(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        Design d = Design.make(spec(SAMPLES.get(4)).withBase("new"));
        ResourceLocation key = MyPack.save(server, d, "Tester");
        try {
            BuildingDef def = BuildingPacks.get(key).orElse(null);
            helper.assertTrue(def != null && def.spec() != null && def.pack().equals(MyPack.PACK), "in the packs: " + def);
            helper.assertTrue(def.blueprint().equals(d.blueprint()), "same blocks");
            Path root = MyPack.root(server);
            helper.assertTrue(Files.exists(root.resolve("pack.mcmeta")), "a data pack");
            Path nbt = root.resolve("data/" + MyPack.NAMESPACE + "/structure/" + MyPack.PACK + "/" + d.slug() + ".nbt");
            Blueprint back = Blueprint.read(NbtIo.readCompressed(nbt, net.minecraft.nbt.NbtAccounter.unlimitedHeap()), BuiltInRegistries.BLOCK.asLookup());
            helper.assertTrue(back.size().equals(d.blueprint().size()) && back.entries().size() == d.blueprint().entries().size(),
                    "the structure file reads back");
            // asking for it by name again draws the same building
            Design again = Design.make(BuildSpec.parse(def.spec()).withBase(key.getPath()));
            helper.assertTrue(again.blueprint().equals(d.blueprint()), "saved designs come back the same");
        } finally {
            try (var files = Files.walk(MyPack.root(server))) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        helper.succeed();
    }
}
