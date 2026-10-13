package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.StadConfig;

/**
 * How a citizen sounds (PLAN.md M9), from their genes, sex and age: a voice description for They Will Talk's
 * designed voices, and pitch and pace for the others. The description only changes when they reach a new stage of
 * life (a child's voice breaks, a woman in her twenties turns thirty), because a changed description is a new voice.
 * It deliberately leaves out their job, town and mood: the mood is added line by line by They Will Talk.
 */
public final class CitizenVoice {
    private CitizenVoice() {}

    private static final String[] NUMBERS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen"};
    private static final String[] DECADES = {"", "", "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties"};

    /** How old they are, in years: children by how far they've grown, adults as they came. */
    public static int age(CitizenEntity c) {
        if (c.isBaby()) {
            long days = StadConfig.CHILD_DAYS.get() * 24000L;
            double grown = Math.max(0, Math.min(1, (c.level().getGameTime() - c.born()) / (double) days));
            return 4 + (int) Math.round(grown * 13); // 4 to 17
        }
        return c.ageYears();
    }

    /** "child", "teen" or "adult", as They Will Talk counts them. */
    public static String ageGroup(int age) {
        return age < 13 ? "child" : age < 18 ? "teen" : "adult";
    }

    /** "a woman in her thirties", "a little boy of about six" */
    public static String person(boolean female, int age) {
        String he = female ? "girl" : "boy";
        if (age < 10) {
            return "a little " + he + " of about " + NUMBERS[age < 7 ? 5 : 8];
        }
        if (age < 13) {
            return "a " + he + " of about eleven";
        }
        if (age < 18) {
            return "a teenage " + he + " of about " + NUMBERS[age < 16 ? 14 : 16];
        }
        String who = female ? "woman" : "man";
        String her = female ? "her" : "his";
        int decade = Math.min(9, age / 10);
        return (age >= 60 ? "an elderly " : age < 30 ? "a young " : "a ") + who + " in " + her + " " + DECADES[decade];
    }

    /** The voice description for They Will Talk's voice designer. */
    public static String design(CitizenEntity c) {
        Genes g = c.genes();
        int age = age(c);
        boolean female = c.isFemale();
        String pitch;
        if (age < 13) {
            pitch = g.pitch() < 0.95 ? "slightly low for a child" : g.pitch() > 1.05 ? "high, piping" : "childlike";
        } else if (g.pitch() < 0.93) {
            pitch = female ? "low" : "deep";
        } else if (g.pitch() > 1.07) {
            pitch = female ? "high" : "light, tenor";
        } else {
            pitch = "medium-pitched";
        }
        String pace = g.pace() < 0.95 ? "speaks slowly" : g.pace() > 1.05 ? "speaks quickly" : "speaks at an easy pace";
        String person = person(female, age);
        return Character.toUpperCase(person.charAt(0)) + person.substring(1) + " with a " + pitch + ", " + g.timbreWord() + " voice and "
                + g.accentWords() + "; " + pace + (age >= 60 ? ", a little weathered with age" : "") + ". Expressive, characterful storybook delivery.";
    }

    /** Pitch for the voices that aren't designed: children higher, the old a touch lower. */
    public static double pitch(CitizenEntity c) {
        int age = age(c);
        return c.genes().pitch() + (age < 13 ? 0.12 : age < 18 ? 0.05 : age >= 60 ? -0.04 : 0);
    }

    public static double pace(CitizenEntity c) {
        return c.genes().pace() - (age(c) >= 60 ? 0.04 : 0);
    }
}
