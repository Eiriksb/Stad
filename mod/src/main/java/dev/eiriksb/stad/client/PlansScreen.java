package dev.eiriksb.stad.client;

import dev.eiriksb.stad.client.ui.StadButton;
import dev.eiriksb.stad.client.ui.Theme;
import dev.eiriksb.stad.net.PlanActionPayload;
import dev.eiriksb.stad.net.PlansPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The building plans: pick a building, turn it, preview it (and move it about: {@link BuildingPlacer}), plan it. For an
 * area marked out with the plans, it says what goes in it, and offers buildings drawn up to fill it.
 */
public class PlansScreen extends Screen {
    private static final int W = 340;
    private final PlansPayload plans;
    private int selected;
    private Rotation rotation;
    private Button turn;
    /** -1: the building's own materials, else an index into the palettes */
    private int palette = -1;
    /** what was chosen last time (the building's key, and the style's name: "" its own), to start on next time */
    private static String lastKey = "", lastPalette = "";

    public PlansScreen(PlansPayload plans) {
        super(Component.literal(plans.area() == null ? "Building plans" : "Building plans for the marked area (" + plans.area().size() + ")"));
        this.plans = plans;
        this.rotation = plans.rotation();
        palette = plans.palettes().indexOf(lastPalette);
        for (int i = 0; i < plans.options().size(); i++) {
            if (plans.options().get(i).key().equals(lastKey) && plans.options().get(i).goesIn()) {
                selected = i;
            }
        }
        if (plans.area() != null && !plans.options().isEmpty()) {
            if (!plans.options().get(selected).key().equals(lastKey)) {
                selected = plans.options().stream().filter(o -> o.fit().equals(PlansPayload.FILLS)).findFirst()
                        .map(o -> plans.options().indexOf(o)).orElse(0);
            }
            suit();
        }
    }

    /** Turns the chosen building so it goes in the area, when it only goes one way. */
    private void suit() {
        PlansPayload.Option o = plans.options().get(selected);
        if (!o.goesIn(rotation) && o.goesIn(rotation.getRotated(Rotation.CLOCKWISE_90))) {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        }
    }

    private int left() {
        return (width - W) / 2;
    }

    /** the tab: which kind of building the list shows */
    private String group;
    private int scroll;
    /** how far the details on the right are scrolled (pixels), and how tall they were last drawn */
    private int detailScroll, detailHeight;

    private java.util.List<String> groups() {
        return plans.options().stream().map(PlansPayload.Option::group).distinct().toList();
    }

