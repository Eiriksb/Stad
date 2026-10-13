package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.WorkSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/** The lumberjack fells the natural trees on the town's land, top down, and plants a sapling where each one stood. */
public class LumberjackGoal extends WorkGoal {
    /** Which tree each lumberjack is on (and since when), so they spread out instead of all crowding the nearest one. */
    private static final java.util.Map<java.util.UUID, Claim> CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();
    /** A claim not kept up for this long (a lumberjack who died or quit) is free again. */
    private static final long CLAIM_TICKS = 20 * 60;

    private record Claim(BlockPos tree, long at) {}

    private BlockPos tree;
    private List<BlockPos> logs = List.of();

    public LumberjackGoal(CitizenEntity citizen) {
        super(citizen, Jobs.LUMBERJACK);
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return stack.is(ItemTags.SAPLINGS) && stack.getCount() <= 4;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (tree != null && (level.getBlockState(tree).is(BlockTags.LOGS) || canReplant(level))) {
            CLAIMS.put(citizen.getUUID(), new Claim(tree, now));
            return true;
        }
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        CLAIMS.forEach((who, c) -> {
            if (!who.equals(citizen.getUUID()) && now - c.at() < CLAIM_TICKS) {
                taken.add(c.tree());
            }
        });
        tree = nearWork(colony, WorkSites.trees(level, colony).stream().filter(t -> !taken.contains(t) && level.getBlockState(t).is(BlockTags.LOGS))
                .toList(), 32).stream()
                .min(Comparator.comparingDouble(t -> t.distSqr(citizen.blockPosition()))).orElse(null);
        logs = List.of();
        if (tree == null) {
            CLAIMS.remove(citizen.getUUID());
        } else {
            CLAIMS.put(citizen.getUUID(), new Claim(tree, now));
        }
        return tree != null;
    }

    private boolean canReplant(ServerLevel level) {
        return level.getBlockState(tree).isAir() && level.getBlockState(tree.below()).is(BlockTags.DIRT) && hasSapling();
    }

    private boolean hasSapling() {
        for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
            if (citizen.inventory().getItem(i).is(ItemTags.SAPLINGS)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (tree == null) {
            return;
        }
        if (logs.isEmpty() || !level.getBlockState(logs.getLast()).is(BlockTags.LOGS)) {
            logs = WorkSites.treeLogs(level, tree, 96).stream().sorted(Comparator.comparingInt(BlockPos::getY)).toList();
        }
        if (logs.isEmpty()) {
            replant(level, colony);
            return;
        }
        BlockPos log = logs.getLast(); // top first, so nothing is left hanging
        if (!approach.tick(citizen, log, 5.0, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = pace(15);
        BlockState state = level.getBlockState(log);
        for (ItemStack drop : Block.getDrops(state, level, log, null)) {
            pocket(drop);
        }
        level.removeBlock(log, false);
        level.playSound(null, log, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0f, 0.9f);
        citizen.swing(InteractionHand.MAIN_HAND);
        worked(1);
        logs = logs.subList(0, logs.size() - 1);
    }

    /** Picks up saplings nearby (the leaves drop them as they fall) and plants one where the tree stood. */
    private void replant(ServerLevel level, Colony colony) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(tree).inflate(8), e -> e.getItem().is(ItemTags.SAPLINGS))) {
            pocket(item.getItem().copy());
            item.discard();
        }
        if (!hasSapling()) {
            // one from the town's storage, if there's any
            for (ItemStack s : ColonyStorage.deliveries(level, colony).takeUpTo(st -> st.is(ItemTags.SAPLINGS), 1)) {
                pocket(s);
            }
        }
        if (canReplant(level) && approach.tick(citizen, tree, 3.5, 0.6)) {
            for (int i = 0; i < citizen.inventory().getContainerSize(); i++) {
                ItemStack s = citizen.inventory().getItem(i);
                if (s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem bi) {
                    level.setBlock(tree, bi.getBlock().defaultBlockState(), 3);
                    s.shrink(1);
                    level.playSound(null, tree, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
                    break;
                }
            }
        }
        if (!canReplant(level)) {
            WorkSites.invalidate(colony);
            CLAIMS.remove(citizen.getUUID());
            tree = null;
            approach.reset();
        }
    }
}
