package dev.eiriksb.stad.citizen;

import net.minecraft.resources.ResourceLocation;

/**
 * The game's own default player skins, which raiders wear. Citizens are put together from their own parts instead
 * ({@link Looks}, dev.eiriksb.stad.client.CitizenSkins).
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
