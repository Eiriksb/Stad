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
        if (building == null && --whyCheck <= 0) {
            whyCheck = 100;
            citizen.stuck("Nothing to build: plan a building with Building Plans, or tell them what you'd like built");
        }
        return building != null;
    }

    @Override
    public boolean canContinueToUse() {
        return workTime() && building != null && !building.done() && citizen.getUUID().equals(building.builder)
                && colony().flatMap(c -> c.building(building.id)).orElse(null) == building // not cancelled
                && Jobs.BUILDER.equals(citizen.record().map(r -> r.job).orElse(null));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        cooldown = 0;
        current = null;
        approach.reset();
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        current = null;
    }

    /** ticks until they say again that there's nothing to build */
    private int whyCheck;

    /** the step they're on (null: work out the next one) */
    private Placement.Step current;

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) citizen.level();
        Colony colony = colony().orElse(null);
        Placement placement = building.placement().orElse(null);
        if (colony == null || placement == null) {
            return;
        }
        ColonyStorage storage = null;
        if (current == null || Construction.satisfied(level, current)) {
            current = null;
            if (building.status == Building.Status.WAITING && citizen.tickCount % 100 != 0) {
                return; // waiting for materials: look again every few seconds
            }
            storage = ColonyStorage.forBuilding(level, colony);
            current = Construction.doable(level, building, placement, storage);
            if (current == null) {
                if (Construction.next(level, building, placement) == null) {
                    finish(level, colony);
                    return;
                }
                building.status = Building.Status.WAITING;
                building.missing = Construction.missing(level, building, placement, storage);
                colony.changed();
                citizen.stuck("Waiting for " + Construction.describe(building.missing, 3) + " for the " + building.name
                        + ": put them in the town hall");
                ask(level, colony);
                return;
            }
            if (building.status != Building.Status.BUILDING) {
                building.status = Building.Status.BUILDING;
                building.missing.clear();
                colony.changed();
            }
        }
        if (citizen.tickCount % 40 == 0) {
            citizen.doing("Building the " + building.name + " (" + Math.round(building.fractionDone() * 100) + "%)");
        }
        Placement.Step step = current;
        BlockPos pos = step.pos();
        if (!approach.tick(citizen, pos, REACH, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (--cooldown > 0) {
            return;
        }
        cooldown = StadConfig.BUILDER_TICKS_PER_BLOCK.get() * (citizen.grudging() ? 2 : 1);
        if (storage == null) {
            storage = ColonyStorage.forBuilding(level, colony);
        }
        BlockState old = level.getBlockState(pos);
        if (!step.clearing() && step.count() > 0 && !storage.take(step.item(), step.count())) {
            current = null; // someone took it: work out what's next
            return;
        }
        salvage(level, pos, old, ColonyStorage.supply(level, colony));
        if (step.clearing()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        } else {
            if (pos.getY() == placement.origin().getY() && step.state().isCollisionShapeFullBlock(level, pos)) {
                foundation(level, pos);
            }
            if (citizen.getBoundingBox().intersects(new AABB(pos))) {
                Movement.teleportNear(citizen, pos.above());
            }
            level.setBlock(pos, step.state(), 3);
            level.playSound(null, pos, step.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        }
        citizen.swing(InteractionHand.MAIN_HAND);
        citizen.didWork(1);
        current = null;
        if (++placedSince % 10 == 0) {
            Construction.next(level, building, placement);
            building.stepsDone = Construction.done(level, building, placement);
            colony.changed();
        }
    }

    private int placedSince;

    /** Under the bottom of the building, the ground is made up with dirt (a slope, a hollow, shallow water), a few blocks down at most. */
    private static void foundation(ServerLevel level, BlockPos floor) {
        for (int d = 1; d <= 8; d++) {
            BlockPos p = floor.below(d);
            BlockState s = level.getBlockState(p);
            if (!s.canBeReplaced() || s.getDestroySpeed(level, p) < 0) {
                break;
            }
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
        }
    }

    /** What was in the way goes into the town's storage (and a chest's contents onto the ground). */
    private static void salvage(ServerLevel level, BlockPos pos, BlockState old, ColonyStorage storage) {
        if (old.isAir() || old.canBeReplaced() || old.is(net.minecraft.tags.BlockTags.LEAVES) || old.is(net.minecraft.tags.BlockTags.FLOWERS)) {
            return; // nothing the town would want back
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
        if (building.done()) {
            return; // the goal can tick once more before it notices
        }
        building.status = Building.Status.DONE;
        building.builder = null;
        building.missing.clear();
        if (building.isRepair()) { // the damage is mended; the order goes
            colony.removeBuilding(building.id);
            Announce.members(level.getServer(), colony, Component.literal(citizen.getName().getString() + " has finished the "
                    + building.name.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + building.name.substring(1) + ".").withStyle(ChatFormatting.GREEN));
            StadConversations.speakUp(citizen, 32, "(You just finished the " + building.name.toLowerCase(java.util.Locale.ROOT)
                    + " after the raid. Tell {player}, in a sentence.)");
            return;
        }
        colony.changed();
        Announce.members(level.getServer(), colony, Component.literal(citizen.getName().getString() + " finished the " + building.name
                + " in " + colony.name() + "!").withStyle(ChatFormatting.GREEN));
        StadConversations.speakUp(citizen, 32, "(You just finished building the " + building.name + " in " + colony.name()
                + ". Tell {player} about it proudly, in a sentence or two.)");
    }

    /** Tells the town what's missing, every few minutes while waiting. */
    private void ask(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        if (building.offer == null && !building.missing.isEmpty()) {
            building.offer = dev.eiriksb.stad.building.Substitutions.offer(level, colony, building);
            if (building.offer != null) { // something else would do: ask about that instead
                building.lastAsked = now;
                dev.eiriksb.stad.building.Substitutions.ask(level, colony, building, citizen);
                return;
            }
        }
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
