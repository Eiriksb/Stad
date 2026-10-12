package dev.eiriksb.stad;

import com.google.gson.JsonParser;
import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Substitutions;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Adoption;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.pack.PackEditor;
import dev.eiriksb.stad.pack.Palette;
import dev.eiriksb.stad.pack.Palettes;
import dev.eiriksb.stad.pack.Roles;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.planner.Drafts;
import dev.eiriksb.stad.planner.Landmarks;
import dev.eiriksb.stad.planner.Materials;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** In-game tests for custom packs and adoption (M7): roles, palettes, markers, scanning, adopting, swaps, landmarks. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackGameTests {
    private PackGameTests() {}

    private static final int G = 2;
    private static final ResourceLocation COTTAGE = ResourceLocation.fromNamespaceAndPath("stad", "medieval/cottage");

    private static Design house(int sample) {
        return Design.make(BuildSpec.parse(PlannerGameTests.SAMPLES.get(sample)).withBase("new"));
    }

    private static Map<Block, Integer> count(Blueprint bp, Roles.Analysis a, Roles.Role role) {
        Map<Block, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < bp.entries().size(); i++) {
            if (a.roles()[i] == role) {
                m.merge(bp.entries().get(i).state().getBlock(), 1, Integer::sum);
            }
        }
        return m;
    }

    @GameTest(template = "empty", batch = "packs")
    public static void rolesAreGuessedFromShapeAndPlace(GameTestHelper helper) {
        Design d = house(1); // stone brick walls, oak frame, dark oak roof, spruce floors, two storeys
        Blueprint bp = d.blueprint();
        Roles.Analysis a = Roles.analyse(bp);
        Map<Block, Integer> walls = count(bp, a, Roles.Role.WALL), roof = count(bp, a, Roles.Role.ROOF), frame = count(bp, a, Roles.Role.FRAME);
        helper.assertTrue(walls.keySet().equals(java.util.Set.of(Blocks.STONE_BRICKS)), "walls: " + walls);
        helper.assertTrue(roof.getOrDefault(Blocks.DARK_OAK_STAIRS, 0) > 20, "roof: " + roof);
        helper.assertTrue(frame.getOrDefault(Blocks.OAK_LOG, 0) > 10, "frame: " + frame);
        long spruce = bp.entries().stream().filter(e -> e.state().is(Blocks.SPRUCE_PLANKS)).count();
        int spruceFloor = count(bp, a, Roles.Role.FLOOR).getOrDefault(Blocks.SPRUCE_PLANKS, 0);
        Map<String, Integer> byY = new java.util.TreeMap<>();
        for (int i = 0; i < bp.entries().size(); i++) {
            if (bp.entries().get(i).state().is(Blocks.SPRUCE_PLANKS)) {
                byY.merge(bp.entries().get(i).pos().getY() + ":" + a.roles()[i], 1, Integer::sum);
            }
        }
        helper.assertTrue(spruceFloor >= spruce * 0.7, "upstairs floors are floors too: " + spruceFloor + " of " + spruce + " " + byY
                + " roof at " + a.roofY());
        helper.assertTrue(!count(bp, a, Roles.Role.WINDOW).isEmpty() && !count(bp, a, Roles.Role.DOOR).isEmpty()
                && count(bp, a, Roles.Role.FURNITURE).keySet().stream().anyMatch(b -> b instanceof BedBlock), "windows, doors, beds");
        Map<Roles.Role, String> m = Roles.materials(a);
        helper.assertTrue("stone_bricks".equals(m.get(Roles.Role.WALL)) && "dark_oak".equals(m.get(Roles.Role.ROOF))
                && "spruce".equals(m.get(Roles.Role.FLOOR)) && "oak".equals(m.get(Roles.Role.FRAME)), "materials " + m);
        // the rows the editor shows add up, and a correction moves a whole row
        int total = Roles.groups(a).stream().mapToInt(Roles.Group::count).sum();
        helper.assertTrue(total == bp.solidBlocks(), "rows " + total + " vs " + bp.solidBlocks());
        Roles.Analysis fixed = Roles.analyse(bp, List.of(new Roles.Fix(Blocks.STONE_BRICKS, Roles.Role.WALL, Roles.Role.TRIM)));
        helper.assertTrue(count(bp, fixed, Roles.Role.WALL).isEmpty() && count(bp, fixed, Roles.Role.TRIM).getOrDefault(Blocks.STONE_BRICKS, 0)
                .equals(walls.get(Blocks.STONE_BRICKS)), "fixed");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "packs")
    public static void palettesRestyleByRole(GameTestHelper helper) throws Exception {
        BuildingDef cottage = BuildingPacks.get(COTTAGE).orElseThrow();
        Palette nordic = Palettes.get("nordic").orElse(null);
        helper.assertTrue(nordic != null && Palettes.all().size() >= 5, "the mod's palettes are loaded: " + Palettes.all().size());
        helper.assertTrue(Palettes.get("Nordic").isPresent() && Palettes.get("stad:nordic").isPresent(), "found by name and id");
        Design styled = Design.styled(cottage, nordic);
        Roles.Analysis before = cottage.roles();
        Roles.Analysis after = Roles.analyse(styled.blueprint());
        for (int i = 0; i < cottage.blueprint().entries().size(); i++) {
            BlockState was = cottage.blueprint().entries().get(i).state(), now = styled.blueprint().entries().get(i).state();
            Roles.Role r = before.roles()[i];
            if (r == null || !r.restyled) {
                helper.assertTrue(was == now, "a " + r + " block changed: " + was + " -> " + now);
            } else if (Roles.shape(was) != null && nordic.materials().containsKey(r)) {
                Block expected = nordic.materials().get(r).block(Roles.shape(was));
                helper.assertTrue(expected == null || now.is(expected), r + " " + was + " -> " + now + ", wanted " + expected);
            }
        }
        helper.assertTrue(!Roles.materials(before).get(Roles.Role.WALL).equals("spruce") && "spruce".equals(Roles.materials(after).get(Roles.Role.WALL)),
                "walls are spruce now: " + Roles.materials(after));
        helper.assertTrue(styled.name().equals("Nordic Cottage") && styled.adapted(), "named " + styled.name());
        Path dump = helper.getLevel().getServer().getServerDirectory().resolve("stad-designs");
        Files.createDirectories(dump);
        NbtIo.writeCompressed(cottage.blueprint().toStructure(), dump.resolve("style_0_own.nbt"));
        int n = 1;
        for (Palette p : Palettes.all()) {
            NbtIo.writeCompressed(Design.styled(cottage, p).blueprint().toStructure(), dump.resolve("style_" + n++ + "_" + p.id().getPath() + ".nbt"));
        }
        // a correction keeps blocks out of the restyle
        Block floor = cottage.blueprint().entries().get(java.util.stream.IntStream.range(0, before.roles().length)
                .filter(i -> before.roles()[i] == Roles.Role.FLOOR).findFirst().orElseThrow()).state().getBlock();
        Roles.Analysis kept = Roles.analyse(cottage.blueprint(), List.of(new Roles.Fix(floor, Roles.Role.FLOOR, Roles.Role.DECOR)));
        Blueprint keptFloor = Roles.restyle(kept, nordic.materials());
        for (int i = 0; i < kept.roles().length; i++) {
            if (before.roles()[i] == Roles.Role.FLOOR && cottage.blueprint().entries().get(i).state().is(floor)) {
                helper.assertTrue(keptFloor.entries().get(i).state().is(floor), "the floor was left alone");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "packs")
    public static void markersSurviveSavingAndTurning(GameTestHelper helper) {
        Blueprint bp = new Blueprint(new Vec3i(4, 3, 6), List.of(new Blueprint.Entry(new BlockPos(0, 0, 0), Blocks.CHEST.defaultBlockState()),
                new Blueprint.Entry(new BlockPos(1, 0, 0), Blocks.AIR.defaultBlockState())),
                List.of(new Blueprint.Marker(new BlockPos(1, 0, 0), "stad:storage"), new Blueprint.Marker(new BlockPos(3, 1, 5), "stad:work")));
        Blueprint read = Blueprint.read(bp.toStructure(), BuiltInRegistries.BLOCK.asLookup());
        helper.assertTrue(read.markers().size() == 2 && read.markers("stad:work").getFirst().pos().equals(new BlockPos(3, 1, 5)),
                "structure file: " + read.markers());
        helper.assertTrue(read.entries().stream().noneMatch(e -> e.state().is(Blocks.STRUCTURE_BLOCK)), "markers aren't built");
        Blueprint loaded = Blueprint.load(bp.save(), BuiltInRegistries.BLOCK.asLookup());
        helper.assertTrue(loaded.markers().equals(bp.markers()), "saved with a town: " + loaded.markers());
        Blueprint turned = bp.rotated(Rotation.CLOCKWISE_90);
        helper.assertTrue(turned.size().equals(new Vec3i(6, 3, 4)), "size turns");
        helper.assertTrue(turned.markers("stad:work").getFirst().pos().equals(Placement.rotate(new BlockPos(3, 1, 5), bp.size(), Rotation.CLOCKWISE_90)),
                "markers turn with it");
        BlockPos origin = new BlockPos(100, 64, 100);
        Placement p = Placement.of(bp, origin, Rotation.CLOCKWISE_180);
        helper.assertTrue(p.markers("stad:storage").getFirst().equals(origin.offset(Placement.rotate(new BlockPos(1, 0, 0), bp.size(), Rotation.CLOCKWISE_180))),
                "placed markers");
        // a Data structure block in the world is read as a marker when scanned
        BlockPos at = helper.absolutePos(new BlockPos(1, G, 1));
        helper.getLevel().setBlock(at, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.DATA), 3);
        if (helper.getLevel().getBlockEntity(at) instanceof StructureBlockEntity sb) {
            sb.setMode(StructureMode.DATA);
            sb.setMetaData("stad:sit");
        }
        Blueprint scanned = Blueprint.capture(helper.getLevel(), new BoundingBox(at.getX(), at.getY(), at.getZ(), at.getX() + 1, at.getY(), at.getZ()));
        helper.assertTrue(scanned.markers().equals(List.of(new Blueprint.Marker(BlockPos.ZERO, "stad:sit"))), "scanned: " + scanned.markers());
        helper.succeed();
    }

    /** Puts a blueprint into the world. */
    private static BoundingBox build(ServerLevel level, Blueprint bp, BlockPos origin, Rotation rotation) {
        Placement p = Placement.of(bp, origin, rotation);
        for (Placement.Step s : p.steps()) {
            level.setBlock(s.pos(), s.state(), 2 | 16);
        }
        return p.bounds();
    }

    /** Takes the barrier ceiling off a test plot, so the sky shows (and outdoors is outdoors). */
    private static void openSky(GameTestHelper helper) {
        for (int y = 14; y <= 20; y++) {
            for (int x = -1; x <= 19; x++) {
                for (int z = -1; z <= 19; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    if (helper.getLevel().getBlockState(p).is(Blocks.BARRIER)) {
                        helper.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void cleanMyPack(ServerLevel level) throws Exception {
        Path root = MyPack.root(level.getServer());
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @GameTest(template = "lot", batch = "packs_scan", timeoutTicks = 100)
    public static void scannedBuildingsGoIntoMyPack(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Design d = house(0); // the small cottage, built facing south (its door on the south side)
        BlockPos origin = helper.absolutePos(new BlockPos(4, G - 1, 4));
        BoundingBox box = build(level, d.blueprint(), origin, Rotation.CLOCKWISE_180);
        BlockPos marker = new BlockPos(box.minX() + 2, box.minY() + 1, box.minZ() + 2);
        level.setBlock(marker, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.DATA), 3);
        if (level.getBlockEntity(marker) instanceof StructureBlockEntity sb) {
            sb.setMode(StructureMode.DATA);
            sb.setMetaData("stad:work");
        }
        player.moveTo(box.getCenter().getX() + 0.5, box.minY() + 1, box.maxZ() + 4.5); // standing in front, to the south
        try {
            PackEditor.Session s = PackEditor.scan(player, box);
            helper.assertTrue(s.type().equals(BuildingDef.HOME) && s.blueprint().markers().size() == 1, "guessed a home, with its marker: " + s);
            BlockPos door = s.blueprint().entries().stream().filter(e -> e.state().getBlock() instanceof DoorBlock).findFirst().orElseThrow().pos();
            helper.assertTrue(door.getZ() <= 1, "turned so the door faces north like pack buildings: door at " + door);
            var payload = PackEditor.payload(s);
            helper.assertTrue(payload.needs().getFirst().isEmpty() && !payload.rows().isEmpty() && payload.markers().contains("work"),
                    "editor: " + payload.needs() + " " + payload.markers());
            ResourceLocation key = PackEditor.save(player, s, "Scanned Cottage", BuildingDef.HOME,
                    List.of(new Roles.Fix(Blocks.OAK_PLANKS, Roles.Role.WALL, Roles.Role.TRIM)));
            BuildingDef def = BuildingPacks.get(key).orElse(null);
            helper.assertTrue(def != null && def.fixes().size() == 1 && def.spec() == null && MyPack.contains(key), "in My pack: " + def);
            Path data = MyPack.root(level.getServer()).resolve("data").resolve(MyPack.NAMESPACE);
            var manifest = JsonParser.parseString(Files.readString(data.resolve("stad/buildings/" + MyPack.PACK + "/scanned_cottage.json"))).getAsJsonObject();
            helper.assertTrue(manifest.getAsJsonArray("roles").size() == 1 && manifest.get("type").getAsString().equals("stad:home"), "manifest " + manifest);
            Blueprint back = Blueprint.read(NbtIo.readCompressed(data.resolve("structure/" + MyPack.PACK + "/scanned_cottage.nbt"),
                    NbtAccounter.unlimitedHeap()), BuiltInRegistries.BLOCK.asLookup());
            helper.assertTrue(back.markers().equals(s.blueprint().markers()), "markers saved in the structure");
            // a second scan with the same name doesn't overwrite the first
            ResourceLocation second = PackEditor.save(player, PackEditor.scan(player, box), "Scanned Cottage", BuildingDef.HOME, List.of());
            helper.assertTrue(!second.equals(key), "new id " + second);
            // a hand-made building is something the builder can restyle
            helper.assertTrue(Design.make(new BuildSpec("Scanned Cottage", "home", key.getPath(), d.blueprint().size().getX(), d.blueprint().size().getZ(),
                    1, "gable", "birch", "none", "spruce", "oak", 2, List.of(), List.of())).blueprint() != null, "restylable");
            Path zip = MyPack.export(level.getServer(), "test_pack");
            try (ZipFile z = new ZipFile(zip.toFile())) {
                helper.assertTrue(z.getEntry("pack.mcmeta") != null && z.getEntry("data/" + MyPack.NAMESPACE + "/stad/buildings/" + MyPack.PACK
                        + "/scanned_cottage.json") != null, "exported zip");
            }
            Files.deleteIfExists(zip);
            helper.assertTrue(MyPack.delete(level.getServer(), key) && BuildingPacks.get(key).isEmpty(), "deleted");
            MyPack.delete(level.getServer(), second);
        } finally {
            cleanMyPack(level);
            level.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** A 3 x 4 room (inside) with oak plank walls and roof, a door on the north side. Returns the room's inside corner. */
    private static BlockPos room(GameTestHelper helper) {
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 9; z++) {
                for (int y = G; y <= G + 3; y++) {
                    boolean wall = x == 4 || x == 8 || z == 4 || z == 9 || y == G + 3;
                    helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.OAK_PLANKS : Blocks.AIR);
                }
            }
        }
        door(helper, true);
        return new BlockPos(5, G, 5);
    }

    private static void door(GameTestHelper helper, boolean there) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        helper.setBlock(new BlockPos(6, G, 4), there ? lower : Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(6, G + 1, 4), there ? lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER) : Blocks.AIR.defaultBlockState());
    }

    @GameTest(template = "lot", batch = "packs_adopt", timeoutTicks = 100)
    public static void housesCanBeAdopted(GameTestHelper helper) {
        openSky(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(new BlockPos(14, G, 14), StadRegistries.TOWN_HALL.get());
        Colony colony = ColonyManager.get(level.getServer()).found(level, helper.absolutePos(new BlockPos(14, G, 14)), owner.getUUID(),
                owner.getGameProfile().getName(), 16).colony();
        room(helper);
        BlockPos inside = helper.absolutePos(new BlockPos(6, G, 7));
        try {
            Adoption.Check outside = Adoption.check(level, colony, helper.absolutePos(new BlockPos(12, G, 2)), null);
            helper.assertTrue(outside.problem() != null, "outdoors isn't a building");
            Adoption.Check empty = Adoption.check(level, colony, inside, BuildingDef.HOME);
            helper.assertTrue(empty.problem() == null && empty.missing().equals(List.of("a bed")), "an empty room needs a bed: " + empty);
            helper.assertTrue(empty.survey().doors().size() == 1 && empty.survey().front() == Direction.NORTH, "the way in: " + empty.survey());
            helper.setBlock(new BlockPos(6, G + 3, 7), Blocks.AIR);
            Adoption.Check hole = Adoption.check(level, colony, inside, BuildingDef.HOME);
            helper.assertTrue(hole.missing().contains("a roof without holes"), "a hole in the roof: " + hole.missing());
            helper.setBlock(new BlockPos(6, G + 3, 7), Blocks.OAK_PLANKS);
            door(helper, false);
            Adoption.Check open = Adoption.check(level, colony, inside, BuildingDef.HOME);
            helper.assertTrue(open.problem() == null && open.missing().contains("walls all round") && open.missing().contains("a door"),
                    "an open doorway: " + open);
            door(helper, true);
            helper.setBlock(new BlockPos(5, G, 8), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.HEAD));
            helper.setBlock(new BlockPos(5, G, 7), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH)
                    .setValue(BedBlock.PART, BedPart.FOOT));
            Adoption.Check home = Adoption.check(level, colony, inside, null);
            helper.assertTrue(home.ok() && home.type().equals(BuildingDef.HOME), "now it's a home: " + home);
            helper.assertTrue(home.wishes().contains("a light"), "it'd like a light: " + home.wishes());
            Adoption.Check warehouse = Adoption.check(level, colony, inside, BuildingDef.WAREHOUSE);
            helper.assertTrue(warehouse.missing().equals(List.of("at least four chests or barrels")), "not a warehouse: " + warehouse.missing());

            Adoption.Result adopted = Adoption.adopt(owner, level, colony, home, "Test House");
            Building b = adopted.building();
            helper.assertTrue(b != null && b.done() && b.is(BuildingDef.HOME) && b.design != null, "adopted: " + adopted);
            helper.assertTrue(b.placement().orElseThrow().bounds().isInside(helper.absolutePos(new BlockPos(5, G, 8))), "the bed is in it");
            helper.assertTrue(b.design.blueprint().entries().stream().anyMatch(e -> e.state().getBlock() instanceof BedBlock), "as it stands");
            Adoption.Check again = Adoption.check(level, colony, inside, null);
            helper.assertTrue(again.existing() == b, "standing in it again finds it");

            CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 12, G, 12);
            ColonyManager.get(level.getServer()).join(colony, c);
            colony.setStanding(owner.getUUID(), 40);
            Order.Result given = Adoption.giveHome(owner, c, b);
            helper.assertTrue(given.done(), "moved in: " + given);
            helper.assertTrue(helper.absolutePos(new BlockPos(5, G, 8)).equals(c.home().orElse(null)), "their bed: " + c.home());
            helper.assertTrue(Adoption.giveHome(owner, c, b).outcome() == Order.Outcome.INVALID, "already lives there");
            helper.assertTrue(colony.orders().stream().anyMatch(o -> o.kind() == dev.eiriksb.stad.colony.OrderKind.ASSIGN_HOME), "logged");
        } finally {
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "packs_swap", timeoutTicks = 100)
    public static void buildersAskBeforeSwappingMaterials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(new BlockPos(2, G, 2), StadRegistries.TOWN_HALL.get());
        BlockPos hallPos = helper.absolutePos(new BlockPos(2, G, 2));
        Colony colony = ColonyManager.get(level.getServer()).found(level, hallPos, owner.getUUID(), owner.getGameProfile().getName(), 16).colony();
        try {
            Design d = house(0); // oak walls and floor, spruce frame and roof
            Building b = colony.addBuilding(ResourceLocation.fromNamespaceAndPath("stad", "design/test"), d.name(), helper.absolutePos(new BlockPos(6, G - 1, 6)),
                    Rotation.NONE, level.getGameTime(), d);
            Map<net.minecraft.world.item.Item, Integer> needs = b.placement().orElseThrow().materials();
            TownHallBlockEntity hall = (TownHallBlockEntity) level.getBlockEntity(hallPos);
            // plenty of everything but oak; birch instead
            for (var e : needs.entrySet()) {
                var known = Materials.of(Block.byItem(e.getKey()));
                net.minecraft.world.item.Item item = known.isPresent() && known.get().getKey().name().equals("oak")
                        && known.get().getValue() != Materials.Shape.DOOR // the oak door is there
                        ? Materials.get("birch").orElseThrow().block(known.get().getValue()).asItem() : e.getKey();
                for (int left = e.getValue(); left > 0; left -= 64) {
                    ColonyStorage.insert(hall, new ItemStack(item, Math.min(64, left)));
                }
            }
            b.missing = Construction.missing(level, b, b.placement().orElseThrow(), ColonyStorage.supply(level, colony));
            helper.assertTrue(b.missing.containsKey(Items.OAK_PLANKS), "out of oak: " + b.missing);
            Substitutions.Offer offer = Substitutions.offer(level, colony, b);
            helper.assertTrue(offer != null && offer.from().name().equals("oak") && offer.to().name().equals("birch"), "offer: " + offer);
            helper.assertTrue(Substitutions.answer("yes, use the birch", offer) == Boolean.TRUE && Substitutions.answer("no, wait for oak", offer) == Boolean.FALSE
                    && Substitutions.answer("how's the weather?", offer) == null && Substitutions.answer("Use birch then", offer) == Boolean.TRUE, "answers");
            b.offer = offer;
            helper.assertTrue(Substitutions.accept(owner, colony, b) == null && b.swaps.size() == 1 && b.offer == null, "accepted");
            Map<net.minecraft.world.item.Item, Integer> now = b.placement().orElseThrow().materials();
            helper.assertTrue(!now.containsKey(Items.OAK_PLANKS) && now.getOrDefault(Items.BIRCH_PLANKS, 0) > 0, "built in birch now: " + now);
            helper.assertTrue(!offer.shapes().contains(Materials.Shape.DOOR) && (!needs.containsKey(Items.OAK_DOOR) || now.containsKey(Items.OAK_DOOR)),
                    "only what's short is swapped: " + offer.shapes());
            helper.assertTrue(Construction.missing(level, b, b.placement().orElseThrow(), ColonyStorage.supply(level, colony)).isEmpty(),
                    "nothing missing: " + Construction.missing(level, b, b.placement().orElseThrow(), ColonyStorage.supply(level, colony)));
            // swaps are kept with the town
            var tag = colony.save();
            Colony loaded = Colony.load(tag);
            helper.assertTrue(loaded.building(b.id).orElseThrow().swaps.equals(b.swaps), "saved");
            // saying no: not asked again
            Building c = colony.addBuilding(ResourceLocation.fromNamespaceAndPath("stad", "design/test2"), d.name(), helper.absolutePos(new BlockPos(6, G - 1, 6)),
                    Rotation.NONE, level.getGameTime(), d);
            c.missing = Construction.missing(level, c, c.placement().orElseThrow(), ColonyStorage.supply(level, colony));
            c.offer = Substitutions.offer(level, colony, c);
            helper.assertTrue(c.offer != null && Substitutions.decline(owner, colony, c) == null, "declined");
            helper.assertTrue(Substitutions.offer(level, colony, c) == null, "not asked again: " + Substitutions.offer(level, colony, c));
        } finally {
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "packs_lake", timeoutTicks = 100)
    public static void draftsCanFaceTheLake(GameTestHelper helper) {
        openSky(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(new BlockPos(2, G, 2), StadRegistries.TOWN_HALL.get());
        Colony colony = ColonyManager.get(level.getServer()).found(level, helper.absolutePos(new BlockPos(2, G, 2)), owner.getUUID(),
                owner.getGameProfile().getName(), 14).colony();
        // a pond in the far corner
        for (int x = 11; x <= 17; x++) {
            for (int z = 11; z <= 17; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }
        BlockPos stand = helper.absolutePos(new BlockPos(2, G, 15));
        owner.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        try {
            helper.assertTrue(Landmarks.kind("put it facing the lake").orElse(null) == Landmarks.Kind.WATER
                    && Landmarks.kind("by the forest").orElse(null) == Landmarks.Kind.TREES && Landmarks.kind("next to the hut").isEmpty(), "kinds");
            var lake = Landmarks.find(level, colony, "the lake", owner.blockPosition());
            helper.assertTrue(lake.isPresent() && level.getFluidState(lake.get().pos()).isSource(), "found the pond: " + lake);
            Design small = Design.make(new BuildSpec("Hut", "home", "new", 5, 5, 1, "gable", "oak", "none", "spruce", "oak", 1, List.of(), List.of()));
            Drafts.Draft d = Drafts.draw(owner, colony, null, small.spec(), Drafts.Where.NEAR, "lake");
            helper.assertTrue("the water".equals(d.landmark), "by the water: " + d.landmark + " at " + d.site);
            Direction front = d.rotation.rotate(Direction.NORTH);
            Vec3 toLake = Vec3.atCenterOf(lake.get().pos()).subtract(Vec3.atCenterOf(d.placement().bounds().getCenter()));
            helper.assertTrue(front.getStepX() * toLake.x + front.getStepZ() * toLake.z > 0, "the door faces the lake: " + front + " " + toLake);
            helper.assertTrue(Drafts.where(d, owner).contains("water"), Drafts.where(d, owner));
        } finally {
            Drafts.cancel(owner);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }
}
