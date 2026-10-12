package dev.eiriksb.stad.citizen;

import net.minecraft.resources.ResourceLocation;

/**
 * What citizens look like. For now the game's own default player skins (slim arms for women, wide for men); layered,
 * inherited looks come later (PLAN.md §5, "MCA-style life").
 */
public final class CitizenLooks {
    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    public static final int SKIN_COUNT = SKINS.length;

    private CitizenLooks() {}

    public static ResourceLocation texture(int skin, boolean slim) {
        return ResourceLocation.withDefaultNamespace("textures/entity/player/" + (slim ? "slim/" : "wide/")
                + SKINS[Math.floorMod(skin, SKIN_COUNT)] + ".png");
    }
}
