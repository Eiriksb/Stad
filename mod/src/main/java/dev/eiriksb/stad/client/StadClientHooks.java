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

    public static void openEditor(dev.eiriksb.stad.net.EditorPayload editor) {
        Minecraft.getInstance().setScreen(new PackEditorScreen(editor));
    }

    public static void openCitizen(dev.eiriksb.stad.net.CitizenScreenPayload p) {
        Minecraft.getInstance().setScreen(new CitizenChatScreen(p));
    }

    /** A new line for the citizen chat screen, if it's open for that citizen. */
    public static void citizenLine(dev.eiriksb.stad.net.CitizenLinePayload p) {
        if (Minecraft.getInstance().screen instanceof CitizenChatScreen s && s.citizen().equals(p.citizen())) {
            s.add(new dev.eiriksb.stad.net.CitizenScreenPayload.Line(p.fromPlayer(), p.speaker(), p.text()));
        }
    }

    public static void draftPreview(dev.eiriksb.stad.net.DraftPreviewPayload preview) {
        GhostPreview.set(preview);
    }
}