    private java.util.List<Integer> inGroup() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < plans.options().size(); i++) {
            if (plans.options().get(i).group().equals(group)) {
                out.add(i);
            }
        }
        return out;
    }

    private int listBottom() {
        return height - 30 - (plans.palettes().isEmpty() ? 4 : 28);
    }

    @Override
    protected void init() {
        int x = left();
        if (group == null && !plans.options().isEmpty()) {
            group = plans.options().get(selected).group();
        }
        java.util.List<String> groups = groups();
        int tw = groups.isEmpty() ? 0 : Math.min(80, (W - 4 * (groups.size() - 1)) / groups.size());
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            addRenderableWidget(StadButton.of(Component.literal(g), b -> {
                group = g;
                scroll = 0;
                selected = inGroup().getFirst();
                detailScroll = 0;
                suit();
                rebuildWidgets();
            }).bounds(x + i * (tw + 4), 26, tw, 20).icon(groupIcon(g)).build().tab(g.equals(group)));
        }
        java.util.List<Integer> list = inGroup();
        int rows = Math.max(1, (listBottom() - 50) / 22);
        scroll = Math.max(0, Math.min(scroll, list.size() - rows));
        int y = 51;
        for (int k = scroll; k < Math.min(list.size(), scroll + rows); k++) {
            int index = list.get(k);
            PlansPayload.Option o = plans.options().get(index);
            addRenderableWidget(StadButton.of(Component.literal(font.plainSubstrByWidth(o.name(), 104)).withStyle(o.locked().isEmpty()
                    && o.goesIn() ? ChatFormatting.RESET : ChatFormatting.DARK_GRAY), btn -> {
                selected = index;
                detailScroll = 0;
                suit();
                rebuildWidgets();
            }).bounds(x + 3, y, 110, 20).build().chosen(index == selected));
            y += 22;
        }
        int by = height - 30;
        turn = addRenderableWidget(StadButton.of(doorLabel(), b -> {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
            rebuildWidgets();
        }).bounds(x, by, 100, 20).build());
        boolean fills = !plans.options().isEmpty() && plans.options().get(selected).fit().equals(PlansPayload.FILLS);
        if (!plans.palettes().isEmpty() && !"Farms".equals(group) && !fills) {
            addRenderableWidget(StadButton.of(styleLabel(), b -> {
                palette = palette + 1 >= plans.palettes().size() ? -1 : palette + 1;
                b.setMessage(styleLabel());
            }).bounds(x, by - 24, 110, 20).build());
        }
        boolean locked = !plans.options().isEmpty() && !plans.options().get(selected).locked().isEmpty();
        boolean goes = plans.options().isEmpty() || plans.options().get(selected).goesIn(rotation);
        addRenderableWidget(StadButton.of(Component.literal("Preview"), b -> send(true)).bounds(x + 106, by, 70, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(fills ? "See it drawn up in the area before"
                        + " planning it" : "See the whole building where it would go, and move it about before planning it"))).build()).active = goes;
        locked |= !goes;
        addRenderableWidget(StadButton.of(Component.literal("Plan it").withStyle(ChatFormatting.GREEN), b -> {
            send(false);
            onClose();
        }).bounds(x + 182, by, 70, 20).build()).active = !locked;
        addRenderableWidget(StadButton.of(Component.literal("Cancel"), b -> onClose()).bounds(x + 258, by, 82, 20).build());
    }

    private static net.minecraft.resources.ResourceLocation groupIcon(String group) {
        return switch (group) {
            case "Homes" -> Theme.icon("house");
            case "Workplaces" -> Theme.job("builder");
            case "Farms" -> Theme.icon("field");
            default -> Theme.icon("bell");
        };
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        Theme.panel(g, x - 12, 2, W + 24, height - 4);
        Theme.header(g, font, title, width / 2, 7);
        Theme.inset(g, x, 46, 116, listBottom() - 44);
        Theme.inset(g, x + 120, 46, W - 120, listBottom() - 44);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < left() + 114) {
            scroll -= (int) Math.signum(scrollY);
            rebuildWidgets();
            return true;
        }
        if (mouseX >= left() + 120 && mouseY < listBottom()) {
            detailScroll = Math.max(0, Math.min(Math.max(0, detailHeight - (listBottom() - 52)), detailScroll - (int) Math.signum(scrollY) * 20));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private Component styleLabel() {
        return Component.literal("Style: " + (palette < 0 ? "its own" : plans.palettes().get(palette)));
    }

    private Component doorLabel() {
        Direction door = rotation.rotate(Direction.NORTH);
        return Component.literal("Door: " + door.getName());
    }

    private void send(boolean preview) {
        if (plans.options().isEmpty()) {
            return;
        }
        boolean fills = plans.options().get(selected).fit().equals(PlansPayload.FILLS);
        lastKey = plans.options().get(selected).key();
        lastPalette = palette < 0 ? "" : plans.palettes().get(palette);
        if (preview) {
            BuildingPlacer.area(plans.area());
        }
        PacketDistributor.sendToServer(new PlanActionPayload(plans.colony(), plans.options().get(selected).key(), plans.at(), rotation, preview,
                palette < 0 || fills ? "" : plans.palettes().get(palette), false, plans.area()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left() + 127, tw = W - 134;
        if (plans.options().isEmpty()) {
            g.drawString(font, "No building packs are loaded.", x, 52, Theme.MUTED);
            return;
        }
        PlansPayload.Option o = plans.options().get(selected);
        int top = 52, bottom = listBottom() - 3;
        g.enableScissor(x - 2, top - 3, x + tw + 2, bottom);
        int y = top - detailScroll;
        g.drawString(font, Component.literal(o.name()).withStyle(ChatFormatting.BOLD), x, y, Theme.GOLD);
        y += 13;
        if (!o.locked().isEmpty()) {
            for (var line : font.split(Component.literal("Not yet: research " + o.locked() + " at a university first (the town hall's Research tab)."), tw)) {
                g.drawString(font, line, x, y, Theme.BAD);
                y += 10;
            }
            y += 2;
        }
        for (var line : font.split(Component.literal(o.purpose()), tw)) {
            g.drawString(font, line, x, y, 0xFFFFE08A);
            y += 10;
        }
        y += 2;
        for (var line : font.split(Component.literal(o.description()), tw)) {
            g.drawString(font, line, x, y, Theme.MUTED);
            y += 10;
        }
        y += 4;
        if (!o.fit().equals(PlansPayload.FILLS)) {
            g.drawString(font, o.sizeX() + " × " + o.sizeZ() + " blocks, " + o.sizeY() + " high", x, y, Theme.FAINT);
            y += 10;
        }
        if (!o.fit().isEmpty()) {
            String fit = switch (o.fit()) {
                case PlansPayload.FILLS -> "Drawn up to fill the marked area: turn it to choose which way its door faces.";
                case PlansPayload.FITS -> "Fits in the marked area: preview it to move it about inside.";
                case PlansPayload.STRAIGHT -> "Fits in the marked area with its door facing north or south.";
                case PlansPayload.TURNED -> "Fits in the marked area with its door facing east or west.";
                default -> o.fit();
            };
            for (var line : font.split(Component.literal(fit), tw)) {
                g.drawString(font, line, x, y, o.goesIn(rotation) ? Theme.GOOD : Theme.BAD);
                y += 10;
            }
        }
        y += 4;
        Theme.heading(g, font, "Materials", x, y, tw);
        y += 13;
        for (var line : font.split(Component.literal(o.materials()), tw)) {
            g.drawString(font, line, x, y, Theme.TEXT);
            y += 10;
        }
        y += 6;
        String where = o.fit().equals(PlansPayload.FILLS) ? "" : plans.area() != null
                ? "In the middle of the marked area, floor level with where you clicked. " : "Centred on the block you clicked, "
                + ("Farms".equals(o.group()) ? "level with it. " : "floor level with it. ");
        for (var line : font.split(Component.literal("Farms".equals(o.group()) ? where + "Sneak and right-click two corners with the plans to mark"
                + " out a field, pen or grove of any size."
                : where + "Builders take the materials from the town hall." + (palette < 0 || o.fit().equals(PlansPayload.FILLS) ? "" : " In the "
                + plans.palettes().get(palette) + " style, walls, roof, floor and frame are built in its materials instead.")), tw)) {
            g.drawString(font, line, x, y, Theme.FAINT);
            y += 10;
        }
        g.disableScissor();
        detailHeight = y + detailScroll - top;
        Theme.scrollbar(g, x + tw + 1, top - 3, bottom - top + 3, detailScroll, bottom - top, detailHeight);
        int rows = Math.max(1, (listBottom() - 50) / 22);
        Theme.scrollbar(g, left() + 116 - 7, 48, listBottom() - 48, scroll, rows, inGroup().size());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
