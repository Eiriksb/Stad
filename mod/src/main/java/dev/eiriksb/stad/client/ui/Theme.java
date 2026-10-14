package dev.eiriksb.stad.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.eiriksb.stad.Stad;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Stad's look (art/krita, painted by tools/art/paint.py): dark walnut panels with brass trim round dark felt, brass
 * title plates, wells for lists, and icons for jobs and states. Client side.
 */
public final class Theme {
    private Theme() {}

    public static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, name);
    }

    public static final ResourceLocation PANEL = sprite("panel"), INSET = sprite("inset"), HEADER = sprite("header"),
            DIVIDER = sprite("divider"), SCROLLER = sprite("scroller"), SCROLLER_TRACK = sprite("scroller_track"), SLOT = sprite("slot"),
            SLOT_SELECTED = sprite("slot_selected");

    /** text on felt */
    public static final int TEXT = 0xFFEDE2C8;
    /** less important text */
    public static final int MUTED = 0xFFA8987C;
    /** quiet hints */
    public static final int FAINT = 0xFF7A6D5A;
    public static final int GOLD = 0xFFF0D27A;
    public static final int GOOD = 0xFF8FD47F;
    public static final int BAD = 0xFFFF8A70;
    public static final int WARN = 0xFFF0C050;
    /** lettering on a brass plate */
    public static final int ON_BRASS = 0xFF2B1D14;

    private static final Set<String> JOBS = Set.of("builder", "farmer", "lumberjack", "courier", "guard", "cook", "fisher", "shepherd", "miner",
            "smith", "healer", "merchant", "teacher", "carpenter", "mason", "weaver", "beekeeper", "gatherer");

    /** A job's 16 by 16 icon (a question mark for no job). */
    public static ResourceLocation job(String job) {
        return sprite("icon/job/" + (job != null && JOBS.contains(job) ? job : "none"));
    }

    /** A 16 by 16 icon: "house", "people", "person", "bell", "field", "fence", "sapling". */
    public static ResourceLocation icon(String name) {
        return sprite("icon/" + name);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        blit(g, PANEL, x, y, w, h);
    }

    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        blit(g, INSET, x, y, w, h);
    }

    public static void blit(GuiGraphics g, ResourceLocation sprite, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        g.blitSprite(sprite, x, y, w, h);
    }

    /** A 16 by 16 icon at x, y. */
    public static void icon(GuiGraphics g, ResourceLocation icon, int x, int y) {
        blit(g, icon, x, y, 16, 16);
    }

    /** A 9 by 9 icon ("check", "cross", "warn", "heart", "food", "moon", "star"), to sit in a line of text. */
    public static void small(GuiGraphics g, String name, int x, int y) {
        blit(g, sprite("icon/" + name), x, y, 9, 9);
    }

    /** A brass plate with dark lettering, centred on {@code cx}; returns its width. */
    public static int header(GuiGraphics g, Font font, Component text, int cx, int y) {
        int w = font.width(text) + 20;
        blit(g, HEADER, cx - w / 2, y, w, 16);
        g.drawString(font, text, cx - font.width(text) / 2, y + 4, ON_BRASS, false);
        return w;
    }

    public static void divider(GuiGraphics g, int x, int y, int w) {
        blit(g, DIVIDER, x, y, w, 5);
    }

    /** A brass scroll bar on a dark track, when there's more than fits. */
    public static void scrollbar(GuiGraphics g, int x, int top, int height, int scroll, int visible, int total) {
        if (total <= visible) {
            return;
        }
        blit(g, SCROLLER_TRACK, x, top, 6, height);
        int bar = Math.max(12, height * visible / total);
        int at = top + (height - bar) * scroll / Math.max(1, total - visible);
        blit(g, SCROLLER, x, at, 6, bar);
    }

    /** A heading in gold with a divider under it, across {@code w}. */
    public static void heading(GuiGraphics g, Font font, String text, int x, int y, int w) {
        g.drawString(font, text, x, y, GOLD);
        divider(g, x + font.width(text) + 6, y + 2, Math.max(0, w - font.width(text) - 6));
    }
}
