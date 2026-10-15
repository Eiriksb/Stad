package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Fields;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.WorkSites;
import dev.eiriksb.stad.colony.Workplaces;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The farmer: harvests ripe crops and replants them, sows empty farmland with seeds (their own, or fetched from the
 * town's stores), and makes the town's fields: levels them, tills them and digs the water hole in the middle. A farmer
 * with no field lays one out near their farmhouse (or the town hall). With no seeds anywhere they pick them from the
 * grass.
 */
public class FarmGoal extends WorkGoal {
    private enum Kind { HARVEST, SOW, FETCH, CLEAR, TILL, WATER, GRASS }

    private record Task(Kind kind, BlockPos pos) {}

    /** how many seeds they carry */
    private static final int SEEDS = 16;
    /** ticks between looking for somewhere to lay out a field */
    private static final int LAYOUT_EVERY = 1200;

    private Task task;
    private long nextLayout;
    private long nextPlan;
    /** why there's no work, worked out when they last looked */
    private String why;

    public FarmGoal(CitizenEntity citizen) {
        super(citizen, Jobs.FARMER);
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return isSeed(stack) && stack.getCount() <= SEEDS;
    }

    private static boolean isSeed(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (task != null && valid(level, colony, task)) {
            return true;
        }
        if (level.getGameTime() < nextPlan) {
            task = null;
            return false;
        }
        task = plan(level, colony);
        if (task == null) {
            nextPlan = level.getGameTime() + 40; // nothing to do: look again in a couple of seconds, not every tick
        }
        return task != null;
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        return why;
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Waiting for the crops to grow";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "Farming";
        }
        return switch (task.kind) {
            case HARVEST -> "Harvesting";
            case SOW -> "Sowing the field";
            case FETCH -> "Fetching seeds";
            case CLEAR -> "Levelling the field";
            case TILL -> "Tilling the field";
            case WATER -> "Digging the field's water hole";
            case GRASS -> "Looking for seeds in the grass";
        };
    }

    /** The fields they look after: the ones near their workplace, or all of them. */
    private List<Building> fields(Colony colony) {
        List<Building> all = Fields.of(colony);
        Optional<Building> at = workplace(colony);
        if (at.isEmpty()) {
            return all;
        }
        BlockPos spot = Workplaces.spot(at.get());
        List<Building> near = all.stream().filter(f -> Fields.centre(f).closerThan(spot, 32)).toList();
        return near.isEmpty() ? all : near;
    }

    /** The farmland they work: what the scan found near them, and their fields' (which may be newly tilled). */
    private List<BlockPos> farmland(ServerLevel level, Colony colony, List<Building> fields) {
        java.util.Set<BlockPos> all = new java.util.LinkedHashSet<>(nearWork(colony, WorkSites.farmland(level, colony), 24));
        for (Building f : fields) {
            for (BlockPos p : Fields.plots(f)) {
                if (level.getBlockState(p).is(Blocks.FARMLAND)) {
                    all.add(p);
                }
            }
        }
        return new ArrayList<>(all);
    }

    private Task plan(ServerLevel level, Colony colony) {
        why = null;
        BlockPos me = citizen.blockPosition();
        Comparator<BlockPos> nearest = Comparator.comparingDouble(p -> p.distSqr(me));
        List<Building> fields = fields(colony);
        List<BlockPos> farmland = farmland(level, colony, fields);
        Optional<BlockPos> ripe = farmland.stream().filter(f -> ripe(level, f)).min(nearest);
        if (ripe.isPresent()) {
            return new Task(Kind.HARVEST, ripe.get());
        }
        List<BlockPos> bare = farmland.stream().filter(f -> level.getBlockState(f.above()).isAir()).toList();
        if (!bare.isEmpty()) {
            // a plot designed for a crop gets it, when the town has it: fetch it rather than sow something else
            BlockPos next = bare.stream().min(nearest).get();
            String want = wants(colony, next);
            fetching = null;
            if (want != null && !pocketed(seedFor(want))) {
                ColonyStorage.Box box = seedStore(level, colony, seedFor(want));
                if (box != null) {
                    fetching = seedFor(want);
                    return new Task(Kind.FETCH, box.pos());
                }
            }
            if (hasSeeds()) {
                return new Task(Kind.SOW, bare.stream().min(nearest).get());
            }
            ColonyStorage.Box box = seedStore(level, colony);
            if (box != null) {
                return new Task(Kind.FETCH, box.pos());
            }
        }
        Task field = fieldWork(level, fields, nearest);
        if (field != null) {
            return field;
        }
        if (fields.isEmpty() && farmland.isEmpty() || Fields.of(colony).size() < farmers(colony)) {
            if (level.getGameTime() >= nextLayout) {
                nextLayout = level.getGameTime() + LAYOUT_EVERY;
                BlockPos near = workplace(colony).map(Workplaces::spot).orElse(colony.center);
                Optional<BlockPos> spot = Fields.findSpot(level, colony, near);
                if (spot.isPresent()) {
                    Building f = Fields.layOut(level, colony, spot.get());
                    Announce.members(level.getServer(), colony, Component.literal(citizen.getName().getString()
                            + " (farmer) has marked out a field " + where(colony, Fields.centre(f)) + ".").withStyle(ChatFormatting.GREEN));
                    return fieldWork(level, List.of(f), nearest);
                }
            }
            if (fields.isEmpty() && farmland.isEmpty()) {
                why = "Nowhere for a field: they need a flat 9×9 patch of grass or dirt on the town's land. Plan one with the building plans (Field)";
                return null;
            }
        }
        if (!bare.isEmpty()) {
            Optional<BlockPos> grass = grass(level, fields.isEmpty() ? bare.getFirst() : Fields.centre(fields.getFirst()));
            if (grass.isPresent()) {
                return new Task(Kind.GRASS, grass.get());
            }
            why = "No seeds to sow: put wheat seeds (or potatoes, carrots, beetroot seeds) in the town hall";
        }
        return null;
    }

    /** How many farmers the town has. */
    private static long farmers(Colony colony) {
        return colony.citizens().stream().filter(r -> Jobs.FARMER.equals(r.job)).count();
    }

    /** "by the farmhouse", "north of the town hall" */
    private String where(Colony colony, BlockPos p) {
        Optional<Building> at = workplace(colony);
        if (at.isPresent()) {
            return "by the " + at.get().name.toLowerCase(java.util.Locale.ROOT);
        }
        int dx = p.getX() - colony.center.getX(), dz = p.getZ() - colony.center.getZ();
        String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        return dir + " of the town hall";
    }

    /** The next thing a field needs: the water hole first, then clearing and tilling each plot. */
    private Task fieldWork(ServerLevel level, List<Building> fields, Comparator<BlockPos> nearest) {
        for (Building f : fields.stream().sorted(Comparator.comparingDouble(f -> Fields.centre(f).distSqr(citizen.blockPosition()))).toList()) {
            Optional<BlockPos> water = Fields.waters(f).stream().filter(w -> !level.getFluidState(w).isSource()).min(nearest);
            if (water.isPresent()) {
                return new Task(Kind.WATER, water.get());
            }
            Optional<BlockPos> clear = Fields.plots(f).stream().filter(p -> inTheWay(level, p.above())).min(nearest);
            if (clear.isPresent()) {
                return new Task(Kind.CLEAR, clear.get().above());
            }
            Optional<BlockPos> till = Fields.plots(f).stream().filter(p -> needsTilling(level, p)).min(nearest);
            if (till.isPresent()) {
                return new Task(Kind.TILL, till.get());
            }
        }
        return null;
    }

    /** Something solid above a plot (a bump in the ground, a stone): it's dug away. Grass and flowers go when it's tilled. */
    private static boolean inTheWay(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && !(s.getBlock() instanceof CropBlock) && s.getDestroySpeed(level, p) >= 0
                && level.getBlockEntity(p) == null;
    }

    private static boolean needsTilling(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.FARMLAND)) {
            return false;
        }
        if (s.canBeReplaced()) {
            return true; // a dip: filled with dirt and tilled
        }
        return Fields.tillable(s) || s.getDestroySpeed(level, p) >= 0 && level.getBlockEntity(p) == null && s.getDestroySpeed(level, p) < 3;
    }

    private boolean valid(ServerLevel level, Colony colony, Task t) {
        return switch (t.kind) {
            case HARVEST -> ripe(level, t.pos);
            case SOW -> level.getBlockState(t.pos).is(Blocks.FARMLAND) && level.getBlockState(t.pos.above()).isAir() && hasSeeds();
            case FETCH -> fetching != null ? !pocketed(fetching) : !hasSeeds();
            case CLEAR -> inTheWay(level, t.pos);
            case TILL -> needsTilling(level, t.pos);
            case WATER -> !level.getFluidState(t.pos).isSource();
            case GRASS -> !hasSeeds() && isGrass(level.getBlockState(t.pos));
        };
    }

    private static boolean ripe(ServerLevel level, BlockPos farmland) {
        BlockState above = level.getBlockState(farmland.above());
        return above.getBlock() instanceof CropBlock crop && crop.isMaxAge(above);
    }

    private boolean hasSeeds() {
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            if (isSeed(citizen.inventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Where in the town's stores there are seeds (wheat seeds first, then potatoes, carrots, beetroot). */
    /** the seed they're fetching for plots that ask for it, or null */
    private net.minecraft.world.item.Item fetching;

    private boolean pocketed(net.minecraft.world.item.Item item) {
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            if (citizen.inventory().getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    private ColonyStorage.Box seedStore(ServerLevel level, Colony colony, net.minecraft.world.item.Item seed) {
        for (ColonyStorage s : Tools.stores(level, colony, citizen, workplace(colony))) {
            ColonyStorage.Box b = s.find(st -> st.is(seed));
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    private ColonyStorage.Box seedStore(ServerLevel level, Colony colony) {
        for (ColonyStorage s : Tools.stores(level, colony, citizen, workplace(colony))) {
            ColonyStorage.Box b = s.find(st -> st.is(Items.WHEAT_SEEDS));
            if (b != null) {
                return b;
            }
        }
        for (ColonyStorage s : Tools.stores(level, colony, citizen, workplace(colony))) {
            ColonyStorage.Box b = s.find(FarmGoal::isSeed);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    private static boolean isGrass(BlockState s) {
        return s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN);
    }

    /** Grass near a spot, to pick seeds from. */
    private static Optional<BlockPos> grass(ServerLevel level, BlockPos near) {
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-20, -3, -20), near.offset(20, 4, 20))) {
            if (isGrass(level.getBlockState(p)) && (best == null || p.distSqr(near) < best.distSqr(near))) {
                best = p.immutable();
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        BlockPos at = task.kind == Kind.SOW || task.kind == Kind.HARVEST ? task.pos.above() : task.pos;
        if (!approach.tick(citizen, at, 3.5, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = pace(12);
        switch (task.kind) {
            case HARVEST -> harvest(level, at);
            case SOW -> sow(level, colony, at);
            case FETCH -> fetch(level, colony);
            case CLEAR -> dig(level, at);
            case TILL -> till(level, task.pos);
            case WATER -> {
                dig(level, at.above());
                if (!level.getBlockState(at).isAir()) {
                    pocketDrops(level, at);
                }
                level.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
                level.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8f, 1.0f);
            }
            case GRASS -> {
                pocketDrops(level, at);
                level.removeBlock(at, false);
                level.playSound(null, at, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.8f, 1.0f);
            }
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        task = null;
        approach.reset();
    }

    private void harvest(ServerLevel level, BlockPos crop) {
        BlockState state = level.getBlockState(crop);
        if (!(state.getBlock() instanceof CropBlock c) || !c.isMaxAge(state)) {
            return;
        }
        for (ItemStack drop : Block.getDrops(state, level, crop, null)) {
            pocket(drop);
        }
        ItemStack seed = new ItemStack(state.getBlock().asItem());
        if (usePocketed(s -> s.is(seed.getItem()))) {
            level.setBlock(crop, c.getStateForAge(0), 3);
        } else {
            level.removeBlock(crop, false);
        }
        level.playSound(null, crop, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.8f, 1.0f);
        worked(1);
    }

    /** The seed for a crop a plot asks for ("carrots": a carrot). */
    private static net.minecraft.world.item.Item seedFor(String crop) {
        return switch (crop) {
            case "carrots" -> Items.CARROT;
            case "potatoes" -> Items.POTATO;
            case "beetroots" -> Items.BEETROOT_SEEDS;
            default -> Items.WHEAT_SEEDS;
        };
    }

    /** What the plot at {@code farmland} asks for, if a field designed at the table says. */
    private String wants(Colony colony, BlockPos farmland) {
        for (Building f : Fields.of(colony)) {
            String c = Fields.crops(f).get(farmland);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private void sow(ServerLevel level, Colony colony, BlockPos crop) {
        if (!level.getBlockState(crop).isAir() || !level.getBlockState(crop.below()).is(Blocks.FARMLAND)) {
            return;
        }
        String want = wants(colony, crop.below());
        int pick = -1;
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            ItemStack s = citizen.inventory().getItem(i);
            if (isSeed(s) && (pick < 0 || want != null && s.is(seedFor(want)))) {
                pick = i;
            }
        }
        if (pick >= 0) {
            ItemStack s = citizen.inventory().getItem(pick);
            level.setBlock(crop, ((BlockItem) s.getItem()).getBlock().defaultBlockState(), 3);
            s.shrink(1);
            level.playSound(null, crop, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1.0f);
        }
    }

    private void fetch(ServerLevel level, Colony colony) {
        int want = SEEDS;
        java.util.Set<net.minecraft.world.item.Item> asked = new java.util.LinkedHashSet<>();
        if (fetching != null) {
            asked.add(fetching);
        }
        fields(colony).forEach(f -> Fields.crops(f).values().forEach(c -> asked.add(seedFor(c))));
        asked.add(Items.WHEAT_SEEDS);
        for (net.minecraft.world.item.Item seed : asked) {
            for (ColonyStorage s : Tools.stores(level, colony, citizen, workplace(colony))) {
                for (ItemStack got : s.takeUpTo(st -> st.is(seed), want)) {
                    want -= got.getCount();
                    pocket(got);
                }
            }
        }
        fetching = null;
        for (ColonyStorage s : Tools.stores(level, colony, citizen, workplace(colony))) {
            if (want <= 0) {
                break;
            }
            for (ItemStack got : s.takeUpTo(FarmGoal::isSeed, want)) {
                want -= got.getCount();
                pocket(got);
            }
        }
    }

    /** Digs a block out of the way (what it drops goes in their pockets). */
    private void dig(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.getDestroySpeed(level, p) < 0) {
            return;
        }
        pocketDrops(level, p);
        level.removeBlock(p, false);
        level.playSound(null, p, s.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
    }

    /** What a block they clear drops: seeds and anything to eat they keep; the dirt, sand and flowers stay in the field. */
    private void pocketDrops(ServerLevel level, BlockPos p) {
        for (ItemStack drop : Block.getDrops(level.getBlockState(p), level, p, level.getBlockEntity(p))) {
            if (isSeed(drop) || ColonyStorage.isFood(drop)) {
                pocket(drop);
            }
        }
    }

    /** Makes a plot farmland: grass or flowers on it go (sometimes leaving seeds), a dip is filled in, other ground dug out. */
    private void till(ServerLevel level, BlockPos p) {
        BlockState above = level.getBlockState(p.above());
        if (!above.isAir() && above.canBeReplaced()) {
            pocketDrops(level, p.above());
            level.removeBlock(p.above(), false);
        }
        BlockState s = level.getBlockState(p);
        if (!s.canBeReplaced() && !s.is(Blocks.DIRT) && !s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.DIRT_PATH)) {
            pocketDrops(level, p); // sand, gravel, a stone in the way
        }
        level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 3);
        level.playSound(null, p, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8f, 1.0f);
        worked(1);
    }
}
