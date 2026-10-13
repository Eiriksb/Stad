package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.ColonyProtection;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Design;
import dev.eiriksb.stad.war.Combat;
import dev.eiriksb.stad.war.Envoys;
import dev.eiriksb.stad.war.Politics;
import dev.eiriksb.stad.war.Raids;
import dev.eiriksb.stad.war.War;
import dev.eiriksb.stad.war.Wars;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** In-game tests for raids and rivalry (M8): protection, guards, mob raids, poaching, wars, peace and envoys. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WarGameTests {
    private WarGameTests() {}

    private static final int G = 2;
    private static final long DAY = 24 * 60 * 60_000L;

    private static Colony found(GameTestHelper helper, ServerPlayer owner, int x, int z, int radius) {
        helper.setBlock(new BlockPos(x, G, z), StadRegistries.TOWN_HALL.get());
        ColonyManager.Founding f = ColonyManager.get(helper.getLevel().getServer()).found(helper.getLevel(), helper.absolutePos(new BlockPos(x, G, z)),
                owner.getUUID(), owner.getGameProfile().getName(), radius);
        helper.assertTrue(f.colony() != null, "no colony: " + f.problem());
        f.colony().politics.windowFrom = f.colony().politics.windowTo = 0; // any time
        return f.colony();
    }

    private static void move(ServerPlayer p, GameTestHelper helper, int x, int z) {
        BlockPos at = helper.absolutePos(new BlockPos(x, G, z));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static void cleanUp(GameTestHelper helper, List<Colony> colonies, List<ServerPlayer> players) {
        var server = helper.getLevel().getServer();
        for (Colony c : colonies) {
            for (War w : Wars.of(server, c.id)) {
                Wars.end(server, w, War.Outcome.PEACE, System.currentTimeMillis());
            }
            ColonyManager.get(server).dissolve(server, c);
        }
        players.forEach(p -> server.getPlayerList().remove(p));
    }

    @GameTest(template = "lot", batch = "war_claims", timeoutTicks = 100)
    public static void claimsAreProtectedAndRaidsOpenOnlyTheWayIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel(), stranger = helper.makeMockServerPlayerInLevel();
        Colony home = found(helper, owner, 3, 3, 6);
        Colony rival = found(helper, stranger, 15, 15, 5);
        try {
            BlockPos plank = helper.absolutePos(new BlockPos(5, G, 5)), door = helper.absolutePos(new BlockPos(6, G, 5));
            helper.setBlock(new BlockPos(5, G, 5), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(6, G, 5), Blocks.OAK_DOOR);
            helper.assertTrue(ColonyProtection.canBreak(stranger, level, plank, level.getBlockState(plank)) == ColonyProtection.Verdict.DENIED,
                    "strangers can't dig here");
            helper.assertTrue(ColonyProtection.canBreak(owner, level, plank, level.getBlockState(plank)) == ColonyProtection.Verdict.ALLOWED,
                    "the town's people can");
            helper.assertTrue(ColonyProtection.canBreak(stranger, level, home.center, level.getBlockState(home.center)) == ColonyProtection.Verdict.DENIED,
                    "the town hall is the founder's");
            // a war raid: the attackers may break their way in, and nothing else
            home.politics.set(Politics.Stance.OPEN, 0);
            rival.politics.set(Politics.Stance.OPEN, 0);
            War war = Wars.declare(stranger, rival, home, 0).war();
            helper.assertTrue(war != null, "declared");
            move(stranger, helper, 4, 8);
            Wars.Result raid = Wars.startRaid(stranger, war, 2 * DAY);
            helper.assertTrue(raid.war() != null, "raid: " + raid.problem());
            helper.assertTrue(ColonyProtection.canBreak(stranger, level, door, level.getBlockState(door)) == ColonyProtection.Verdict.RAID_BREAKABLE,
                    "doors can be broken in a raid");
            helper.assertTrue(ColonyProtection.canBreak(stranger, level, plank, level.getBlockState(plank)) == ColonyProtection.Verdict.DENIED,
                    "walls can't");
        } finally {
            cleanUp(helper, List.of(home, rival), List.of(owner, stranger));
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "war_rules", timeoutTicks = 100)
    public static void warsFollowTheRulesAndEndInATruce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer a = helper.makeMockServerPlayerInLevel(), d = helper.makeMockServerPlayerInLevel();
        Colony attacker = found(helper, a, 3, 3, 6);
        Colony defender = found(helper, d, 15, 15, 5);
        try {
            helper.assertTrue(Wars.declare(a, attacker, defender, 0).problem().contains("peaceful"), "peaceful towns can't go to war");
            attacker.politics.set(Politics.Stance.OPEN, 0);
            helper.assertTrue(Wars.declare(a, attacker, defender, 0).problem().contains("can't be attacked"), "nor be attacked");
            defender.politics.set(Politics.Stance.OPEN, 0);
            War war = Wars.declare(a, attacker, defender, 0).war();
            helper.assertTrue(war != null && Wars.declare(a, attacker, defender, 0).war() == null, "one war at a time");
            helper.assertTrue(attacker.politics.cantChange(Politics.Stance.PEACEFUL, true, 10 * DAY) != null, "no going peaceful during a war");
            helper.assertTrue(Wars.cantRaid(server, war, 60_000).contains("warning"), "the warning period");
            // loot to carry off: 100 cobblestone in the defenders' town hall
            TownHallBlockEntity hall = (TownHallBlockEntity) level.getBlockEntity(defender.center);
            ColonyStorage.insert(hall, new ItemStack(Items.COBBLESTONE, 64));
            ColonyStorage.insert(hall, new ItemStack(Items.COBBLESTONE, 36));
            move(a, helper, 15, 13); // by their hall
            move(d, helper, 3, 9);   // away from it
            long now = 2 * DAY;
            helper.assertTrue(Wars.startRaid(a, war, now).war() != null, "raid started");
            helper.assertTrue(war.state == War.State.RAID && Wars.raidOf(defender.id).isPresent(), "a raid is on");
            Wars.tick(server, now + 1000);
            Wars.tick(server, now + 2000);
            helper.assertTrue(Wars.capture(war) == 2, "taking the banner: " + Wars.capture(war));
            // a defender at the hall stops it
            move(d, helper, 15, 16);
            Wars.tick(server, now + 3000);
            helper.assertTrue(Wars.capture(war) == 0, "contested: " + Wars.capture(war));
            move(d, helper, 3, 9);
            for (int s = 0; s < StadConfig.CAPTURE_SECONDS.get() && war.on(); s++) {
                Wars.tick(server, now + 4000 + s * 1000L);
            }
            helper.assertTrue(war.outcome == War.Outcome.ATTACKERS && !war.on(), "taken: " + war.outcome);
            int left = ColonyStorage.supply(level, defender).count(Items.COBBLESTONE);
            int took = ColonyStorage.supply(level, attacker).count(Items.COBBLESTONE);
            helper.assertTrue(left == 80 && took == 20, "a fifth carried off: left " + left + ", took " + took);
            helper.assertTrue(Wars.declare(a, attacker, defender, now + 60_000).problem().contains("truce"), "a truce follows");
            helper.assertTrue(Wars.declare(a, attacker, defender, now + 4 * DAY).war() != null, "until it runs out");
        } finally {
            cleanUp(helper, List.of(attacker, defender), List.of(a, d));
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "war_peace", timeoutTicks = 100)
    public static void envoysCanMakePeace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer a = helper.makeMockServerPlayerInLevel(), d = helper.makeMockServerPlayerInLevel();
        Colony attacker = found(helper, a, 3, 3, 6);
        Colony defender = found(helper, d, 15, 15, 5);
        try {
            attacker.politics.set(Politics.Stance.OPEN, 0);
            defender.politics.set(Politics.Stance.OPEN, 0);
            War war = Wars.declare(a, attacker, defender, 0).war();
            CitizenEntity envoy = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 4, G, 4);
            ColonyManager.get(level.getServer()).join(attacker, envoy);
            attacker.setStanding(a.getUUID(), 40);
            String sent = Envoys.send(a, envoy, war, 1000);
            helper.assertTrue(sent == null && Envoys.isEnvoy(envoy.getUUID()), "sent: " + sent);
            helper.assertTrue(envoy.blockPosition().closerThan(defender.center, 6), "at their hall: " + envoy.blockPosition());
            helper.assertTrue(war.peaceOffers.contains(attacker.id) && war.on(), "the senders offer peace");
            helper.assertTrue(!Combat.fighter(envoy), "envoys don't fight");
            String answered = Envoys.answer(d, Envoys.of(envoy.getUUID()).orElseThrow(), true, 2000);
            helper.assertTrue(answered == null && war.outcome == War.Outcome.PEACE, "peace: " + answered + " " + war.outcome);
            helper.assertTrue(Envoys.of(envoy.getUUID()).map(e -> e.until() <= level.getGameTime() + 200).orElse(false), "heads home shortly");
            Envoys.home(level.getServer(), envoy.getUUID());
            helper.assertTrue(!Envoys.isEnvoy(envoy.getUUID()) && envoy.blockPosition().closerThan(attacker.center, 6), "home again");
        } finally {
            cleanUp(helper, List.of(attacker, defender), List.of(a, d));
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "war_poach", timeoutTicks = 100)
    public static void unhappyCitizensCanBeLuredAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer a = helper.makeMockServerPlayerInLevel(), b = helper.makeMockServerPlayerInLevel();
        Colony theirs = found(helper, a, 3, 3, 6);
        Colony mine = found(helper, b, 15, 15, 5);
        try {
            CitizenEntity sad = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 4, G, 6);
            CitizenEntity glad = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 6);
            ColonyManager.get(level.getServer()).join(theirs, sad);
            ColonyManager.get(level.getServer()).join(theirs, glad);
            sad.setHunger(1);
            sad.setRest(1);
            mine.setStanding(b.getUUID(), 30);
            int before = theirs.standing(a.getUUID()).score();
            Order.Result happy = OrderService.issue(new Order(b, OrderKind.INVITE, glad));
            helper.assertTrue(happy.outcome() == Order.Outcome.REFUSED && happy.reason().contains("happy"), "the happy stay: " + happy);
            Order.Result lured = OrderService.issue(new Order(b, OrderKind.INVITE, sad));
            helper.assertTrue(lured.done() && mine.hasCitizen(sad.getUUID()) && !theirs.hasCitizen(sad.getUUID()), "lured: " + lured);
            helper.assertTrue(theirs.standing(a.getUUID()).score() < before, "their leader is blamed");
            theirs.politics.poaching = false;
            glad.setHunger(1);
            glad.setRest(1);
            Order.Result off = OrderService.issue(new Order(b, OrderKind.INVITE, glad));
            helper.assertTrue(off.outcome() == Order.Outcome.INVALID, "not when the town forbids it: " + off);
        } finally {
            cleanUp(helper, List.of(theirs, mine), List.of(a, b));
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "war_guard", timeoutTicks = 300)
    public static void guardsTakeSwordsAndFightMonsters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner, 3, 3, 12);
        CitizenEntity guard = helper.spawn(StadRegistries.CITIZEN.get(), 6, G, 6);
        ColonyManager.get(level.getServer()).join(colony, guard);
        colony.citizen(guard.getUUID()).orElseThrow().job = Jobs.GUARD;
        TownHallBlockEntity hall = (TownHallBlockEntity) level.getBlockEntity(colony.center);
        ColonyStorage.insert(hall, new ItemStack(Items.STONE_SWORD));
        ColonyStorage.insert(hall, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(Combat.unarmed(guard), "no sword yet");
        Combat.arm(guard, level, colony);
        helper.assertTrue(guard.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD) && ColonyStorage.supply(level, colony).count(Items.IRON_SWORD) == 0,
                "took the best sword");
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, 10, G, 10);
        zombie.setNoAi(true);
        helper.assertTrue(Combat.target(guard) == zombie, "a monster on the town's land");
        float full = zombie.getHealth();
        helper.succeedWhen(() -> {
            helper.assertTrue(zombie.getHealth() < full || !zombie.isAlive(), "the guard hasn't hit it yet");
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        });
    }

    @GameTest(template = "lot", batch = "war_mobs", timeoutTicks = 200)
    public static void mobRaidsWarnShelterAndGetRepaired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner, 2, 2, 12);
        move(owner, helper, 3, 8);
        Design d = Design.make(BuildSpec.parse(PlannerGameTests.SAMPLES.get(0)).withBase("new"));
        BlockPos origin = helper.absolutePos(new BlockPos(6, G - 1, 6));
        Building house = colony.addBuilding(ResourceLocation.fromNamespaceAndPath("stad", "design/raided"), "Raided House", origin, Rotation.NONE,
                level.getGameTime(), d);
        for (Placement.Step s : house.placement().orElseThrow().steps()) {
            level.setBlock(s.pos(), s.state(), 2 | 16);
        }
        house.status = Building.Status.DONE;
        CitizenEntity guard = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3, G, 3);
        CitizenEntity baker = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 4, G, 3);
        ColonyManager.get(level.getServer()).join(colony, guard);
        ColonyManager.get(level.getServer()).join(colony, baker);
        colony.citizen(guard.getUUID()).orElseThrow().job = Jobs.GUARD;
        colony.setStanding(owner.getUUID(), 0);
        try {
            Raids.MobRaid raid = Raids.start(level, colony, 0);
            helper.assertTrue(raid.phase == Raids.Phase.WARNING && Combat.underAttack(colony), "warned");
            helper.assertTrue(Combat.fighter(guard) && !Combat.fighter(baker), "the guard fights, the baker shelters");
            Raids.tick(level, colony);
            helper.assertTrue(raid.phase == Raids.Phase.FIGHT && raid.spawned == Raids.strength(colony) && raid.raiders.size() == raid.spawned,
                    "they came: " + raid.spawned);
            Mob raider = (Mob) level.getEntity(raid.raiders.iterator().next());
            helper.assertTrue(raider != null && Raids.isRaider(raider, colony.id), "tagged raiders");
            // they broke a wall
            BlockPos wall = house.placement().orElseThrow().steps().stream().filter(s -> s.state().is(Blocks.OAK_PLANKS)).findFirst().orElseThrow().pos();
            level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
            for (var id : List.copyOf(raid.raiders)) {
                if (level.getEntity(id) instanceof Mob m) {
                    m.kill();
                }
            }
            Raids.tick(level, colony);
            helper.assertTrue(Raids.of(colony).isEmpty() && !Combat.underAttack(colony), "over");
            Building repair = colony.buildings().stream().filter(Building::isRepair).findFirst().orElse(null);
            helper.assertTrue(repair != null && repair.design.blueprint().entries().size() == 1
                    && repair.placement().orElseThrow().steps().getFirst().pos().equals(wall), "a repair order for the wall: " + repair);
            helper.assertTrue(colony.standing(owner.getUUID()).score() > 0, "seeing the town through it: " + colony.standing(owner.getUUID()).score());
        } finally {
            Raids.stop(level, colony);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "war_factions", timeoutTicks = 200)
    public static void raidersComeDressedForTheirLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Colony colony = found(helper, owner, 2, 2, 12);
        for (int i = 0; i < 9; i++) {
            ColonyManager.get(level.getServer()).join(colony, helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 3 + i % 3, G, 3 + i / 3));
        }
        boolean factions = StadConfig.RAIDER_FACTIONS.get();
        try {
            StadConfig.RAIDER_FACTIONS.set(false);
            helper.assertTrue(dev.eiriksb.stad.war.Faction.of(level, colony, level.random) == dev.eiriksb.stad.war.Faction.PILLAGERS, "off: pillagers");
            StadConfig.RAIDER_FACTIONS.set(true);
            Raids.MobRaid raid = Raids.start(level, colony, 0);
            raid.faction = dev.eiriksb.stad.war.Faction.NORSE;
            raid.leaderName = "Test Ironside";
            Raids.tick(level, colony);
            helper.assertTrue(raid.phase == Raids.Phase.FIGHT && raid.spawned >= 3, "they came: " + raid.spawned);
            List<dev.eiriksb.stad.war.RaiderEntity> raiders = new java.util.ArrayList<>();
            raid.raiders.forEach(id -> {
                if (level.getEntity(id) instanceof dev.eiriksb.stad.war.RaiderEntity r) {
                    raiders.add(r);
                }
            });
            helper.assertTrue(raiders.size() == raid.spawned, "all Norse raiders: " + raiders.size());
            dev.eiriksb.stad.war.RaiderEntity leader = raiders.stream().filter(dev.eiriksb.stad.war.RaiderEntity::leader).findFirst().orElseThrow();
            helper.assertTrue("Test Ironside".equals(leader.getCustomName().getString()) && leader.isCustomNameVisible() && leader.getMaxHealth() == 36
                    && leader.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.IRON_CHESTPLATE),
                    "the leader: " + leader.getCustomName() + ", " + leader.getMaxHealth());
            for (dev.eiriksb.stad.war.RaiderEntity r : raiders) {
                helper.assertTrue(r.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.IRON_HELMET)
                        && Raids.isRaider(r, colony.id) && r.faction() == dev.eiriksb.stad.war.Faction.NORSE, "dressed as Norse raiders");
                boolean bow = r.getMainHandItem().is(net.minecraft.world.item.Items.BOW);
                helper.assertTrue(bow || r.getMainHandItem().is(net.minecraft.world.item.Items.IRON_AXE), "axes and bows: " + r.getMainHandItem());
                helper.assertTrue(r.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof net.minecraft.world.entity.ai.goal.RangedBowAttackGoal) == bow,
                        "bowmen shoot, the rest close in");
            }
            // saved and loaded, still themselves
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            leader.saveWithoutId(tag);
            dev.eiriksb.stad.war.RaiderEntity copy = StadRegistries.RAIDER.get().create(level);
            copy.load(tag);
            helper.assertTrue(copy.faction() == dev.eiriksb.stad.war.Faction.NORSE && copy.leader() && copy.skin() == leader.skin(), "saved");
        } finally {
            StadConfig.RAIDER_FACTIONS.set(factions);
            Raids.stop(level, colony);
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }
}
