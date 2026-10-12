package dev.eiriksb.stad.client;

import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.ColonyActionPayload;
import dev.eiriksb.stad.net.ColonyView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The town hall: the town's name, who's in charge, its citizens and how they're doing. */
public class TownHallScreen extends Screen {
    private static final int W = 300;
    private static final int LINE = 11;
    private final ColonyView view;
    private EditBox nameBox;
    private int scroll;

    public TownHallScreen(ColonyView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    private int left() {
        return (width - W) / 2;
    }

    @Override
    protected void init() {
        int x = left();
        boolean manage = view.viewerRank().atLeast(Rank.OFFICER);
        nameBox = new EditBox(font, x, 20, W - 70, 18, Component.literal("Town name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(view.name());
        nameBox.setEditable(manage);
        addRenderableWidget(nameBox);
        Button rename = Button.builder(Component.literal("Rename"), b -> PacketDistributor.sendToServer(
                new ColonyActionPayload(view.id(), ColonyActionPayload.Action.RENAME, nameBox.getValue()))).bounds(x + W - 66, 19, 66, 20).build();
        rename.active = manage;
        addRenderableWidget(rename);
        Button storage = Button.builder(Component.literal("Storage"), b -> PacketDistributor.sendToServer(
                new ColonyActionPayload(view.id(), ColonyActionPayload.Action.OPEN_STORAGE, ""))).bounds(x, height - 28, 96, 20).build();
        storage.active = view.viewerRank().atLeast(Rank.MEMBER);
        addRenderableWidget(storage);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + W - 96, height - 28, 96, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = 46;
        long homed = view.citizens().stream().filter(ColonyView.Citizen::home).count();
        g.drawString(font, "Founded by " + view.owner() + " · you are " + view.viewerRank().id(), x, y, 0xAAAAAA);
        y += LINE;
        g.drawString(font, view.citizens().size() + " citizens · " + view.beds() + " beds (" + (view.beds() - homed) + " free) · land: "
                + view.radius() + " blocks around the hall", x, y, 0xAAAAAA);
        y += LINE + 4;

        g.drawString(font, Component.literal("Citizens").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
        y += LINE;
        int listTop = y;
        int rows = Math.max(1, (height - 40 - listTop - 70) / LINE);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, view.citizens().size() - rows)));
        if (view.citizens().isEmpty()) {
            g.drawString(font, "Nobody lives here yet. Place beds and newcomers will come.", x, y, 0x888888);
            y += LINE;
        }
        for (int i = scroll; i < Math.min(view.citizens().size(), scroll + rows); i++) {
            ColonyView.Citizen c = view.citizens().get(i);
            int color = c.happiness() >= 60 ? 0x77DD77 : c.happiness() >= 35 ? 0xDDDD77 : 0xDD7777;
            g.drawString(font, c.name() + (c.job().isEmpty() ? "" : " (" + c.job() + ")"), x, y, 0xFFFFFF);
            g.drawString(font, String.format("food %d · rest %d · %s", c.hunger(), c.rest(), c.home() ? "bed" : "homeless"), x + 150, y, 0xAAAAAA);
            g.drawString(font, c.happiness() + "%", x + W - 28, y, color);
            y += LINE;
        }
        y = Math.max(y, listTop + Math.min(rows, Math.max(1, view.citizens().size())) * LINE) + 4;

        if (!view.buildings().isEmpty()) {
            g.drawString(font, Component.literal("Buildings").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
            y += LINE;
            for (String b : view.buildings()) {
                g.drawString(font, font.plainSubstrByWidth(b, W), x, y, 0xCCCCCC);
                y += LINE;
            }
            y += 4;
        }
        g.drawString(font, Component.literal("Leaders").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
        y += LINE;
        StringBuilder leaders = new StringBuilder();
        for (ColonyView.Member m : view.members()) {
            if (!leaders.isEmpty()) {
                leaders.append(", ");
            }
            leaders.append(m.name()).append(" (").append(m.rank().id()).append(')');
        }
        g.drawString(font, leaders.toString(), x, y, 0xCCCCCC);
        y += LINE + 4;
        if (!view.recentOrders().isEmpty()) {
            g.drawString(font, Component.literal("Recent orders").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
            y += LINE;
            for (String o : view.recentOrders()) {
                if (y > height - 40) {
                    break;
                }
                g.drawString(font, font.plainSubstrByWidth(o, W), x, y, 0x999999);
                y += LINE;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
