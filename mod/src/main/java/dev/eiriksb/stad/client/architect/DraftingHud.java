package dev.eiriksb.stad.client.architect;

import com.mojang.blaze3d.platform.InputConstants;
import dev.eiriksb.stad.Stad;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** While designing in the drafting world: a reminder at the top of the screen, and a key to finish. */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class DraftingHud {
    private DraftingHud() {}

    private static final ResourceLocation DRAFTING = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "drafting");

    public static final KeyMapping FINISH = new KeyMapping("key.stad.finish_design", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
            "key.categories.stad");

    static boolean drafting(Minecraft mc) {
        return mc.level != null && mc.level.dimension().location().equals(DRAFTING);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (FINISH.consumeClick()) {
            if (drafting(mc) && mc.screen == null) {
                mc.setScreen(new FinishScreen());
            }
        }
    }

    public static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!drafting(mc) || mc.options.hideGui || mc.screen != null) {
            return;
        }
        String text = "Designing  ·  " + FINISH.getTranslatedKeyMessage().getString() + ": finish";
        int w = mc.font.width(text), x = (g.guiWidth() - w) / 2;
        g.fill(x - 4, 3, x + w + 4, 15, 0x80000000);
        g.drawString(mc.font, text, x, 5, 0xFFD27F, false);
    }

    /** Keep what was built, throw it away, or carry on. */
    static final class FinishScreen extends Screen {
        FinishScreen() {
            super(Component.literal("Finish designing"));
        }

        @Override
        protected void init() {
            int cx = width / 2, y = height / 2 - 10;
            addRenderableWidget(Button.builder(Component.literal("Keep it"), b -> command("stad build done"))
                    .bounds(cx - 118, y, 76, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Throw away"), b -> command("stad build cancel"))
                    .bounds(cx - 38, y, 76, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Keep building"), b -> onClose())
                    .bounds(cx + 42, y, 76, 20).build());
        }

        private void command(String c) {
            onClose();
            if (minecraft.player != null) {
                minecraft.player.connection.sendCommand(c);
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int cx = width / 2, y = height / 2 - 10;
            g.drawCenteredString(font, "Done designing?", cx, y - 26, 0xFFD27F);
            g.drawCenteredString(font, "Keep it makes what you built the table's design.", cx, y - 14, 0xC0C0C0);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
