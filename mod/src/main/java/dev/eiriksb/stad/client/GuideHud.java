package dev.eiriksb.stad.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.StadClientConfig;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.client.ui.Theme;
import dev.eiriksb.stad.net.GuidePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The getting-started guide (the tutorial): the next step for your town, in the top right corner, until they're all
 * done, and a toast as each is done. U (a key in Controls) hides and shows it; it stays as you left it.
 */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class GuideHud {
    private GuideHud() {}

    public static final KeyMapping TOGGLE = new KeyMapping("key.stad.guide", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, "key.categories.stad");

    static final int WIDTH = 150;

    private static GuidePayload guide = GuidePayload.NONE;

    static void set(GuidePayload p) {
        GuidePayload was = guide;
        guide = p;
        if (!shown() || p.total() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!p.justDone().isEmpty()) {
            mc.getToasts().addToast(new StepToast(p.next().isEmpty() ? "All the steps done!" : "Step done (" + p.done() + " of " + p.total() + ")",
                    p.next().isEmpty() ? p.town() + " is on its way." : "Next: " + p.next()));
        } else if (was.total() == 0 && !p.next().isEmpty() && p.done() == 0) {
            mc.getToasts().addToast(new StepToast("Getting started", "Your town's next step is in the corner (" + key() + " hides it)."));
        }
    }

    private static boolean shown() {
        return StadClientConfig.SHOW_GUIDE.get();
    }

    private static String key() {
        return TOGGLE.getTranslatedKeyMessage().getString();
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        guide = GuidePayload.NONE;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        while (TOGGLE.consumeClick()) {
            StadClientConfig.SHOW_GUIDE.set(!shown());
            StadClientConfig.SHOW_GUIDE.save();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && guide.total() > 0) {
                mc.player.displayClientMessage(Component.literal(shown() ? "The town guide is showing." : "The town guide is hidden: "
                        + key() + " shows it again."), true);
            }
        }
    }

    static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        GuidePayload p = guide;
        if (p.total() == 0 || p.next().isEmpty() || !shown() || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()
                || BuildingPlacer.active() || GhostPreview.showing() || dev.eiriksb.stad.client.architect.DraftingHud.drafting(mc)) {
            return;
        }
        Font font = mc.font;
        List<FormattedCharSequence> lines = font.split(Component.literal(p.next()), WIDTH);
        if (lines.size() > 6) {
            lines = lines.subList(0, 6);
        }
        int x = g.guiWidth() - WIDTH - 8, y = 6;
        int h = 12 + lines.size() * 10 + 14;
        Theme.panel(g, x - 6, y - 5, WIDTH + 12, h + 8);
        g.drawString(font, "Next step  ", x, y, Theme.GOLD, false);
        String count = p.done() + " of " + p.total() + " done";
        g.drawString(font, count, x + WIDTH - font.width(count), y, Theme.MUTED, false);
        y += 12;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, Theme.TEXT, false);
            y += 10;
        }
        g.drawString(font, key() + ": hide", x, y + 3, Theme.FAINT, false);
    }

    /** A step done, and what's next. */
    static final class StepToast implements Toast {
        private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
        private final Component title;
        private final List<FormattedCharSequence> text;

        StepToast(String title, String text) {
            this.title = Component.literal(title);
            this.text = Minecraft.getInstance().font.split(Component.literal(text), 125);
        }

        @Override
        public int height() {
            return 20 + Math.min(3, text.size()) * 9 + 3;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
            g.blitSprite(BACKGROUND, 0, 0, width(), height());
            Font font = toasts.getMinecraft().font;
            g.renderFakeItem(new ItemStack(StadRegistries.TOWN_HALL_ITEM.get()), 8, 8);
            g.drawString(font, title, 30, 7, 0xFFFFFF00, false);
            for (int i = 0; i < Math.min(3, text.size()); i++) {
                g.drawString(font, text.get(i), 30, 18 + i * 9, 0xFFFFFFFF, false);
            }
            return time >= 7000L * toasts.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
        }
    }
}
