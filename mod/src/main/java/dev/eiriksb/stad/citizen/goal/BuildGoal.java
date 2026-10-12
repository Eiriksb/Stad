package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.twt.StadConversations;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Optional;

/**
 * A builder's work day: take the town's next planned building, clear its site, then build it block by block with
 * materials from the town's storage, asking for what's missing.
 */
public class BuildGoal extends Goal {
    static final double REACH = 5.0;
    private static final long ASK_EVERY = 3 * 60 * 20;
    private final CitizenEntity citizen;
    private Building building;
    private int cooldown;
    private final Movement.Approach approach = new Movement.Approach();

    public BuildGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workTime() {
        return citizen.canWork();
    }

    private Optional<Colony> colony() {
        return citizen.colony();
    }

    @Override
    public boolean canUse() {
        if (!workTime() || !(citizen.level() instanceof ServerLevel)) {
            return false;
        }
        Optional<Colony> c = colony();
        if (c.isEmpty() || !Jobs.BUILDER.equals(citizen.record().map(r -> r.job).orElse(null))) {
            return false;
        }
        building = c.get().workFor(citizen.getUUID()).filter(b -> b.placement().isPresent()).orElse(null);
        return building != null;
    }

    @Override
    public boolean canContinueToUse() {
        return workTime() && building != null && !building.done() && citizen.getUUID().equals(building.builder)
                && Jobs.BUILDER.equals(citizen.record().map(r -> r.job).orElse(null));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        cooldown = 0;
        approach.reset();
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) citizen.level();
        Colony colony = colony().orElse(null);
        Placement placement = building.placement().orElse(null);
        if (colony == null || placement == null) {
            return;
        }
        ColonyStorage storage = null;
        if (building.status == Building.Status.WAITING) {
            if (citizen.tickCount % 100 != 0) {
                return;
            }
            storage = ColonyStorage.supply(level, colony);
            Placement.Step next = Construction.next(level, building, placement);
            if (next != null && next.count() > 0 && storage.count(next.item()) < next.count()) {
                building.missing = Construction.missing(level, building, placement, storage);
                ask(level, colony);
                return;
            }
            building.status = Building.Status.BUILDING;
            colony.changed();
        }
        Placement.Step step = Construction.next(level, building, placement);
        if (step == null) {
            finish(level, colony);
            return;
        }
        BlockPos pos = step.pos();
        if (!approach.tick(citizen, pos, REACH, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = StadConfig.BUILDER_TICKS_PER_BLOCK.get();
        if (storage == null) {
            storage = ColonyStorage.supply(level, colony);
        }
        BlockState old = level.getBlockState(pos);
        if (old.getDestroySpeed(level, pos) < 0) {
            building.progress++; // bedrock and the like: build around it
            return;
        }
        if (!step.clearing() && step.count() > 0 && !storage.take(step.item(), step.count())) {
            building.status = Building.Status.WAITING;
            building.missing = Construction.missing(level, building, placement, storage);
            colony.changed();
            ask(level, colony);
            return;
        }
        salvage(level, pos, old, storage);
        if (step.clearing()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        } else {
            if (citizen.getBoundingBox().intersects(new AABB(pos))) {
                Movement.teleportNear(citizen, pos.above());
            }
            level.setBlock(pos, step.state(), 3);
            level.playSound(null, pos, step.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        citizen.didWork(1);
        building.progress++;
        building.status = Building.Status.BUILDING;
        if (building.progress % 10 == 0) {
            colony.changed();
        }
    }

    /** What was in the way goes into the town's storage (and a chest's contents onto the ground). */
    private static void salvage(ServerLevel level, BlockPos pos, BlockState old, ColonyStorage storage) {
        if (old.isAir() || old.canBeReplaced()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof Container c) {
            Containers.dropContents(level, pos, c);
        }
        ItemStack item = new ItemStack(old.getBlock().asItem());
        if (!item.isEmpty()) {
            storage.put(item);
        }
    }

    private void finish(ServerLevel level, Colony colony) {
        building.status = Building.Status.DONE;
        building.builder = null;
        building.missing.clear();
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(citizen.getName().getString() + " finished the " + building.name
                + " in " + colony.name() + "!").withStyle(ChatFormatting.GREEN));
        StadConversations.speakUp(citizen, 32, "(You just finished building the " + building.name + " in " + colony.name()
                + ". Tell {player} about it proudly, in a sentence or two.)");
    }

    /** Tells the town what's missing, every few minutes while waiting. */
    private void ask(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now - building.lastAsked < ASK_EVERY || building.missing.isEmpty()) {
            return;
        }
        building.lastAsked = now;
        StadConversations.speakUp(citizen, 24, "(You're building the " + building.name + " but you've run out of materials: you need "
                + Construction.describe(building.missing, 3) + ". Ask {player} to put them in the town hall, in a sentence or two.)");
        Announce.members(level.getServer(), colony, Component.literal("[" + colony.name() + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(citizen.getName().getString() + " (builder): I need " + Construction.describe(building.missing, 4)
                        + " to keep building the " + building.name + ". Put them in the town hall, please.").withStyle(ChatFormatting.YELLOW)));
    }
}
