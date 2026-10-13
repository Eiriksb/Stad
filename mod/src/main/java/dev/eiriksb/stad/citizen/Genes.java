package dev.eiriksb.stad.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * What a citizen is born with and passes on to their children (PLAN.md M9): for now, how their voice sounds. Pitch
 * and pace are around 1 (normal); the timbre and the family's way of speaking are picked from lists. A child gets
 * the average of their parents' pitch and pace, give or take, and one parent's timbre and accent.
 */
public record Genes(double pitch, double pace, int timbre, int accent) {
    public static final List<String> TIMBRES = List.of("warm", "husky", "bright", "smooth", "gravelly", "breathy", "clear", "raspy",
            "velvety", "reedy", "mellow", "crisp");
    public static final List<String> ACCENTS = List.of("a soft Scandinavian lilt", "a broad country accent", "a crisp, precise way of speaking",
            "a gentle sing-song cadence", "a slow, drawling accent", "a clipped, matter-of-fact manner", "a hearty, booming manner",
            "a quiet, measured way of speaking");

    public Genes {
        pitch = clamp(pitch, 0.85, 1.15);
        pace = clamp(pace, 0.88, 1.12);
        timbre = Math.floorMod(timbre, TIMBRES.size());
        accent = Math.floorMod(accent, ACCENTS.size());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Genes random(RandomSource r) {
        return new Genes(1 + r.nextGaussian() * 0.06, 1 + r.nextGaussian() * 0.05, r.nextInt(TIMBRES.size()), r.nextInt(ACCENTS.size()));
    }

    /** A child of {@code a} and {@code b}. */
    public static Genes child(Genes a, Genes b, RandomSource r) {
        return new Genes((a.pitch + b.pitch) / 2 + r.nextGaussian() * 0.03, (a.pace + b.pace) / 2 + r.nextGaussian() * 0.03,
                (r.nextBoolean() ? a : b).timbre, (r.nextBoolean() ? a : b).accent);
    }

    public String timbreWord() {
        return TIMBRES.get(timbre);
    }

    public String accentWords() {
        return ACCENTS.get(accent);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("Pitch", pitch);
        t.putDouble("Pace", pace);
        t.putInt("Timbre", timbre);
        t.putInt("Accent", accent);
        return t;
    }

    /** Null when there's nothing saved (citizens from before genes). */
    public static Genes load(CompoundTag t) {
        return t == null || !t.contains("Pitch") ? null : new Genes(t.getDouble("Pitch"), t.getDouble("Pace"), t.getInt("Timbre"), t.getInt("Accent"));
    }
}
