package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.building.Survey;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.planner.Design;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Taking over a building someone made (PLAN.md §6.5): stand inside it and it's surveyed; if it has what its kind of
 * building needs (a bed for a home, chests for a warehouse...) it becomes one of the town's, as it stands. Otherwise
 * you hear what's missing. A home can then be given to a citizen. Server thread.
 */
public final class Adoption {
    private Adoption() {}

    /**
     * What a building would make.
     *
     * @param existing the town's building it already is (when you're standing in one)
     * @param problem  why it can't be taken over at all, or null
     * @param missing  what it needs to be a {@code type}
     * @param wishes   what would make it nicer
     */
    public record Check(Survey.Result survey, ResourceLocation type, List<String> missing, List<String> wishes, Building existing,
                        String problem) {
        public boolean ok() {
            return problem == null && missing.isEmpty();
        }

        /** "a bed and a roof without holes" */
        public String missingText() {
            return Design.join(missing);
        }
    }

    /** Surveys the building around {@code at} as a {@code wanted} (null: whatever it looks like). */
    public static Check check(ServerLevel level, Colony colony, BlockPos at, ResourceLocation wanted) {
        if (!colony.contains(level.dimension(), at)) {
            return new Check(null, wanted, List.of(), List.of(), null, "That isn't on " + colony.name() + "'s land.");
        }
        for (Building b : colony.buildings()) {
            if (b.occupies(at)) {
                if (!b.done()) {
                    return new Check(null, b.type(), List.of(), List.of(), b, "That's the " + b.name + ", which isn't finished yet.");
                }
                ResourceLocation type = b.type();
                if (wanted != null && !wanted.equals(type)) {
                    return new Check(null, type, List.of(), List.of(), b, "That's already the town's " + b.name + ".");
                }
                return new Check(null, type, List.of(), List.of(), b, null);
            }
        }
        Survey.Result s = Survey.of(level, at);
        if (!s.ok()) {
            return new Check(s, wanted, List.of(), List.of(), null, s.problem());
        }
        BoundingBox box = s.box();
        for (BlockPos corner : new BlockPos[]{new BlockPos(box.minX(), box.minY(), box.minZ()), new BlockPos(box.maxX(), box.minY(), box.maxZ()),
                new BlockPos(box.minX(), box.minY(), box.maxZ()), new BlockPos(box.maxX(), box.minY(), box.minZ())}) {
            if (!colony.contains(level.dimension(), corner)) {
                return new Check(s, wanted, List.of(), List.of(), null, "It reaches beyond " + colony.name() + "'s land.");
            }
        }
        for (Building b : colony.buildings()) {
            if (b.overlaps(box)) {
                return new Check(s, wanted, List.of(), List.of(), null, "It runs into the " + b.name + ".");
            }
        }
        ResourceLocation type = wanted != null ? wanted : Requirements.guess(s.contents()).orElse(BuildingDef.HOME);
        return new Check(s, type, Requirements.missing(type, s.contents()), Requirements.wishes(type, s.contents()), null, null);
    }

    /** A name for it: "Ingrid's House", "Warehouse"... */
    public static String name(ResourceLocation type, String owner) {
        String word = Requirements.word(type);
        String title = Character.toUpperCase(word.charAt(0)) + word.substring(1);
        if (type.equals(BuildingDef.HOME)) {
            title = "House";
        } else if (type.equals(BuildingDef.BUILDER_HUT)) {
            title = "Builder's Hut";
        }
        return owner == null ? title : owner + "'s " + title;
    }

    public record Result(Building building, Order.Outcome outcome, String reason) {}

    /** The player has the town take it over (an officer's call). Returns the building, or why not. */
    public static Result adopt(ServerPlayer player, ServerLevel level, Colony colony, Check c, String name) {
        if (c.existing() != null && c.problem() == null) {
            return new Result(c.existing(), Order.Outcome.ACCEPTED, null);
        }
        Order.Outcome outcome;
        String reason;
        Building b = null;
        if (!colony.rank(player.getUUID()).atLeast(OrderKind.ADOPT_BUILDING.minRank)) {
            outcome = Order.Outcome.NOT_ALLOWED;
            reason = "Only officers of " + colony.name() + " can take buildings over for the town.";
        } else if (c.problem() != null) {
            outcome = Order.Outcome.INVALID;
            reason = c.problem();
        } else if ((reason = Research.locked(colony, c.type())) != null) {
            outcome = Order.Outcome.INVALID;
        } else if (!c.missing().isEmpty()) {
            outcome = Order.Outcome.INVALID;
            reason = "To be a " + Requirements.word(c.type()) + " it needs " + c.missingText() + ".";
        } else {
            BoundingBox box = c.survey().box();
            Blueprint bp = Blueprint.capture(level, box);
            Design design = Design.asBuilt(name, c.type(), bp, "adopted");
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath("stad", "adopted/" + design.slug());
            b = colony.addBuilding(key, name, new BlockPos(box.minX(), box.minY(), box.minZ()), Rotation.NONE, level.getGameTime(), design);
            b.status = Building.Status.DONE;
            b.outlineUntil = level.getGameTime() + 100;
            colony.changed();
            outcome = Order.Outcome.ACCEPTED;
            reason = null;
        }
        colony.log(new OrderRecord(level.getGameTime(), player.getUUID(), player.getGameProfile().getName(), null, name,
                OrderKind.ADOPT_BUILDING, outcome, reason));
        return new Result(b, outcome, reason);
    }

    /** Gives {@code citizen} a bed in {@code home} (an order: they may refuse a leader they resent). */
    public static Order.Result giveHome(ServerPlayer player, CitizenEntity citizen, Building home) {
        return OrderService.issue(new Order(player, OrderKind.ASSIGN_HOME, citizen, String.valueOf(home.id)));
    }

    /** "the house you're standing in has two beds, a door and a furnace" (for the citizen to talk about). */
    public static String describe(Check c) {
        if (c.survey() == null) {
            return c.existing() != null ? "the town's " + c.existing().name : "the building";
        }
        var k = c.survey().contents();
        java.util.List<String> parts = new java.util.ArrayList<>();
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.BED), "bed");
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.DOOR), "door");
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.STORAGE), "chest");
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.CRAFTING_TABLE), "crafting table");
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.COOKING), "stove");
        count(parts, k.count(dev.eiriksb.stad.building.Contents.Thing.LIGHT), "light");
        int rooms = c.survey().rooms();
        return (rooms == 1 ? "one room" : rooms + " rooms") + (parts.isEmpty() ? ", empty" : " with " + Design.join(parts));
    }

    private static void count(java.util.List<String> parts, int n, String thing) {
        if (n > 0) {
            parts.add((n == 1 ? "a" : String.valueOf(n)) + " " + thing + (n == 1 ? "" : "s"));
        }
    }
}
