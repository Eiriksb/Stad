package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderRecord;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.planner.Design;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Planning a building: checks that it fits on the town's land without overlapping anything, then queues it. */
public final class Planning {
    private Planning() {}

    public record Result(Building building, String problem) {}

    /** Where a building would go, or why it can't. */
    public static String problem(Colony colony, BuildingDef def, BlockPos origin, Rotation rotation) {
        return problem(colony, def.blueprint(), origin, rotation);
    }

    public static String problem(Colony colony, Blueprint blueprint, BlockPos origin, Rotation rotation) {
        Placement p = Placement.of(blueprint, origin, rotation);
        BoundingBox box = p.bounds();
        for (BlockPos corner : new BlockPos[]{new BlockPos(box.minX(), box.minY(), box.minZ()), new BlockPos(box.maxX(), box.minY(), box.minZ()),
                new BlockPos(box.minX(), box.minY(), box.maxZ()), new BlockPos(box.maxX(), box.minY(), box.maxZ())}) {
            if (!colony.contains(colony.dimension, corner)) {
                return "That would reach beyond " + colony.name() + "'s land.";
            }
        }
        if (box.isInside(colony.center)) {
            return "That would build over the town hall.";
        }
        for (Building other : colony.buildings()) {
            if (other.placement().map(o -> o.bounds().intersects(box)).orElse(false)) {
                return "That overlaps the " + other.name + ".";
            }
        }
        return null;
    }

    /** A player plans a building (an order to the town's builders, so it's rank-checked and logged). */
    public static Result plan(ServerPlayer player, ServerLevel level, Colony colony, BuildingDef def, BlockPos origin, Rotation rotation) {
        return plan(player, level, colony, def.key(), def.name(), def.blueprint(), null, origin, rotation);
    }

    /** A player has a building they drew up with a builder planned. */
    public static Result plan(ServerPlayer player, ServerLevel level, Colony colony, Design design, BlockPos origin, Rotation rotation) {
        net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("stad", "design/" + design.slug());
        return plan(player, level, colony, key, design.name(), design.blueprint(), design, origin, rotation);
    }

    private static Result plan(ServerPlayer player, ServerLevel level, Colony colony, net.minecraft.resources.ResourceLocation key, String name,
                               Blueprint blueprint, Design design, BlockPos origin, Rotation rotation) {
        Order.Outcome outcome;
        String reason = null;
        Building planned = null;
        if (!colony.rank(player.getUUID()).atLeast(OrderKind.PLAN_BUILDING.minRank)) {
            outcome = Order.Outcome.NOT_ALLOWED;
            reason = "Only officers of " + colony.name() + " can plan buildings.";
        } else if ((reason = problem(colony, blueprint, origin, rotation)) != null) {
            outcome = Order.Outcome.INVALID;
        } else {
            outcome = Order.Outcome.ACCEPTED;
            planned = colony.addBuilding(key, name, origin, rotation, level.getGameTime(), design);
            planned.outlineUntil = level.getGameTime() + 200;
        }
        colony.log(new OrderRecord(level.getGameTime(), player.getUUID(), player.getGameProfile().getName(), null, name,
                OrderKind.PLAN_BUILDING, outcome, reason));
        return new Result(planned, reason);
    }

    public static boolean canPlan(Colony colony, ServerPlayer player) {
        return colony.rank(player.getUUID()).atLeast(Rank.OFFICER);
    }
}
