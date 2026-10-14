package dev.eiriksb.stad.colony;

import net.minecraft.util.RandomSource;

import java.util.Collection;

/** Names for new towns, after old English places. */
final class TownNames {
    private static final String[] FIRST = {
            "Ash", "Oak", "Elm", "Mill", "Stone", "Brook", "North", "South", "East", "West", "Green", "Red", "Old", "New",
            "Fair", "Hazel", "Wolf", "Fox", "Raven", "Thorn", "Hay", "Long", "High", "Clear", "Rock", "Willow", "Bramble", "Kings"};
    private static final String[] LAST = {
            "ford", "ton", "by", "ham", "field", "wick", "wood", "bridge", "dale", "stead", "bury", "hill", "mere", "well", "combe", "worth"};

    private TownNames() {}

    static String random(RandomSource r, Collection<String> taken) {
        for (int i = 0; i < 50; i++) {
            String n = FIRST[r.nextInt(FIRST.length)] + LAST[r.nextInt(LAST.length)];
            if (!taken.contains(n)) {
                return n;
            }
        }
        return "Town " + (taken.size() + 1);
    }
}
