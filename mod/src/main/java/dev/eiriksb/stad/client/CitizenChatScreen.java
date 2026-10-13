package dev.eiriksb.stad.client;

import dev.eiriksb.stad.net.CitizenLinePayload;
import dev.eiriksb.stad.net.CitizenScreenPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talking to a citizen face to face (PLAN.md §7): who they are and how they're doing, what you've said to each other,
 * a box to type in, and buttons for the orders you may give. Replies, and what you say with your voice, appear as they
 * come; the citizen speaks them aloud as usual.
 */
public class CitizenChatScreen extends Screen {
    private static final int W = 360;
    private static final int LINE = 10;
    private final CitizenScreenPayload view;
    private final List<CitizenScreenPayload.Line> lines;
    private EditBox input;
    /** lines scrolled up from the bottom */
    private int scroll;
    private int historyTop;
    private int historyBottom;

    public CitizenChatScreen(CitizenScreenPayload view) {
        super(Component.literal(view.name()));
        this.view = view;
        this.lines = new ArrayList<>(view.history());
    }

    public UUID citizen() {
        return view.citizen();
    }

    /** A new line (a reply, or the player's spoken words). */
    public void add(CitizenScreenPayload.Line line) {
        lines.add(line);
        scroll = 0;
    }

    private int left() {
        return (width - W) / 2;
    }

    @Override
    protected void init() {
        int x = left();
        int by = height - 26;
        input = new EditBox(font, x, by, W - 60, 18, Component.literal("Say something"));
        input.setMaxLength(256);
        input.setHint(Component.literal(view.canTalk() ? (view.prompt().isEmpty() ? "Say something to " + first() + "..." : view.prompt())
                : first() + " can't talk right now").withStyle(ChatFormatting.DARK_GRAY));
        input.setEditable(view.canTalk());
        addRenderableWidget(input);
        setInitialFocus(input);
        Button send = addRenderableWidget(Button.builder(Component.literal("Say"), b -> send()).bounds(x + W - 56, by - 1, 56, 20).build());
        send.active = view.canTalk();
        // the orders and shortcuts, in rows above the text box
        int ay = by - 24;
        int ax = x;
        for (CitizenScreenPayload.Action a : view.actions()) {
            int w = Math.min(W, font.width(a.label()) + 12);
            if (ax + w > x + W) {
                ax = x;
                ay -= 22;
            }
            Button.Builder bb = Button.builder(Component.literal(a.label()).withStyle(s -> s.withColor(a.color())), btn -> act(a))
                    .bounds(ax, ay, w, 20);
            if (!a.tooltip().isEmpty()) {
                bb.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(a.tooltip())));
            }
            Button b = bb.build();
            addRenderableWidget(b);
            ax += w + 4;
        }
        historyBottom = (view.actions().isEmpty() ? by : ay) - 6;
        about.clear();
        for (String a : view.about()) {
            about.addAll(font.split(Component.literal(a), W));
        }
        historyTop = 22 + 12 + about.size() * LINE + 8;
    }

    /** who they are and how they're doing, wrapped to the screen */
    private final List<FormattedCharSequence> about = new ArrayList<>();

    private String first() {
        return view.name().split(" ")[0];
    }

    private void act(CitizenScreenPayload.Action a) {
        if (a.prefill()) {
            input.setValue(a.command());
            setFocused(input);
            input.moveCursorToEnd(false);
            return;
        }
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.connection.sendCommand(a.command());
        }
        onClose();
    }

    private void send() {
        String text = input.getValue().strip();
        if (text.isEmpty() || !view.canTalk()) {
            return;
        }
        PacketDistributor.sendToServer(new CitizenLinePayload(view.citizen(), true, "", text));
        String me = minecraft != null && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "You";
        add(new CitizenScreenPayload.Line(true, me, text));
        input.setValue("");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && input.isFocused()) {
            send();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll + (int) Math.signum(scrollY) * 2);
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        g.drawString(font, Component.literal(view.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), x, 10, 0xFFFFFF);
        String mood = "Happiness " + view.happiness() + "% · food " + view.hunger() + "/20 · rest " + view.rest() + "/20";
        int moodColor = view.happiness() >= 60 ? 0x77DD77 : view.happiness() >= 35 ? 0xDDDD77 : 0xDD7777;
        g.drawString(font, mood, x + W - font.width(mood), 10, moodColor);
        int y = 24;
        for (FormattedCharSequence a : about) {
            g.drawString(font, a, x, y, 0xAAAAAA);
            y += LINE;
        }
        // the conversation, newest at the bottom, wrapped
        g.fill(x - 4, historyTop - 4, x + W + 4, historyBottom + 2, 0x66000000);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (CitizenScreenPayload.Line l : lines) {
            Component c = Component.literal(l.speaker() + ": ").withStyle(l.fromPlayer() ? ChatFormatting.AQUA : ChatFormatting.GOLD)
                    .append(Component.literal(l.text()).withStyle(ChatFormatting.WHITE));
            for (FormattedCharSequence s : font.split(c, W)) {
                wrapped.add(s);
                colors.add(0xFFFFFF);
            }
            wrapped.add(FormattedCharSequence.EMPTY);
            colors.add(0);
        }
        int rows = Math.max(1, (historyBottom - historyTop) / LINE);
        scroll = Math.min(scroll, Math.max(0, wrapped.size() - rows));
        int end = wrapped.size() - scroll;
        int start = Math.max(0, end - rows);
        if (lines.isEmpty()) {
            g.drawString(font, Component.literal(view.canTalk() ? "You haven't talked yet. Type below, or speak with your voice chat key while"
                    + " looking at them." : "They aren't talking right now.").withStyle(ChatFormatting.DARK_GRAY), x, historyTop, 0x888888);
        }
        int ly = historyBottom - (end - start) * LINE;
        for (int i = start; i < end; i++) {
            g.drawString(font, wrapped.get(i), x, ly, colors.get(i));
            ly += LINE;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
