package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.WorkSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.Comparator;
import java.util.List;

/**
 * The fisher stands on the shore of a pond or river on the town's land and fishes, from the game's own fishing loot
 * (mostly fish, now and then junk; treasure needs a real bobber in open water).
 */
public class FishGoal extends WorkGoal {
    private BlockPos spot;
    private BlockPos water;
    private int bite;

    public FishGoal(CitizenEntity citizen) {
        super(citizen, Jobs.FISHER);
    }

    @Override
    protected ItemStack tool() {
        return new ItemStack(Items.FISHING_ROD);
    }

    @Override
    protected int load() {
        return 12;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (spot != null && WorkSites.waterBeside(level, spot).isPresent()) {
            return true;
        }
        BlockPos from = from(colony);
        List<BlockPos> shore = WorkSites.shore(level, colony).stream()
                .sorted(Comparator.comparingDouble(p -> p.distSqr(from))).limit(4).toList();
        // one of the nearest few, so fishers don't all crowd the same spot
        spot = shore.isEmpty() ? null : shore.get(citizen.getRandom().nextInt(shore.size()));
        bite = 0;
        return spot != null;
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (spot == null) {
            return;
        }
        if (!approach.tick(citizen, spot, 1.8, 0.6)) {
            water = null;
            return;
        }
        if (water == null) {
            water = WorkSites.waterBeside(level, spot).orElse(null);
            if (water == null) {
                spot = null;
                return;
            }
            bite = pace(200 + citizen.getRandom().nextInt(300)); // casting out, waiting for a bite
            citizen.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, water, SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5f, 0.8f);
        }
        citizen.getLookControl().setLookAt(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
        if (--bite > 0) {
            if (bite % 40 == 0) {
                level.sendParticles(ParticleTypes.FISHING, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 1, 0.1, 0, 0.1, 0);
            }
            return;
        }
        level.sendParticles(ParticleTypes.SPLASH, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 12, 0.2, 0.05, 0.2, 0.1);
        level.playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6f, 1.0f);
        for (ItemStack caught : catchAt(level, water, tool())) {
            pocket(caught);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        citizen.didWork(1);
        water = null; // cast again
    }

    /** One catch from the game's fishing loot. */
    public static List<ItemStack> catchAt(ServerLevel level, BlockPos water, ItemStack rod) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, water.getCenter())
                .withParameter(LootContextParams.TOOL, rod).withLuck(0).create(LootContextParamSets.FISHING);
        return table.getRandomItems(params);
    }
}
