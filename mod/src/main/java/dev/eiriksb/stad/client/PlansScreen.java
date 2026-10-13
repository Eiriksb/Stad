package dev.eiriksb.stad.client;

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

/** The building plans: pick a building, turn it, preview its outline, plan it. */
public class PlansScreen extends Screen {
    private static final int W = 320;
    private final PlansPayload plans;
    private int selected;
    private Rotation rotation;
    private Button turn;
    /** -1: the building's own materials, else an index into the palettes */
    private int palette = -1;

    public PlansScreen(PlansPayload plans) {
        super(Component.literal("Building plans"));
        this.plans = plans;
        this.rotation = plans.rotation();
    }

    private int left() {
        return (width - W) / 2;
    }

    /** the tab: which kind of building the list shows */
    private String group;
    private int scroll;

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
            Button tab = addRenderableWidget(Button.builder(Component.literal(g), b -> {
                group = g;
                scroll = 0;
                selected = inGroup().getFirst();
                rebuildWidgets();
            }).bounds(x + i * (tw + 4), 22, tw, 18).build());
            tab.active = !g.equals(group);
        }
        java.util.List<Integer> list = inGroup();
        int rows = Math.max(1, (listBottom() - 44) / 22);
        scroll = Math.max(0, Math.min(scroll, list.size() - rows));
        int y = 44;
        for (int k = scroll; k < Math.min(list.size(), scroll + rows); k++) {
            int index = list.get(k);
            Button b = addRenderableWidget(Button.builder(Component.literal(plans.options().get(index).name()), btn -> {
                selected = index;
                rebuildWidgets();
            }).bounds(x, y, 110, 20).build());
            b.active = index != selected;
            y += 22;
        }
        int by = height - 30;
        turn = addRenderableWidget(Button.builder(doorLabel(), b -> {
            rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
            b.setMessage(doorLabel());
        }).bounds(x, by, 100, 20).build());
        if (!plans.palettes().isEmpty() && !"Fields".equals(group)) {
            addRenderableWidget(Button.builder(styleLabel(), b -> {
                palette = palette + 1 >= plans.palettes().size() ? -1 : palette + 1;
                b.setMessage(styleLabel());
            }).bounds(x, by - 24, 110, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Preview"), b -> send(true)).bounds(x + 106, by, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Plan it").withStyle(ChatFormatting.GREEN), b -> {
            send(false);
            onClose();
        }).bounds(x + 182, by, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + 258, by, 62, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < left() + 114) {
            scroll -= (int) Math.signum(scrollY);
            rebuildWidgets();
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
        PacketDistributor.sendToServer(new PlanActionPayload(plans.colony(), plans.options().get(selected).key(), plans.at(), rotation, preview,
                palette < 0 ? "" : plans.palettes().get(palette)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left() + 120;
        g.drawString(font, Component.literal("Building plans").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), left(), 8, 0xFFFFFF);
        if (plans.options().isEmpty()) {
            g.drawString(font, "No building packs are loaded.", x, 30, 0xAAAAAA);
            return;
        }
        PlansPayload.Option o = plans.options().get(selected);
        int y = 44;
        g.drawString(font, Component.literal(o.name()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD), x, y, 0xFFFFFF);
        y += 12;
        for (var line : font.split(Component.literal(o.purpose()), W - 120)) {
            g.drawString(font, line, x, y, 0xFFDD88);
            y += 10;
        }
        for (var line : font.split(Component.literal(o.description()), W - 120)) {
            g.drawString(font, line, x, y, 0xAAAAAA);
            y += 10;
        }
        y += 4;
        g.drawString(font, o.sizeX() + " x " + o.sizeZ() + " blocks, " + o.sizeY() + " high", x, y, 0xAAAAAA);
        y += 14;
        g.drawString(font, Component.literal("Materials").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
        y += 12;
        for (var line : font.split(Component.literal(o.materials()), W - 120)) {
            g.drawString(font, line, x, y, 0xCCCCCC);
            y += 10;
        }
        y += 6;
        for (var line : font.split(Component.literal("Fields".equals(o.group()) ? "Centred on the block you clicked, level with it."
                : "Centred on the block you clicked, floor level with it. Builders take the "
                + "materials from the town hall." + (palette < 0 ? "" : " In the " + plans.palettes().get(palette)
                + " style, walls, roof, floor and frame are built in its materials instead.")), W - 120)) {
            g.drawString(font, line, x, y, 0x888888);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
