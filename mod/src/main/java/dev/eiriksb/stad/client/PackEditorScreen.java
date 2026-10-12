package dev.eiriksb.stad.client;

import dev.eiriksb.stad.net.EditorPayload;
import dev.eiriksb.stad.net.EditorSavePayload;
import dev.eiriksb.stad.pack.Roles;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The pack editor (PLAN.md §6.5): name a scanned building, say what kind it is, and fix the guessed block roles
 * (what a palette restyles as walls, roof, floor...), then save it into My pack.
 */
public class PackEditorScreen extends Screen {
    private static final int W = 340;
    private static final int ROW = 22;
    private static final int TOP = 96;
    private final EditorPayload editor;
    private final List<String> roles = new ArrayList<>();
    private int type;
    private int page;
    private String name;
    private EditBox nameBox;

    public PackEditorScreen(EditorPayload editor) {
        super(Component.literal("Pack editor"));
        this.editor = editor;
        this.name = editor.name();
        this.type = Math.max(0, editor.types().indexOf(editor.kind()));
        editor.rows().forEach(r -> roles.add(r.role()));
    }

    private int left() {
        return (width - W) / 2;
    }

    private int perPage() {
        return Math.max(1, (height - TOP - 36) / ROW);
    }

    private int pages() {
        return Math.max(1, (editor.rows().size() + perPage() - 1) / perPage());
    }

    @Override
    protected void init() {
        int x = left();
        nameBox = new EditBox(font, x, 24, W - 116, 18, Component.literal("Name"));
        nameBox.setMaxLength(40);
        nameBox.setValue(name);
        nameBox.setResponder(v -> name = v);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(typeLabel(), b -> {
            type = (type + 1) % editor.types().size();
            b.setMessage(typeLabel());
        }).bounds(x + W - 110, 23, 110, 20).build());
        int start = page * perPage();
        for (int i = start; i < Math.min(editor.rows().size(), start + perPage()); i++) {
            int index = i;
            int y = TOP + (i - start) * ROW;
            addRenderableWidget(Button.builder(roleLabel(index), b -> {
                Roles.Role[] all = Roles.Role.values();
                Roles.Role now = Roles.Role.of(roles.get(index)).orElse(Roles.Role.DECOR);
                roles.set(index, all[(now.ordinal() + 1) % all.length].id());
                b.setMessage(roleLabel(index));
            }).bounds(x + W - 96, y, 96, 20).build());
        }
        int by = height - 28;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(x, by, 20, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(x + 24, by, 20, 20).build());
        prev.active = page > 0;
        next.active = page < pages() - 1;
        addRenderableWidget(Button.builder(Component.literal(editor.editable() ? "Save" : "Save a copy").withStyle(ChatFormatting.GREEN), b -> save())
                .bounds(x + W - 186, by, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + W - 90, by, 90, 20).build());
    }

    private void turn(int by) {
        page = Math.max(0, Math.min(pages() - 1, page + by));
        rebuildWidgets();
    }

    private Component typeLabel() {
        return Component.literal("Kind: " + word(editor.types().get(type)));
    }

    private static String word(String type) {
        String path = ResourceLocation.parse(type).getPath();
        return switch (path) {
            case "builder_hut" -> "builder's hut";
            case "building" -> "other";
            default -> path.replace('_', ' ');
        };
    }

    private Component roleLabel(int i) {
        String role = roles.get(i);
        Roles.Role r = Roles.Role.of(role).orElse(Roles.Role.DECOR);
        boolean changed = !role.equals(editor.rows().get(i).guessed());
        return Component.literal(r.label).withStyle(changed ? ChatFormatting.YELLOW : r.restyled ? ChatFormatting.WHITE : ChatFormatting.GRAY);
    }

    private void save() {
        List<EditorPayload.Row> rows = new ArrayList<>();
        for (int i = 0; i < editor.rows().size(); i++) {
            EditorPayload.Row r = editor.rows().get(i);
            rows.add(new EditorPayload.Row(r.block(), r.guessed(), roles.get(i), r.count()));
        }
        PacketDistributor.sendToServer(new EditorSavePayload(editor.token(), name, editor.types().get(type), rows));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        g.drawString(font, Component.literal("Pack editor").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), x, 10, 0xFFFFFF);
        g.drawString(font, editor.sizeX() + " x " + editor.sizeZ() + ", " + editor.sizeY() + " high" + (editor.editable() ? "" : " · from another pack"),
                x + 90, 10, 0x888888);
        int y = 48;
        String needs = editor.needs().get(type);
        String kind = word(editor.types().get(type));
        g.drawString(font, needs.isEmpty() ? Component.literal("Has what a " + kind + " needs.").withStyle(ChatFormatting.GREEN)
                : Component.literal("A " + kind + " needs " + needs + ".").withStyle(ChatFormatting.RED), x, y, 0xFFFFFF);
        y += 12;
        g.drawString(font, "Has " + editor.contents() + ".", x, y, 0xAAAAAA);
        y += 12;
        g.drawString(font, "Markers: " + editor.markers(), x, y, 0xAAAAAA);
        y += 14;
        g.drawString(font, Component.literal("Blocks and what they are (click to change)").withStyle(ChatFormatting.GOLD), x, y, 0xFFFFFF);
        int start = page * perPage();
        for (int i = start; i < Math.min(editor.rows().size(), start + perPage()); i++) {
            EditorPayload.Row r = editor.rows().get(i);
            int ry = TOP + (i - start) * ROW;
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(r.block()));
            g.renderItem(new ItemStack(block.asItem()), x, ry + 2);
            g.drawString(font, block.getName().getString() + " x" + r.count(), x + 20, ry + 2, 0xFFFFFF);
            Roles.Role guessed = Roles.Role.of(r.guessed()).orElse(Roles.Role.DECOR);
            g.drawString(font, "guessed: " + guessed.label, x + 20, ry + 11, 0x777777);
        }
        g.drawString(font, (page + 1) + "/" + pages(), x + 50, height - 22, 0x888888);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
