package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.StadConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps towns' land loaded ({@code keepLoaded}) so builders keep building and couriers keep carrying while their
 * players are off mining. Through NeoForge's forced chunks, which also keep a dimension ticking with nobody in it.
 * Crops still only grow near players (vanilla's random ticks), as with any loaded chunk. Server thread.
 */
public final class ColonyChunks {
    /** Tickets left from the last run are dropped on load; {@link #update} takes the ones still wanted again. */
    private static final TicketController TICKETS = new TicketController(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "towns"),
            (level, helper) -> List.copyOf(helper.getBlockTickets().keySet()).forEach(helper::removeAllTickets));

    private record Held(ResourceKey<Level> dimension, BlockPos center, int radius) {}

    private static final Map<Integer, Held> held = new HashMap<>();

    private ColonyChunks() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterTicketControllersEvent e) -> e.register(TICKETS));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> held.clear());
    }

    /** Every few seconds: holds the towns that should stay loaded, lets go of the rest. */
    public static void update(MinecraftServer server) {
        int radius = StadConfig.LOADED_RADIUS.get();
        Set<Integer> seen = new HashSet<>();
        for (Colony c : ColonyManager.get(server).all()) {
            seen.add(c.id);
            Held h = held.get(c.id);
            if (!wanted(server, c)) {
                if (h != null) {
                    release(server, c.id);
                }
            } else if (h == null || h.radius != radius || !h.center.equals(c.center) || h.dimension != c.dimension) {
                if (h != null) {
                    release(server, c.id);
                }
                hold(server, c, radius);
            }
        }
        for (Integer id : List.copyOf(held.keySet())) {
            if (!seen.contains(id)) {
                release(server, id); // dissolved
            }
        }
    }

    static boolean wanted(MinecraftServer server, Colony c) {
        return switch (StadConfig.KEEP_LOADED.get()) {
            case NEVER -> false;
            case ALWAYS -> true;
            case WHILE_ONLINE -> c.ranks().keySet().stream().anyMatch(id -> server.getPlayerList().getPlayer(id) != null)
                    || server.getPlayerList().getPlayer(c.owner()) != null;
        };
    }

    private static void hold(MinecraftServer server, Colony c, int radius) {
        ServerLevel level = server.getLevel(c.dimension);
        if (level == null) {
            return;
        }
        force(level, c.center, radius, true);
        held.put(c.id, new Held(c.dimension, c.center, radius));
    }

    private static void release(MinecraftServer server, int id) {
        Held h = held.remove(id);
        ServerLevel level = h == null ? null : server.getLevel(h.dimension);
        if (level != null) {
            force(level, h.center, h.radius, false);
        }
    }

    private static void force(ServerLevel level, BlockPos center, int radius, boolean add) {
        ChunkPos mid = new ChunkPos(center);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                TICKETS.forceChunk(level, center, mid.x + dx, mid.z + dz, add, true);
            }
        }
    }

    /** Whether {@code colony}'s land is being kept loaded. */
    public static boolean held(Colony colony) {
        return held.containsKey(colony.id);
    }

    /** For {@code /stad perf}. */
    public static String describe(MinecraftServer server) {
        int r = StadConfig.LOADED_RADIUS.get();
        int side = 2 * r + 1;
        return "Towns kept loaded: " + held.size() + " (" + StadConfig.KEEP_LOADED.get().name().toLowerCase(java.util.Locale.ROOT)
                + ", " + side + "x" + side + " chunks each)";
    }
}
