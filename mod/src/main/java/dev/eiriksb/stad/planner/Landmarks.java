package dev.eiriksb.stad.planner;

import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Places in the land around a town that people name when they say where a building should go: "facing the lake",
 * "by the forest", "up on the hill", "next to the fields" (PLAN.md §6.4). Server thread; only loaded land is looked at.
 */
public final class Landmarks {
    private Landmarks() {}

    public enum Kind { WATER, TREES, HILL, FIELDS }

    /** A place and the spot that stands for it. */
    public record Landmark(Kind kind, String name, BlockPos pos) {}

    private static final Pattern WATER = Pattern.compile("\\b(lake|pond|water|river|sea|shore|beach|coast|stream|creek|bay|ocean|fjord)\\b");
    private static final Pattern TREES = Pattern.compile("\\b(forest|woods?|trees|grove)\\b");
    private static final Pattern HILL = Pattern.compile("\\b(hill|hilltop|mountain|cliff|high ground|ridge)\\b");
    private static final Pattern FIELDS = Pattern.compile("\\b(fields?|farms?|farmland|crops|meadow)\\b");
    /** how far past the town's land to look (a lake just outside still counts) */
    private static final int MARGIN = 24;

    /** What kind of place {@code text} names, if any. */
    public static Optional<Kind> kind(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (WATER.matcher(t).find()) {
            return Optional.of(Kind.WATER);
        }
        if (TREES.matcher(t).find()) {
            return Optional.of(Kind.TREES);
        }
        if (HILL.matcher(t).find()) {
            return Optional.of(Kind.HILL);
        }
        if (FIELDS.matcher(t).find()) {
            return Optional.of(Kind.FIELDS);
        }
        return Optional.empty();
    }

    /** The place {@code text} names nearest {@code from} (for a hill: the highest ground), if there is one. */
    public static Optional<Landmark> find(ServerLevel level, Colony colony, String text, BlockPos from) {
        Optional<Kind> kind = kind(text);
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        int r = colony.radius + MARGIN;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = colony.center.getX() - r; x <= colony.center.getX() + r; x += 2) {
            for (int z = colony.center.getZ() - r; z <= colony.center.getZ() + r; z += 2) {
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
                if (!matches(level, kind.get(), top)) {
                    continue;
                }
                double score = kind.get() == Kind.HILL ? -top.getY() * 1000.0 + top.distSqr(from) / 1000.0 : top.distSqr(from);
                if (score < bestScore) {
                    best = top;
                    bestScore = score;
                }
            }
        }
        String name = switch (kind.get()) {
            case WATER -> "the water";
            case TREES -> "the trees";
            case HILL -> "the hilltop";
            case FIELDS -> "the fields";
        };
        return Optional.ofNullable(best).map(p -> new Landmark(kind.get(), name, p));
    }

    private static boolean matches(ServerLevel level, Kind kind, BlockPos top) {
        return switch (kind) {
            case WATER -> level.getFluidState(top).is(FluidTags.WATER) && around(level, top, 3, 10, p -> level.getFluidState(p).is(FluidTags.WATER));
            case TREES -> level.getBlockState(top).is(BlockTags.LEAVES) && around(level, top, 3, 10, p -> level.getBlockState(p).is(BlockTags.LEAVES));
            case HILL -> level.getFluidState(top).isEmpty() && !level.getBlockState(top).is(BlockTags.LEAVES) && !level.getBlockState(top).is(BlockTags.LOGS);
            case FIELDS -> level.getBlockState(top).is(Blocks.FARMLAND) || level.getBlockState(top.below()).is(Blocks.FARMLAND);
        };
    }

    /** Whether at least {@code min} spots in the square of radius {@code r} round {@code p} (same height, every other block) match. */
    private static boolean around(ServerLevel level, BlockPos p, int r, int min, java.util.function.Predicate<BlockPos> test) {
        int n = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (test.test(p.offset(dx, 0, dz)) && ++n >= min) {
                    return true;
                }
            }
        }
        return false;
    }
}
