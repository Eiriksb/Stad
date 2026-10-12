package dev.eiriksb.stad.pack;

import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.building.Outlines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** What each player has marked out with the scan wand: two corners of a building. Server thread. */
public final class Scans {
    /** the biggest building that can be scanned, along each side */
    public static final int MAX_SIDE = 48;

    public record Selection(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        public boolean complete() {
            return a != null && b != null;
        }

        public BoundingBox box() {
            return BoundingBox.fromCorners(a, b);
        }
    }

    private static final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    private Scans() {}

    public static Optional<Selection> get(ServerPlayer player) {
        Selection s = selections.get(player.getUUID());
        return s != null && s.dimension.equals(player.level().dimension()) ? Optional.of(s) : Optional.empty();
    }

    /** Sets the first or second corner; returns the new selection. */
    public static Selection corner(ServerPlayer player, BlockPos pos, boolean first) {
        Selection old = get(player).orElse(new Selection(player.level().dimension(), null, null));
        Selection s = first ? new Selection(old.dimension, pos.immutable(), old.b) : new Selection(old.dimension, old.a, pos.immutable());
        selections.put(player.getUUID(), s);
        if (s.complete()) {
            Outlines.preview(player, s.box(), 40);
        }
        return s;
    }

    public static void clear(ServerPlayer player) {
        selections.remove(player.getUUID());
    }

    /** Why a selection can't be scanned, or null. */
    public static String problem(Selection s) {
        if (s == null || !s.complete()) {
            return "Mark two opposite corners first: left-click one with the scan wand, right-click the other.";
        }
        BoundingBox box = s.box();
        if (box.getXSpan() > MAX_SIDE || box.getYSpan() > MAX_SIDE || box.getZSpan() > MAX_SIDE) {
            return "That's too big to scan: at most " + MAX_SIDE + " blocks along each side (it's " + box.getXSpan() + " x "
                    + box.getYSpan() + " x " + box.getZSpan() + ").";
        }
        return null;
    }

    /** "12 x 7 x 9 blocks" */
    public static String size(Selection s) {
        BoundingBox b = s.box();
        return b.getXSpan() + " x " + b.getYSpan() + " x " + b.getZSpan() + " blocks";
    }

    /** Every second: shows the outline to players holding the wand. */
    public static void tick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getMainHandItem().is(StadRegistries.SCAN_WAND.get()) || p.getOffhandItem().is(StadRegistries.SCAN_WAND.get())) {
                get(p).filter(Selection::complete).ifPresent(s -> Outlines.preview(p, s.box(), 25));
            }
        }
    }

    public static void forget(UUID player) {
        selections.remove(player);
    }
}
