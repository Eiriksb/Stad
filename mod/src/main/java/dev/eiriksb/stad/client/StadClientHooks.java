package dev.eiriksb.stad.client;

import dev.eiriksb.stad.net.ColonyView;
import dev.eiriksb.stad.net.PlansPayload;
import net.minecraft.client.Minecraft;

/** Client-side handlers for network messages (only loaded on the client). */
public final class StadClientHooks {
    private StadClientHooks() {}

    public static void openTownHall(ColonyView view, String notice, boolean error) {
        TownHallScreen s = new TownHallScreen(view);
        s.notice(notice, error);
        Minecraft.getInstance().setScreen(s);
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

    public static void openTable(dev.eiriksb.stad.net.TablePayloads.Open p) {
        var next = new dev.eiriksb.stad.client.architect.ArchitectScreen(p);
        if (Minecraft.getInstance().screen instanceof dev.eiriksb.stad.client.architect.ArchitectScreen s && s.table().equals(p.table())) {
            s.replacedBy(next);
        }
        Minecraft.getInstance().setScreen(next);
    }

    public static void updateTable(dev.eiriksb.stad.net.TablePayloads.Update p) {
        if (Minecraft.getInstance().screen instanceof dev.eiriksb.stad.client.architect.ArchitectScreen s && s.table().equals(p.table())) {
            s.update(p);
        }
    }

    public static void devUi(String script) {
        DevUi.run(script);
    }

    public static void drafting(dev.eiriksb.stad.net.TablePayloads.Drafting p) {
        dev.eiriksb.stad.client.architect.DraftingHud.set(p);
    }

    public static void guide(dev.eiriksb.stad.net.GuidePayload p) {
        GuideHud.set(p);
    }

    public static void placePreview(dev.eiriksb.stad.net.PlacePreviewPayload p) {
        BuildingPlacer.receive(p);
    }

    public static void draftPreview(dev.eiriksb.stad.net.DraftPreviewPayload preview) {
        GhostPreview.set(preview);
    }

    /** A citizen said something: how they feel shows on their face, and their mouth says it if no voice does. */
    public static void speech(dev.eiriksb.stad.net.CitizenSpeechPayload p) {
        boolean voices = net.neoforged.fml.ModList.get().isLoaded("voicechat");
        long speaking = Math.max(2500, p.text().length() * LipSync.LETTER_MS);
        LipSync.said(p.citizen(), p.text(), voices);
        LipSync.feel(p.citizen(), p.emotion(), speaking + 5000);
    }
}
