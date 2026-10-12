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
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private int stuck;
    private BlockPos walkingTo;

    public BuildGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workTime() {
        return !citizen.isSleeping() && !citizen.isNight() && citizen.hunger() >= 4;
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
        stuck = 0;
        walkingTo = null;
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
            storage = ColonyStorage.of(level, colony);
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
        if (citizen.getEyePosition().distanceTo(pos.getCenter()) > REACH) {
            if (!pos.equals(walkingTo) || citizen.getNavigation().isDone()) {
                EatAtTownHallGoal.walkNextTo(citizen, pos, 0.6);
                walkingTo = pos;
            }
            if (++stuck > 160) { // eight seconds without getting there: builders know a shortcut
                teleportNear(level, pos, placement);
                stuck = 0;
            }
            return;
        }
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = StadConfig.BUILDER_TICKS_PER_BLOCK.get();
        if (storage == null) {
            storage = ColonyStorage.of(level, colony);
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
                teleportNear(level, pos.above(), placement);
            }
            level.setBlock(pos, step.state(), 3);
            level.playSound(null, pos, step.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        building.progress++;
        building.status = Building.Status.BUILDING;
        stuck = 0;
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
    }

    /** Tells the town what's missing, every few minutes while waiting. */
    private void ask(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (now - building.lastAsked < ASK_EVERY || building.missing.isEmpty()) {
            return;
        }
        building.lastAsked = now;
        Announce.members(level.getServer(), colony, Component.literal("[" + colony.name() + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(citizen.getName().getString() + " (builder): I need " + Construction.describe(building.missing, 4)
                        + " to keep building the " + building.name + ". Put them in the town hall, please.").withStyle(ChatFormatting.YELLOW)));
    }

    /** Somewhere to stand within reach of {@code pos}: solid below, room for a person, not where a block is about to go. */
    private void teleportNear(ServerLevel level, BlockPos pos, Placement placement) {
        for (int r = 1; r <= 4; r++) {
            for (int dy = 1; dy >= -4; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos at = pos.offset(dx, dy, dz);
                        if (standable(level, at) && !at.equals(pos) && !at.above().equals(pos)) {
                            citizen.getNavigation().stop();
                            citizen.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                            return;
                        }
                    }
                }
            }
        }
        int top = Math.max(placement.bounds().maxY() + 1, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                pos.getX(), pos.getZ()));
        citizen.teleportTo(pos.getX() + 0.5, top, pos.getZ() + 0.5);
    }

    private static boolean standable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
    }
}
