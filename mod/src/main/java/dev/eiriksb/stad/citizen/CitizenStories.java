package dev.eiriksb.stad.citizen;

import net.minecraft.util.RandomSource;

/** Where a new citizen comes from, read as "you ...". They're all newcomers looking for somewhere to settle. */
public final class CitizenStories {
    private static final String[] STORIES = {
            "left a fishing village up north after a hard winter emptied the nets",
            "walked away from a town whose leader worked everyone to the bone",
            "lost your farm to a pillager raid and are starting over",
            "spent years as a travelling carpenter and are tired of the road",
            "grew up in a big city and want somewhere quieter",
            "are the youngest of nine and there was no land left at home",
            "had a falling-out with your family and left to prove yourself",
            "followed rumours of good land and fresh water out here",
            "were a miner until the mine collapsed, and swore off the dark",
            "were apprenticed to a builder who died before you finished learning",
            "sailed here on a merchant ship and decided to stay",
            "were cast out of your old village after an argument with the elder",
            "dream of a town where everyone has a say",
            "are running from debts you'd rather not talk about",
            "lost your spouse two winters ago and needed a fresh start",
            "were a soldier once and want a peaceful life now"};

    private CitizenStories() {}

    public static String random(RandomSource r) {
        return STORIES[r.nextInt(STORIES.length)];
    }
}
