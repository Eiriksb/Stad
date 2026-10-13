package dev.eiriksb.stad.client;

import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.ColonyActionPayload;
import dev.eiriksb.stad.net.ColonyView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The town hall: the town's name, and in tabs how it's going and what to do next, its citizens and what each is doing
 * (or what's stopping them), every job with what it needs and whether the town has it, and its buildings.
 */
public class TownHallScreen extends Screen {
    private enum Tab { TOWN("Town"), CITIZENS("Citizens"), JOBS("Jobs"), BUILDINGS("Buildings");
        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int LINE = 10;
    private static final int TOP = 52;
    private static Tab lastTab = Tab.TOWN;

    private final ColonyView view;
    private EditBox nameBox;
    private Tab tab = lastTab;
    private int scroll;
    private final List<Row> rows = new ArrayList<>();

    private record Row(FormattedCharSequence text, int color, int indent) {}

    public TownHallScreen(ColonyView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    private int w() {
        return Math.min(380, width - 20);
    }

    private int left() {
        return (width - w()) / 2;
    }

    private int bottom() {
        return height - 30;
    }

    @Override
    protected void init() {
        int x = left(), w = w();
        boolean manage = view.viewerRank().atLeast(Rank.OFFICER);
        nameBox = new EditBox(font, x, 6, w - 70, 18, Component.literal("Town name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(view.name());
        nameBox.setEditable(manage);
        addRenderableWidget(nameBox);
        Button rename = Button.builder(Component.literal("Rename"), b -> PacketDistributor.sendToServer(
                new ColonyActionPayload(view.id(), ColonyActionPayload.Action.RENAME, nameBox.getValue()))).bounds(x + w - 66, 5, 66, 20).build();
        rename.active = manage;
        addRenderableWidget(rename);
        int tw = (w - 3 * 4) / 4;
        for (Tab t : Tab.values()) {
            String label = t.label + (t == Tab.CITIZENS && view.citizens().stream().anyMatch(ColonyView.Citizen::problem) ? " (!)" : "");
            Button b = Button.builder(Component.literal(label), btn -> {
                tab = t;
                lastTab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(x + t.ordinal() * (tw + 4), 28, tw, 18).build();
            b.active = t != tab;
            addRenderableWidget(b);
        }
        int bw = (w - 3 * 4) / 4, by = height - 25;
        Button storage = Button.builder(Component.literal("Storage"), b -> PacketDistributor.sendToServer(
                new ColonyActionPayload(view.id(), ColonyActionPayload.Action.OPEN_STORAGE, ""))).bounds(x, by, bw, 20)
                .tooltip(Tooltip.create(Component.literal("The town hall's storage: building materials, food, tools and swords go here."))).build();
        storage.active = view.viewerRank().atLeast(Rank.MEMBER);
        addRenderableWidget(storage);
        boolean owner = view.viewerRank() == Rank.OWNER;
        Button stance = Button.builder(Component.literal(view.open() ? "Open to war" : "Peaceful").withStyle(view.open() ? ChatFormatting.RED
                : ChatFormatting.GREEN), b -> PacketDistributor.sendToServer(new ColonyActionPayload(view.id(), ColonyActionPayload.Action.STANCE, "")))
                .bounds(x + bw + 4, by, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Peaceful towns can't be raided by players. Open towns can declare war on, and be raided by, other open towns."
                                + " Going back to peaceful takes a while.")))
                .build();
        stance.active = owner;
        addRenderableWidget(stance);
        Button poach = Button.builder(Component.literal(view.poaching() ? "Poaching: on" : "Poaching: off"),
                        b -> PacketDistributor.sendToServer(new ColonyActionPayload(view.id(), ColonyActionPayload.Action.POACHING, "")))
                .bounds(x + 2 * (bw + 4), by, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Whether rival towns may lure your unhappy citizens away.")))
                .build();
        poach.active = owner;
        addRenderableWidget(poach);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 3 * (bw + 4), by, bw, 20).build());
        buildRows();
    }

    private void heading(String text) {
        if (!rows.isEmpty()) {
            rows.add(new Row(FormattedCharSequence.EMPTY, 0, 0));
        }
        rows.add(new Row(Component.literal(text).withStyle(ChatFormatting.GOLD).getVisualOrderText(), 0xFFFFFF, 0));
    }

    private void text(String text, int color) {
        text(text, color, 0);
    }

    private void text(String text, int color, int indent) {
        for (FormattedCharSequence line : font.split(Component.literal(text), w() - indent)) {
            rows.add(new Row(line, color, indent));
        }
    }

    private void buildRows() {
        rows.clear();
        switch (tab) {
            case TOWN -> town();
            case CITIZENS -> citizens();
            case JOBS -> jobs();
            case BUILDINGS -> buildings();
        }
    }

    private void town() {
        long homed = view.citizens().stream().filter(ColonyView.Citizen::home).count();
        text("Founded by " + view.owner() + " · you are " + view.viewerRank().id(), 0xAAAAAA);
        text(view.citizens().size() + " citizens · " + view.beds() + " beds (" + Math.max(0, view.beds() - homed) + " free) · land: "
                + view.radius() + " blocks around the hall", 0xAAAAAA);
        text(view.standing(), 0xDDDD99);
        if (!view.war().isEmpty()) {
            text(view.war(), 0xFF6666);
        }
        long done = view.steps().stream().filter(ColonyView.Step::done).count();
        heading("Next steps (" + done + " of " + view.steps().size() + " done)");
        boolean first = true;
        for (ColonyView.Step s : view.steps()) {
            if (s.done()) {
                text("✔ " + s.text(), 0x669966, 0);
            } else {
                text((first ? "➜ " : "• ") + s.text(), first ? 0xFFFF77 : 0xCCCCCC, 0);
                first = false;
            }
        }
        long stuck = view.citizens().stream().filter(ColonyView.Citizen::problem).count();
        if (stuck > 0) {
            heading("Can't work");
            view.citizens().stream().filter(ColonyView.Citizen::problem).limit(6)
                    .forEach(c -> text(c.name().split(" ")[0] + (c.job().isEmpty() ? "" : " (" + c.job() + ")") + ": " + c.status(), 0xFF8877));
            if (stuck > 6) {
                text("...and " + (stuck - 6) + " more (see Citizens)", 0x999999);
            }
        }
        if (!view.needs().isEmpty()) {
            heading("The town needs");
            view.needs().forEach(n -> text(n, 0xDDBB77));
        }
        heading("Leaders");
        StringBuilder leaders = new StringBuilder();
        for (ColonyView.Member m : view.members()) {
            if (!leaders.isEmpty()) {
                leaders.append(", ");
            }
            leaders.append(m.name()).append(" (").append(m.rank().id()).append(')');
        }
        text(leaders.toString(), 0xCCCCCC);
        if (!view.recentOrders().isEmpty()) {
            heading("Recent orders");
            view.recentOrders().forEach(o -> text(o, 0x999999));
        }
    }

    private void citizens() {
        if (view.citizens().isEmpty()) {
            text("Nobody lives here yet. Place beds on the town's land and newcomers will come.", 0x888888);
            return;
        }
        text("Right-click a citizen to talk to them and give them a job.", 0x888888);
        for (ColonyView.Citizen c : view.citizens()) {
            rows.add(new Row(FormattedCharSequence.EMPTY, 0, 0));
            int happy = c.happiness() >= 60 ? 0x77DD77 : c.happiness() >= 35 ? 0xDDDD77 : 0xDD7777;
            text(c.name() + (c.job().isEmpty() ? "" : " · " + c.job()) + "   " + c.happiness() + "% happy · food " + c.hunger() + " · rest " + c.rest()
                    + (c.home() ? "" : " · homeless"), happy);
            text(c.status(), c.problem() ? 0xFF8877 : 0xAAAAAA, 8);
        }
    }

    private void jobs() {
        text("What each job does and needs, and whether the town has it. Right-click a citizen to give them a job.", 0x888888);
        for (ColonyView.Job j : view.jobs()) {
            rows.add(new Row(FormattedCharSequence.EMPTY, 0, 0));
            String name = j.job().substring(0, 1).toUpperCase() + j.job().substring(1);
            text(name + " · " + (j.workers().isEmpty() ? "nobody" : String.join(", ", j.workers())), 0xFFDD88);
            text(j.does(), 0xBBBBBB, 8);
            text("Needs: " + j.needs(), 0x999999, 8);
            text(j.problem().isEmpty() ? "✔ The town has what it needs" : "✘ " + j.problem(), j.problem().isEmpty() ? 0x77CC77 : 0xFF8877, 8);
        }
    }

    private void buildings() {
        if (view.buildings().isEmpty()) {
            text("Nothing planned yet. Right-click the ground with Building Plans (two paper and a coal) to plan a building or a field,"
                    + " or tell your builder what you'd like.", 0x888888);
        }
        view.buildings().forEach(b -> text(b, 0xCCCCCC));
        if (!view.needs().isEmpty()) {
            heading("The town needs");
            view.needs().forEach(n -> text(n, 0xDDBB77));
        }
    }

    private int visibleRows() {
        return Math.max(1, (bottom() - TOP) / LINE);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows()));
        g.fill(x - 6, TOP - 4, x + w() + 8, bottom() + 2, 0xA0000000);
        g.enableScissor(x, TOP, x + w(), bottom());
        int y = TOP;
        for (int i = scroll; i < rows.size() && y < bottom(); i++) {
            Row r = rows.get(i);
            g.drawString(font, r.text(), x + r.indent(), y, r.color());
            y += LINE;
        }
        g.disableScissor();
        if (rows.size() > visibleRows()) {
            int track = bottom() - TOP;
            int bar = Math.max(10, track * visibleRows() / rows.size());
            int at = TOP + (track - bar) * scroll / Math.max(1, rows.size() - visibleRows());
            g.fill(x + w() + 3, at, x + w() + 5, at + bar, 0x88FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 3;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
