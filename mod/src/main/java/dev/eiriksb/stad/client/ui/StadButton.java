package dev.eiriksb.stad.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A button in Stad's look: a walnut plank with a brass rim (or a tab), with an optional icon before its label. Made like
 * a vanilla button: {@code StadButton.of(label, press).bounds(...).tooltip(...).build()}.
 */
public class StadButton extends Button {
    public static final WidgetSprites PLANK = new WidgetSprites(Theme.sprite("button"), Theme.sprite("button_disabled"),
            Theme.sprite("button_highlighted"));
    public static final WidgetSprites TAB = new WidgetSprites(Theme.sprite("tab"), Theme.sprite("tab"), Theme.sprite("tab_highlighted"));
    public static final WidgetSprites TAB_SELECTED = new WidgetSprites(Theme.sprite("tab_selected"), Theme.sprite("tab_selected"),
            Theme.sprite("tab_selected"));

    /** a plank that stays lit: the chosen one of a list */
    public static final WidgetSprites CHOSEN = new WidgetSprites(Theme.sprite("button_highlighted"), Theme.sprite("button_highlighted"),
            Theme.sprite("button_highlighted"));

    private WidgetSprites sprites = PLANK;
    private ResourceLocation icon;

    protected StadButton(int x, int y, int w, int h, Component message, OnPress press) {
        super(x, y, w, h, message, press, DEFAULT_NARRATION);
    }

    public static Builder of(Component message, OnPress press) {
        return new Builder(message, press);
    }

    public StadButton icon(ResourceLocation icon) {
        this.icon = icon;
        return this;
    }

    /** Makes it a tab (the selected one joins the panel under it). */
    public StadButton tab(boolean selected) {
        sprites = selected ? TAB_SELECTED : TAB;
        return this;
    }

    /** Makes it the chosen one of a list (lit, with a gold label), or an ordinary button again. */
    public StadButton chosen(boolean chosen) {
        sprites = chosen ? CHOSEN : PLANK;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        g.setColor(1, 1, 1, alpha);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        boolean selectedTab = sprites == TAB_SELECTED || sprites == CHOSEN;
        g.blitSprite(sprites.get(active || selectedTab, isHoveredOrFocused()), getX(), getY(), getWidth(), getHeight());
        g.setColor(1, 1, 1, 1);
        int color = (selectedTab ? Theme.GOLD : active ? 0xF4E8CC : 0x8A7F70) & 0xFFFFFF | Mth.ceil(alpha * 255) << 24;
        // greyed out means greyed out, whatever colour the label has ("Plan it" in green)
        Component message = active || selectedTab ? getMessage() : Component.literal(getMessage().getString());
        int tw = mc.font.width(message);
        boolean hasLabel = !message.getString().isEmpty();
        int iw = getHeight() >= 18 ? 16 : 12;
        // the icon and the label when both fit; else the label (it says more) when it fits; else the icon
        boolean both = icon != null && hasLabel && tw + iw + 10 <= getWidth();
        if (icon == null || hasLabel && !both && tw + 6 <= getWidth()) {
            renderScrollingString(g, mc.font, message, getX() + 2, getY(), getX() + getWidth() - 2, getY() + getHeight(), color);
            return;
        }
        int x = both ? getX() + (getWidth() - tw - iw - 3) / 2 : getX() + (getWidth() - iw) / 2;
        int y = getY() + (getHeight() - iw) / 2;
        Theme.blit(g, icon, x, y, iw, iw);
        if (both) {
            g.drawString(mc.font, message, x + iw + 3, getY() + (getHeight() - 8) / 2, color);
        }
    }

    public static final class Builder {
        private final Component message;
        private final OnPress press;
        private int x, y, w = 150, h = 20;
        private Tooltip tooltip;
        private ResourceLocation icon;

        Builder(Component message, OnPress press) {
            this.message = message;
            this.press = press;
        }

        public Builder bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder size(int w, int h) {
            this.w = w;
            this.h = h;
            return this;
        }

        public Builder width(int w) {
            this.w = w;
            return this;
        }

        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder icon(ResourceLocation icon) {
            this.icon = icon;
            return this;
        }

        public StadButton build() {
            StadButton b = new StadButton(x, y, w, h, message, press);
            b.setTooltip(tooltip);
            b.icon = icon;
            return b;
        }
    }
}
