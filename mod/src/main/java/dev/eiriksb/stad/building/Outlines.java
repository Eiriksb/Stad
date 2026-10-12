package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Building outlines drawn with particles: previews for one player, freshly planned buildings for everyone near. */
public final class Outlines {
    private record Preview(UUID player, BoundingBox box, long until) {}

    private static final List<Preview> previews = new ArrayList<>();

    private Outlines() {}

    public static void preview(ServerPlayer player, BoundingBox box, int ticks) {
        previews.removeIf(p -> p.player.equals(player.getUUID()));
        previews.add(new Preview(player.getUUID(), box, player.level().getGameTime() + ticks));
    }

    /** Called every few ticks. */
    public static void draw(ServerLevel level, List<Colony> colonies) {
        long now = level.getGameTime();
        for (Iterator<Preview> it = previews.iterator(); it.hasNext(); ) {
            Preview p = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(p.player);
            if (p.until < now || player == null) {
                it.remove();
            } else if (player.level() == level) {
                edges(level, p.box, ParticleTypes.END_ROD, player);
            }
        }
        for (Colony c : colonies) {
            if (!c.dimension.equals(level.dimension())) {
                continue;
            }
            for (Building b : c.buildings()) {
                if (b.outlineUntil > now) {
                    b.placement().ifPresent(pl -> edges(level, pl.bounds(), ParticleTypes.HAPPY_VILLAGER, null));
                }
            }
        }
    }

    private static void edges(ServerLevel level, BoundingBox box, ParticleOptions particle, ServerPlayer only) {
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;
        double[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1}};
        int[][] lines = {{0, 1}, {0, 2}, {1, 3}, {2, 3}, {4, 5}, {4, 6}, {5, 7}, {6, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] l : lines) {
            double[] a = corners[l[0]], b = corners[l[1]];
            double len = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
            for (double t = 0; t <= len; t += 0.5) {
                double f = len == 0 ? 0 : t / len;
                double x = a[0] + (b[0] - a[0]) * f, y = a[1] + (b[1] - a[1]) * f, z = a[2] + (b[2] - a[2]) * f;
                if (only != null) {
                    level.sendParticles(only, particle, true, x, y, z, 1, 0, 0, 0, 0);
                } else {
                    level.sendParticles(particle, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
    }
}
