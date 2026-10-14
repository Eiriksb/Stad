package dev.eiriksb.stad.client;

import dev.eiriksb.stad.client.ui.StadButton;
import dev.eiriksb.stad.client.ui.Theme;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.ColonyActionPayload;
import dev.eiriksb.stad.net.ColonyView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The town hall: the town's name, and in tabs how it's going and what to do next, its citizens and what each is doing
 * (or what's stopping them), every job with what it needs and whether the town has it, and its buildings.
 */
public class TownHallScreen extends Screen {
    private enum Tab { TOWN("Town", "bell"), CITIZENS("Citizens", "people"), JOBS("Jobs", null), BUILDINGS("Buildings", "house");
        final String label, icon;

        Tab(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        ResourceLocation sprite() {
            return icon == null ? Theme.job("builder") : Theme.icon(icon);
        }
    }

    private static final int LINE = 10, ICON_ROW = 19;
    private static Tab lastTab = Tab.TOWN;

    private final ColonyView view;
    private EditBox nameBox;
    private boolean renaming;
    private Tab tab = lastTab;
    /** how far the list is scrolled, in pixels */
    private int scroll;
    private final List<Row> rows = new ArrayList<>();

    /**
     * A line in the list: text, its colour, how far it's indented, and an icon before it (a 16 by 16 sprite, which
     * makes the row taller, or a 9 by 9 one by name); {@code heading} draws it as a heading with a divider.
     */
    private record Row(FormattedCharSequence text, int color, int indent, ResourceLocation icon, String small, boolean heading, int height) {}

    public TownHallScreen(ColonyView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    private int w() {
        return Math.min(400, width - 40);
    }

    private int left() {
        return (width - w()) / 2;
    }

    private int top() {
        return 54;
    }

    private int bottom() {
        return height - 36;
    }

    @Override
    protected void init() {
        int x = left(), w = w();
        boolean manage = view.viewerRank().atLeast(Rank.OFFICER);
        if (renaming) {
            nameBox = new EditBox(font, width / 2 - 80, 8, 160, 16, Component.literal("Town name"));
            nameBox.setMaxLength(32);
            nameBox.setValue(view.name());
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
            addRenderableWidget(StadButton.of(Component.literal("Save"), b -> {
                PacketDistributor.sendToServer(new ColonyActionPayload(view.id(), ColonyActionPayload.Action.RENAME, nameBox.getValue()));
                renaming = false;
                rebuildWidgets();
            }).bounds(width / 2 + 84, 7, 40, 18).build());
        } else if (manage) {
            addRenderableWidget(StadButton.of(Component.literal("Rename"), b -> {
                renaming = true;
                rebuildWidgets();
            }).bounds(x + w - 52, 8, 52, 16).tooltip(Tooltip.create(Component.literal("Give the town a new name."))).build());
        }
        int tw = (w - 3 * 3) / 4;
        for (Tab t : Tab.values()) {
            boolean trouble = t == Tab.CITIZENS && view.citizens().stream().anyMatch(ColonyView.Citizen::problem);
            StadButton b = StadButton.of(Component.literal(t.label + (trouble ? " (!)" : "")), btn -> {
                tab = t;
                lastTab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(x + t.ordinal() * (tw + 3), top() - 22, tw, 20).icon(t.sprite()).build().tab(t == tab);
            addRenderableWidget(b);
        }
        int bw = (w - 3 * 4) / 4, by = height - 28;
        StadButton storage = StadButton.of(Component.literal("Storage"), b -> PacketDistributor.sendToServer(
                        new ColonyActionPayload(view.id(), ColonyActionPayload.Action.OPEN_STORAGE, ""))).bounds(x, by, bw, 20)
                .tooltip(Tooltip.create(Component.literal("The town hall's storage: building materials, food, seeds, saplings, tools and swords go here.")))
                .build();
        storage.active = view.viewerRank().atLeast(Rank.MEMBER);
        addRenderableWidget(storage);
        boolean owner = view.viewerRank() == Rank.OWNER;
        StadButton stance = StadButton.of(Component.literal(view.open() ? "Open to war" : "Peaceful").withStyle(view.open() ? ChatFormatting.RED
                        : ChatFormatting.GREEN), b -> PacketDistributor.sendToServer(new ColonyActionPayload(view.id(), ColonyActionPayload.Action.STANCE, "")))
                .bounds(x + bw + 4, by, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Peaceful towns can't be raided by players. Open towns can declare war on, and be raided by, other open towns."
                                + " Going back to peaceful takes a while.")))
                .build();
        stance.active = owner;
        addRenderableWidget(stance);
        StadButton poach = StadButton.of(Component.literal(view.poaching() ? "Poaching: on" : "Poaching: off"),
                        b -> PacketDistributor.sendToServer(new ColonyActionPayload(view.id(), ColonyActionPayload.Action.POACHING, "")))
                .bounds(x + 2 * (bw + 4), by, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Whether rival towns may lure your unhappy citizens away.")))
                .build();
        poach.active = owner;
        addRenderableWidget(poach);
        addRenderableWidget(StadButton.of(Component.literal("Done"), b -> onClose()).bounds(x + 3 * (bw + 4), by, bw, 20).build());
        buildRows();
    }

    // ---- the rows -----------------------------------------------------------------------------------------------

    private int textWidth() {
        return w() - 16;
    }

    private void heading(String text) {
        rows.add(new Row(Component.literal(text).getVisualOrderText(), Theme.GOLD, 0, null, null, true, rows.isEmpty() ? 14 : 18));
    }

    private void text(String text, int color) {
        text(text, color, 0, null);
    }

    private void text(String text, int color, int indent, String small) {
        int pad = small != null ? 12 : 0;
        boolean first = true;
        for (FormattedCharSequence line : font.split(Component.literal(text), textWidth() - indent - pad)) {
            rows.add(new Row(line, color, indent + pad, null, first ? small : null, false, LINE));
            first = false;
        }
    }

    /** A row with a 16 by 16 icon and a line of text beside it. */
    private void iconRow(ResourceLocation icon, Component text) {
        rows.add(new Row(font.split(text, textWidth() - 20).getFirst(), Theme.TEXT, 20, icon, null, false, ICON_ROW));
    }

    private void gap() {
        rows.add(new Row(FormattedCharSequence.EMPTY, 0, 0, null, null, false, 5));
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
        iconRow(Theme.icon("people"), Component.literal(view.citizens().size() + " citizens").withColor(Theme.TEXT)
                .append(Component.literal("  ·  " + view.beds() + " beds, " + Math.max(0, view.beds() - homed) + " free  ·  land " + view.radius()
                        + " blocks round the hall").withColor(Theme.MUTED)));
        text("Founded by " + view.owner() + " · you're " + (view.viewerRank() == Rank.OWNER ? "its owner" : "a " + view.viewerRank().id()),
                Theme.MUTED);
        text(view.standing(), 0xFFDDD099);
        if (!view.war().isEmpty()) {
            text(view.war(), Theme.BAD, 0, "warn");
        }
        long done = view.steps().stream().filter(ColonyView.Step::done).count();
        heading("Next steps  " + done + "/" + view.steps().size());
        boolean first = true;
        for (ColonyView.Step s : view.steps()) {
            if (s.done()) {
                text(s.text(), 0xFF6E8F64, 0, "check");
            } else {
                text(s.text(), first ? 0xFFFFE98A : Theme.TEXT, 0, first ? "star" : null);
                first = false;
            }
        }
        List<ColonyView.Citizen> stuck = view.citizens().stream().filter(ColonyView.Citizen::problem).toList();
        if (!stuck.isEmpty()) {
            heading("Can't work");
            stuck.stream().limit(6).forEach(c -> {
                iconRow(Theme.job(c.job()), Component.literal(c.name().split(" ")[0]).withColor(Theme.TEXT));
                text(c.status(), Theme.BAD, 20, null);
            });
            if (stuck.size() > 6) {
                text("...and " + (stuck.size() - 6) + " more (see Citizens)", Theme.FAINT);
            }
        }
        if (!view.needs().isEmpty()) {
            heading("The town needs");
            view.needs().forEach(n -> text(n, 0xFFDDBB77, 0, "warn"));
        }
        heading("Leaders");
        StringBuilder leaders = new StringBuilder();
        for (ColonyView.Member m : view.members()) {
            if (!leaders.isEmpty()) {
                leaders.append(", ");
            }
            leaders.append(m.name()).append(" (").append(m.rank().id()).append(')');
        }
        text(leaders.toString(), Theme.TEXT);
        if (!view.recentOrders().isEmpty()) {
            heading("Recent orders");
            view.recentOrders().forEach(o -> text(o, Theme.MUTED));
        }
    }

    private void citizens() {
        if (view.citizens().isEmpty()) {
            text("Nobody lives here yet. Place beds on the town's land and newcomers will come.", Theme.MUTED);
            return;
        }
        text("Right-click a citizen to talk to them and give them a job.", Theme.FAINT);
        for (ColonyView.Citizen c : view.citizens()) {
            gap();
            int happy = c.happiness() >= 60 ? Theme.GOOD : c.happiness() >= 35 ? Theme.WARN : Theme.BAD;
            iconRow(Theme.job(c.job()), Component.literal(c.name()).withColor(Theme.TEXT)
                    .append(Component.literal(c.job().isEmpty() ? "  no job" : "  " + c.job()).withColor(Theme.MUTED))
                    .append(Component.literal("   " + c.happiness() + "% happy").withColor(happy))
                    .append(Component.literal(" · food " + c.hunger() + " · rest " + c.rest() + (c.home() ? "" : " · homeless")).withColor(Theme.FAINT)));
            text(c.status(), c.problem() ? Theme.BAD : Theme.MUTED, 20, c.problem() ? "warn" : null);
        }
    }

    private void jobs() {
        text("What each job does and needs, and whether the town has it. Right-click a citizen to give them a job.", Theme.FAINT);
        for (ColonyView.Job j : view.jobs()) {
            gap();
            String name = j.job().substring(0, 1).toUpperCase() + j.job().substring(1);
            iconRow(Theme.job(j.job()), Component.literal(name).withColor(Theme.GOLD)
                    .append(Component.literal("   " + (j.workers().isEmpty() ? "nobody yet" : String.join(", ", j.workers()))).withColor(Theme.MUTED)));
            text(j.does(), Theme.TEXT, 20, null);
            text("Needs: " + j.needs(), Theme.MUTED, 20, null);
            if (j.problem().isEmpty()) {
                text("The town has what it needs", Theme.GOOD, 20, "check");
            } else {
                text(j.problem(), Theme.BAD, 20, "cross");
            }
        }
    }

    private void buildings() {
        if (view.buildings().isEmpty()) {
            text("Nothing planned yet. Right-click the ground with Building Plans (two paper and a coal) to plan a building or a field, design"
                    + " one at the architect's table, or tell your builder what you'd like.", Theme.MUTED);
        }
        view.buildings().forEach(b -> text(b, Theme.TEXT));
        if (!view.needs().isEmpty()) {
            heading("The town needs");
            view.needs().forEach(n -> text(n, 0xFFDDBB77, 0, "warn"));
        }
    }

    private int total() {
        return rows.stream().mapToInt(Row::height).sum();
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        Theme.panel(g, x - 14, 2, w() + 28, height - 4);
        Theme.inset(g, x - 4, top() - 2, w() + 8, bottom() - top() + 6);
        if (!renaming) {
            Theme.header(g, font, title, width / 2, 8);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left() + 4, view = bottom() - top();
        scroll = Math.max(0, Math.min(scroll, total() - view));
        g.enableScissor(left(), top(), left() + w(), bottom() + 2);
        int y = top() + 2 - scroll;
        for (Row r : rows) {
            if (y + r.height() >= top() && y < bottom()) {
                if (r.heading()) {
                    int ty = y + r.height() - 11;
                    g.drawString(font, r.text(), x, ty, r.color());
                    Theme.divider(g, x + font.width(r.text()) + 6, ty + 2, Math.max(0, textWidth() - font.width(r.text()) - 6));
                } else if (r.icon() != null) {
                    Theme.icon(g, r.icon(), x, y + 1);
                    g.drawString(font, r.text(), x + r.indent(), y + 5, r.color());
                } else {
                    if (r.small() != null) {
                        Theme.small(g, r.small(), x + r.indent() - 12, y);
                    }
                    g.drawString(font, r.text(), x + r.indent(), y, r.color());
                }
            }
            y += r.height();
        }
        g.disableScissor();
        Theme.scrollbar(g, left() + w() - 6, top(), bottom() - top(), scroll, view, total());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 24;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
