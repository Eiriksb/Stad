package dev.eiriksb.stad.client;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.CitizenMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * A citizen's gear ({@link CitizenMenu}): their armour down the left, them in the middle (following the mouse), what
 * they hold in each hand, their own things on the right, what they carry for work below, and the player's inventory.
 * Drawn like the game's own containers.
 */
public final class CitizenGearScreen extends AbstractContainerScreen<CitizenMenu> {
    private static final int BG = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, SHADE = 0xFF555555, EDGE = 0xFF000000, SLOT = 0xFF8B8B8B,
            SLOT_DARK = 0xFF373737, TEXT = 0x404040;

    public CitizenGearScreen(CitizenMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = CitizenMenu.WIDTH;
        imageHeight = CitizenMenu.HEIGHT;
        inventoryLabelY = CitizenMenu.PLAYER_Y - 11;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        // the panel: a black edge, light top and left, shaded bottom and right
        g.fill(x + 1, y, x + imageWidth - 1, y + imageHeight, EDGE);
        g.fill(x, y + 1, x + imageWidth, y + imageHeight - 1, EDGE);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, LIGHT);
        g.fill(x + 3, y + 3, x + imageWidth - 1, y + imageHeight - 1, SHADE);
        g.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, BG);
        for (Slot s : menu.slots) {
            slot(g, x + s.x - 1, y + s.y - 1);
        }
        // where they stand
        int bx = x + 26, by = y + CitizenMenu.TOP, bw = 52, bh = 70;
        g.fill(bx, by, bx + bw, by + bh, SLOT_DARK);
        g.fill(bx + 1, by + 1, bx + bw, by + bh, LIGHT);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, EDGE);
        if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getEntity(menu.citizenId) instanceof CitizenEntity c) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, bx + 1, by + 1, bx + bw - 1, by + bh - 1, c.isBaby() ? 40 : 28, 0.0625f,
                    mouseX, mouseY, c);
        }
    }

    private static void slot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, SLOT_DARK);
        g.fill(x + 1, y + 1, x + 18, y + 18, LIGHT);
        g.fill(x + 1, y + 1, x + 17, y + 17, SLOT);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
        g.drawString(font, Component.literal("Their own"), CitizenMenu.OWN_X, CitizenMenu.TOP + 56, TEXT, false);
        if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getEntity(menu.citizenId) instanceof CitizenEntity c) {
            String armour = "Armour " + c.getArmorValue();
            g.drawString(font, armour, imageWidth - 8 - font.width(armour), titleLabelY, TEXT, false);
        }
        g.drawString(font, Component.literal("Carrying for work"), 8, CitizenMenu.POCKETS_Y - 11, TEXT, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
    }
}
