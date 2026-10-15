package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Smithing;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Request;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The gatherer (PLAN.md §6.12) brings in the basics from the land round the town, outside its buildings and yards:
 * they dig dirt, sand, gravel and clay, and forage berries, sugar cane, seeds, flowers, pumpkins, melons and mushrooms;
 * and they pick up what's been lying about on the town's land a while (saplings from the leaves, what fell off a cart).
 * They gather what the town has asked for first, then keep a small stock of the basics ({@link #KEEP}).
 * <p>
 * They dig only the top of natural ground, and never where it's lower than round about, so the land is taken down
 * evenly a layer at a time instead of pitted with holes; and not beside water (it would run in), except for clay,
 * which is mostly found under shallow water.
 */
public class GatherGoal extends WorkGoal {
    /** how far from the town hall they go */
    static final int RANGE = 48;
    /** how long (ticks) something has to lie on the ground before it's theirs to pick up */
    static final int LYING = 600;

    enum Kind { DIG, PICK, BERRIES, CANE, ITEM }

    /** Something on the land to gather: what it is, and what it gives. */
    record Find(Kind kind, Predicate<BlockState> block, Predicate<Item> gives) {}

    static final List<Find> FINDS = List.of(
            new Find(Kind.DIG, s -> s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT), i -> i == Items.DIRT
                    || i == Items.COARSE_DIRT),
            new Find(Kind.DIG, s -> s.is(Blocks.SAND), i -> i == Items.SAND),
            new Find(Kind.DIG, s -> s.is(Blocks.RED_SAND), i -> i == Items.RED_SAND),
            new Find(Kind.DIG, s -> s.is(Blocks.GRAVEL), i -> i == Items.GRAVEL || i == Items.FLINT),
            new Find(Kind.DIG, s -> s.is(Blocks.CLAY), i -> i == Items.CLAY_BALL),
            new Find(Kind.BERRIES, s -> s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) >= 2, i -> i == Items.SWEET_BERRIES),
            new Find(Kind.CANE, s -> s.is(Blocks.SUGAR_CANE), i -> i == Items.SUGAR_CANE),
            new Find(Kind.CANE, s -> s.is(Blocks.CACTUS), i -> i == Items.CACTUS),
            new Find(Kind.PICK, s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN), i -> i == Items.WHEAT_SEEDS),
            new Find(Kind.PICK, s -> s.is(BlockTags.SMALL_FLOWERS) && !s.is(Blocks.WITHER_ROSE), i -> i.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.SMALL_FLOWERS)),
            new Find(Kind.PICK, s -> s.is(Blocks.PUMPKIN), i -> i == Items.PUMPKIN),
            new Find(Kind.PICK, s -> s.is(Blocks.MELON), i -> i == Items.MELON_SLICE),
            new Find(Kind.PICK, s -> s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM), i -> i == Items.BROWN_MUSHROOM || i == Items.RED_MUSHROOM),
            new Find(Kind.PICK, s -> s.is(Blocks.DEAD_BUSH), i -> i == Items.STICK));

    /** What the gatherer keeps in the stores, when nobody's asked for anything. */
    public record Stock(Item item, int count) {}

    public static final List<Stock> KEEP = List.of(new Stock(Items.DIRT, 64), new Stock(Items.SAND, 32), new Stock(Items.GRAVEL, 32),
            new Stock(Items.CLAY_BALL, 16), new Stock(Items.SWEET_BERRIES, 16), new Stock(Items.SUGAR_CANE, 16), new Stock(Items.WHEAT_SEEDS, 16));

    /** Columns out from a spot, nearest first. */
    private static final List<int[]> AROUND = new ArrayList<>();

    static {
        for (int x = -RANGE; x <= RANGE; x++) {
            for (int z = -RANGE; z <= RANGE; z++) {
                AROUND.add(new int[]{x, z});
            }
        }
        AROUND.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }

    record Task(Kind kind, BlockPos pos, ItemEntity item) {}

    private Task task;
    private int planIn;
    private Set<Item> lastWanted = Set.of();

    public GatherGoal(CitizenEntity citizen) {
        super(citizen, Jobs.GATHERER);
    }

    @Override
    protected ItemStack tool() {
        return new ItemStack(Items.STONE_SHOVEL);
    }

    /** Whether the town ever wants this from a gatherer. */
    public static boolean gathers(Item item) {
        return FINDS.stream().anyMatch(f -> f.gives().test(item));
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (task != null && still(level, task)) {
            return true;
        }
        task = null;
        if (--planIn > 0) {
            return false;
        }
        planIn = 40;
        task = plan(level, colony);
        cooldown = 0;
        return task != null;
    }

    private static boolean still(ServerLevel level, Task t) {
        if (t.kind() == Kind.ITEM) {
            return t.item().isAlive();
        }
        BlockState s = level.getBlockState(t.pos());
        return FINDS.stream().anyMatch(f -> f.kind() == t.kind() && f.block().test(s));
    }

    /** What the town is short of that they could gather: what's been asked for, else the stock. */
    Set<Item> wanted(ServerLevel level, Colony colony) {
        Map<Item, Integer> stock = new HashMap<>(Smithing.stock(Tools.stores(level, colony, citizen, workplace(colony))));
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            ItemStack s = citizen.inventory().getItem(i);
            stock.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        Set<Item> out = new HashSet<>();
        for (Request r : colony.requests()) {
            ResourceLocation id = Request.FOOD.equals(r.what) ? null : ResourceLocation.tryParse(r.what);
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (item != Items.AIR && FINDS.stream().anyMatch(f -> f.gives().test(item)) && stock.getOrDefault(item, 0) < r.count) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            for (Stock k : KEEP) {
                if (stock.getOrDefault(k.item(), 0) < k.count()) {
                    out.add(k.item());
                }
            }
        }
        return out;
    }

    Task plan(ServerLevel level, Colony colony) {
        BlockPos at = citizen.blockPosition();
        // what's lying about first: it'd be lost when it despawns
        ItemEntity lying = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(RANGE, 16, RANGE),
                        e -> e.getAge() > LYING && !e.hasPickUpDelay() && colony.contains(level.dimension(), e.blockPosition()))
                .stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(citizen))).orElse(null);
        if (lying != null) {
            return new Task(Kind.ITEM, lying.blockPosition(), lying);
        }
        Set<Item> want = wanted(level, colony);
        lastWanted = want;
        if (want.isEmpty()) {
            return null;
        }
        List<Find> finds = FINDS.stream().filter(f -> want.stream().anyMatch(f.gives())).toList();
        Set<Long> out = keepOut(colony);
        for (int[] o : AROUND) {
            int x = at.getX() + o[0], z = at.getZ() + o[1];
            if (Math.abs(x - colony.center.getX()) > RANGE || Math.abs(z - colony.center.getZ()) > RANGE || out.contains(col(x, z))) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            while (top > level.getMinBuildHeight() && (level.getBlockState(new BlockPos(x, top, z)).isAir()
                    || level.getBlockState(new BlockPos(x, top, z)).is(Blocks.BARRIER))) {
                top--; // under a roof of barriers (a test plot's) is the ground
            }
            BlockPos p = new BlockPos(x, top, z);
            if (!colony.contains(level.dimension(), p)) {
                continue;
            }
            BlockPos found = find(level, finds, p);
            if (found != null) {
                Kind kind = finds.stream().filter(f -> f.block().test(level.getBlockState(found))).findFirst().orElseThrow().kind();
                return new Task(kind, found, null);
            }
        }
        return null;
    }

    /** What of {@code finds} is at the top of a column ({@code top}: its highest block), if any. */
    private static BlockPos find(ServerLevel level, List<Find> finds, BlockPos top) {
        BlockState s = level.getBlockState(top);
        BlockPos ground = top;
        if (!s.getFluidState().isEmpty()) { // under water: only clay, and only in the shallows
            int depth = 0;
            while (!level.getFluidState(ground).isEmpty() && depth < 3) {
                ground = ground.below();
                depth++;
            }
            BlockState bed = level.getBlockState(ground);
            return depth <= 2 && bed.is(Blocks.CLAY) && finds.stream().anyMatch(f -> f.block().test(bed)) ? ground : null;
        }
        for (Find f : finds) {
            if (f.kind() == Kind.CANE) {
                // the top of a cane or cactus at least two high: they're cut down to the bottom one, which grows again
                if (f.block().test(s) && f.block().test(level.getBlockState(top.below()))) {
                    return top;
                }
            } else if (f.kind() != Kind.DIG && f.block().test(s)) {
                return top;
            }
        }
        // the ground: under a plant, the block below it
        if (s.canBeReplaced() && !s.isAir()) {
            ground = top.below();
        }
        BlockState g = level.getBlockState(ground);
        for (Find f : finds) {
            if (f.kind() == Kind.DIG && f.block().test(g) && diggable(level, ground)) {
                return ground;
            }
        }
        return null;
    }

    /**
     * Natural ground that can be taken down without leaving a hole: nothing beside it is higher (so a column dug once
     * waits till the ground round it has been taken down too), and there's no water beside it.
     */
    static boolean diggable(ServerLevel level, BlockPos ground) {
        if (level.getBlockState(ground).is(Blocks.CLAY)) {
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = ground.relative(d);
            if (!level.getFluidState(n).isEmpty() || !level.getFluidState(n.above()).isEmpty()) {
                return false;
            }
            if (!level.getBlockState(n.above()).canBeReplaced()) {
                return false; // the ground beside it is higher: it's been dug here already (or it's a wall)
            }
        }
        return !level.getBlockState(ground.above(2)).is(BlockTags.LOGS); // not out from under a tree
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** The town's buildings and yards (and a little round them), and round the town hall: not theirs to dig. */
    private static Set<Long> keepOut(Colony colony) {
        Set<Long> out = new HashSet<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                out.add(col(colony.center.getX() + x, colony.center.getZ() + z));
            }
        }
        for (Building b : colony.buildings()) {
            b.placement().ifPresent(p -> {
                if (b.ring()) {
                    p.steps().forEach(st -> {
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                out.add(col(st.pos().getX() + x, st.pos().getZ() + z));
                            }
                        }
                    });
                    return;
                }
                BoundingBox bb = p.bounds();
                for (int x = bb.minX() - 2; x <= bb.maxX() + 2; x++) {
                    for (int z = bb.minZ() - 2; z <= bb.maxZ() + 2; z++) {
                        out.add(col(x, z));
                    }
                }
            });
        }
        return out;
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (lastWanted.isEmpty()) {
            return null;
        }
        return "Nothing to gather: no " + String.join(", ", lastWanted.stream().map(i -> i.getDescription().getString().toLowerCase(java.util.Locale.ROOT))
                .sorted().toList()) + " on the town's land outside its buildings";
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Nothing to gather: the town has the dirt, sand, gravel, clay, berries, sugar cane and seeds it needs";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "Looking round for what the town needs";
        }
        return switch (task.kind()) {
            case DIG -> "Digging " + citizen.level().getBlockState(task.pos()).getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
            case BERRIES -> "Picking berries";
            case CANE -> "Cutting " + citizen.level().getBlockState(task.pos()).getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
            case ITEM -> "Picking up what's lying about";
            case PICK -> "Foraging";
        };
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        if (task.kind() == Kind.ITEM) {
            ItemEntity e = task.item();
            if (!approach.tick(citizen, e.blockPosition(), 2.0, 0.6)) {
                return;
            }
            pocket(e.getItem().copy());
            e.discard();
            citizen.swing(InteractionHand.MAIN_HAND);
            task = null;
            planIn = 0;
            approach.reset();
            return;
        }
        BlockPos p = task.pos();
        if (!approach.tick(citizen, p, 3.5, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        if (cooldown == 0) {
            cooldown = pace(task.kind() == Kind.DIG ? 20 : 12) + 1;
        }
        if (--cooldown > 0) {
            if (cooldown % 6 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        gather(level, p);
        task = null;
        planIn = 0;
        approach.reset();
    }

    private void gather(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        switch (task.kind()) {
            case BERRIES -> {
                int n = 1 + level.random.nextInt(2) + (s.getValue(SweetBerryBushBlock.AGE) == 3 ? 1 : 0);
                pocket(new ItemStack(Items.SWEET_BERRIES, n));
                level.setBlock(p, s.setValue(SweetBerryBushBlock.AGE, 1), 2);
                level.playSound(null, p, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1f, 1f);
            }
            case CANE -> {
                int n = 0;
                BlockPos at = p;
                while (level.getBlockState(at.above()).is(s.getBlock())) {
                    at = at.above();
                }
                for (; level.getBlockState(at.below()).is(s.getBlock()); at = at.below()) { // from the top down, leaving the bottom one
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
                    n++;
                }
                pocket(new ItemStack(s.getBlock().asItem(), n));
                level.playSound(null, p, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1f, 1f);
            }
            default -> {
                for (ItemStack drop : Block.getDrops(s, level, p, level.getBlockEntity(p), citizen, working())) {
                    pocket(drop);
                }
                level.levelEvent(2001, p, Block.getId(s));
                level.removeBlock(p, false);
            }
        }
        worked(1);
    }
}
