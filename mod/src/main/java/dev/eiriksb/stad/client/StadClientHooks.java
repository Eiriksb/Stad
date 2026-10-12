package dev.eiriksb.stad.client;

import dev.eiriksb.stad.net.ColonyView;
import dev.eiriksb.stad.net.PlansPayload;
import net.minecraft.client.Minecraft;

/** Client-side handlers for network messages (only loaded on the client). */
public final class StadClientHooks {
    private StadClientHooks() {}

    public static void openTownHall(ColonyView view) {
        Minecraft.getInstance().setScreen(new TownHallScreen(view));
    }

    public static void openPlans(PlansPayload plans) {
        Minecraft.getInstance().setScreen(new PlansScreen(plans));
    }

    public static void draftPreview(dev.eiriksb.stad.net.DraftPreviewPayload preview) {
        GhostPreview.set(preview);
    }
}
