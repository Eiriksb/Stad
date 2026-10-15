package dev.eiriksb.stad.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * What a citizen looks like, as small parts: {@code born} is what they were born with and pass on (skin tone, hair
 * and eye colour, the shape of their eyes, brows, nose and mouth, freckles and the like), {@code style} what they
 * chose (hair style, beard, clothes and their colours). Each part is a number from 0 to 63 that the client takes
 * modulo its list's length in {@code textures/entity/citizen/parts.json}, so parts can be added without changing
 * anyone's numbers; skin tone is the one that runs in order (light to dark), so a child's comes between their
 * parents'. How grey their hair is ({@link #GREY}) comes from their age and isn't inherited.
 */
public record Looks(long born, long style) {
    public static final int BITS = 6, MAX = 1 << BITS;
    // what they're born with
    public static final int SKIN = 0, HAIR_COLOUR = 1, EYE_COLOUR = 2, EYES = 3, IRIS = 4, BROW = 5, NOSE = 6, MOUTH = 7, MARKS = 8, GREY = 9;
    private static final int BORN_FIELDS = 9;
    // what they chose
    public static final int HAIR = 0, BEARD = 1, TOP = 2, TOP_COLOUR = 3, BOTTOM = 4, BOTTOM_COLOUR = 5, SHOES = 6;
    private static final int STYLE_FIELDS = 7;

    public int born(int field) {
        return (int) (born >>> (field * BITS)) & (MAX - 1);
    }

    public int style(int field) {
        return (int) (style >>> (field * BITS)) & (MAX - 1);
    }

    private static long with(long bits, int field, int value) {
        long mask = (long) (MAX - 1) << (field * BITS);
        return bits & ~mask | ((long) (value & (MAX - 1)) << (field * BITS));
    }

    public Looks withBorn(int field, int value) {
        return new Looks(with(born, field, value), style);
    }

    public Looks withStyle(int field, int value) {
        return new Looks(born, with(style, field, value));
    }

    /** How grey their hair is at {@code age}: 0 not at all, up to 3 all grey. */
    public Looks aged(int age) {
        return withBorn(GREY, age < 45 ? 0 : age < 55 ? 1 : age < 65 ? 2 : 3);
    }

    public static Looks random(RandomSource r) {
        long born = 0, style = 0;
        for (int f = 0; f < BORN_FIELDS; f++) {
            born = with(born, f, r.nextInt(MAX));
        }
        for (int f = 0; f < STYLE_FIELDS; f++) {
            style = with(style, f, r.nextInt(MAX));
        }
        return new Looks(born, style);
    }

    /**
     * A child of {@code a} and {@code b}: their skin tone between their parents', each other feature from one parent or
     * the other (now and then neither: a grandparent's), and a style of their own.
     */
    public static Looks child(Looks a, Looks b, RandomSource r) {
        Looks own = random(r);
        long born = 0;
        for (int f = 0; f < BORN_FIELDS; f++) {
            int v;
            if (f == SKIN) {
                v = Math.clamp(Math.round((a.born(f) + b.born(f)) / 2f + (float) r.nextGaussian() * 3), 0, MAX - 1);
            } else if (r.nextFloat() < 0.08f) {
                v = own.born(f);
            } else {
                v = (r.nextBoolean() ? a : b).born(f);
            }
            born = with(born, f, v);
        }
        return new Looks(born, own.style);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Born", born);
        t.putLong("Style", style);
        return t;
    }

    /** Null when there's nothing saved (citizens from before looks). */
    public static Looks load(CompoundTag t) {
        return t == null || !t.contains("Born") ? null : new Looks(t.getLong("Born"), t.getLong("Style"));
    }
}
