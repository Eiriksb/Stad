package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/**
 * A town's land grows as it does (PLAN.md §6.1): each home finished on it adds {@code landPerHome} blocks to how far
 * it reaches, up to {@code maxRadius}, and never so far that it runs into another town's land. It never shrinks: a
 * home pulled down doesn't take land back.
 */
public final class Growth {
    private Growth() {}

    /** How many finished homes the town has. */
    public static int homes(Colony colony) {
        return (int) colony.buildings().stream().filter(b -> b.done() && b.is(BuildingDef.HOME)).count();
    }

    /** How far the town's land would reach with its homes, before any other town is in the way. */
    public static int wanted(Colony colony) {
        return Math.min(max(colony), colony.baseRadius + perHome(colony) * homes(colony));
    }

    /** How far it can grow at most (further with Surveying and Cartography researched). */
    public static int max(Colony colony) {
        return Math.max(colony.baseRadius, StadConfig.MAX_RADIUS.get() + Research.moreLand(colony));
    }

    /** How much each home adds. */
    public static int perHome(Colony colony) {
        int per = StadConfig.LAND_PER_HOME.get();
        return per == 0 ? 0 : per + Research.morePerHome(colony);
    }

    /** How far it can reach without running into another town's land. */
    public static int room(Colony colony, Collection<Colony> all) {
        long room = Long.MAX_VALUE;
        for (Colony other : all) {
            if (other != colony && other.dimension.equals(colony.dimension)) {
                room = Math.min(room, (long) Math.sqrt(colony.horizontalDistSqr(other.center)) - other.radius);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /** Grows the town's land to fit its homes; tells its members and lets its citizens go further. Returns whether it grew. */
    public static boolean update(ServerLevel level, Colony colony, Collection<Colony> all) {
        int to = Math.min(wanted(colony), room(colony, all));
        if (to <= colony.radius) {
            return false;
        }
        colony.radius = to;
        colony.changed();
        for (CitizenRecord r : colony.citizens()) {
            if (level.getEntity(r.uuid) instanceof CitizenEntity c) {
                c.restrictTo(colony.center, colony.radius);
            }
        }
        Announce.members(level.getServer(), colony, Component.literal(colony.name() + "'s land has grown: it reaches " + colony.radius
                + " blocks round the town hall now.").withStyle(ChatFormatting.GREEN));
        return true;
    }
}
