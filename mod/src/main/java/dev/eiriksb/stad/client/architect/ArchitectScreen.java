package dev.eiriksb.stad.client.architect;

import dev.eiriksb.stad.client.ui.StadButton;
import dev.eiriksb.stad.client.ui.Theme;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.eiriksb.stad.architect.Yard;
import dev.eiriksb.stad.architect.DesignCheck;
import dev.eiriksb.stad.architect.Project;
import dev.eiriksb.stad.architect.Tables;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.net.TablePayloads;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Materials;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The architect's table (PLAN.md §6.8): the site in 3D on the left, with the tools and blocks underneath, and the
 * building's kind, the generator's settings, what it still needs and what it takes on the right.
 *
 * <p>In the view: drag to turn, scroll to zoom, middle- or right-drag to pan, shift+scroll (or Page Up/Down) to cut
 * the view off at a layer. Click to use the tool, right-click to remove, middle-click to pick a block.
 */
public class ArchitectScreen extends Screen {
    /** the top bar's height, and the drawer's width */
    private static final int TOP = 22, DRAWER = 150;
    private static final int SLOT = 18;
    private static final List<String> MARKERS = List.of("stad:work", "stad:sit", "stad:bed", "stad:storage", "stad:door", "stad:shaft", "stad:post",
            "stad:fish");
    private static final Direction[] DOORS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    enum Tool {
        PLACE("Place", "Click a face to put a block against it. R turns it."),
        BREAK("Break", "Click a block to take it away."),
        PICK("Pick", "Click a block to put it in your hand (middle-click does this with any tool)."),
        BOX("Box", "Click two corners to fill a box (right-click two corners to empty one)."),
        WALLS("Walls", "Click two corners for four walls between them."),
        FLOOR("Floor", "Click two corners for a floor, level with the first."),
        LINE("Line", "Click two ends for a line of blocks."),
        REPLACE("Replace", "Click a block to swap every block like it for the one in your hand (right-click: remove them)."),
        COPY("Copy", "Click two corners to copy what's between them."),
        PASTE("Paste", "Click a face to paste what you copied there. R turns it."),
        MARK("Mark", "Click a space to mark it for the town. Click Mark again for another kind."),
        ROAD("Road", "Click along the ground: each click draws on from the last. Right-click: stop, or take the road off.");

        final String label, hint;

        Tool(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    private record Hit(BlockPos cell, Direction face, double fracY) {}

    private record Change(int key, BlockState before, BlockState after) {}

    private record MarkChange(int key, String before, String after) {}

    private record Op(List<Change> blocks, List<MarkChange> marks) {}

    private final TablePayloads.Open open;
    private final DesignModel model;
    private final Map<String, Integer> stock = new HashMap<>();
    private DesignRenderer renderer;

    private float yaw, pitch = 32, dist;
    private final Vector3f center = new Vector3f();
    private int cut;

    private Tool tool = Tool.PLACE;
    private final BlockState[] hotbar = new BlockState[9];
    private int slot;
    private int turns;
    private boolean mirrorX, mirrorZ;
    private BlockPos corner;
    private boolean cornerErase;
    private Map<BlockPos, BlockState> clip;
    private BlockPos clipSize;
    private int markKind;
    private final Deque<Op> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    private int typeIndex, style, floors = 1, roof, door, beds = 2;
    private final Set<String> features = new LinkedHashSet<>();
    /** a field's, pen's or grove's settings ({@link Yard}) */
    private int crop, water, fence, animal, tree, spacing = Yard.DEFAULT.spacing(), depth = Yard.DEFAULT.depth(), wall,
            wallHeight = Yard.DEFAULT.height();
    private boolean path = Yard.DEFAULT.path(), extra = Yard.DEFAULT.extra();
    /** a market square's and a road's: what it's paved with, the stalls' awnings, how wide the road is */
    private int paving, awning, roadWidth = Yard.DEFAULT.width();
    /** where the road tool's next stretch starts (the last click), or null */
    private BlockPos roadFrom;
    /** the tools on the bottom row (the road tool only for a road) */
    private List<Tool> shownTools = List.of(Tool.values());
    /** the drawer's land settings buttons, and which setting each is */
    private final Map<Button, String> yardButtons = new LinkedHashMap<>();
    /** whether the list of kinds is open */
    private boolean choosingType;

    private String status = "";
    private boolean statusError;
    private long statusAt = System.currentTimeMillis();
    private DesignCheck.Result check;
    private Map<Item, Integer> materials = Map.of();
    private boolean dirtyCheck = true;
    private String sentName;
    private int nameTicks;
    private long startOverAsked;
    private boolean siteOpen;
    private boolean replaced;
    private String name, describeText = "";

    private boolean picking, focusSearch;
    private int pickScroll;
    private List<Block> pickList = List.of();

    private EditBox nameBox, describeBox, cornerA, cornerB, searchBox;
    private final List<Button> toolButtons = new ArrayList<>();
    private Button typeButton, styleButton, floorsButton, roofButton, doorButton, bedsButton, startOverButton, mirrorXButton, mirrorZButton;
    private final List<Button> featureButtons = new ArrayList<>();
    private final List<Button> editOnly = new ArrayList<>();
    private int vw, vh, toolsTop, hotbarY, drawerInfoY;
    private boolean drawer;
    private final long openedAt = System.currentTimeMillis();
    private long toolAt;

    private int dragButton = -1;
    private double dragX, dragY;
    private boolean moved;
    private Hit hover;

    public ArchitectScreen(TablePayloads.Open open) {
        super(Component.literal("Architect's table"));
        this.open = open;
        this.model = open.hasSite() ? new DesignModel(open.terrain(), open.min(), open.sx(), open.sy(), open.sz()) : null;
        for (int i = 0; i < Math.min(open.stockItems().size(), open.stockCounts().length); i++) {
            stock.put(open.stockItems().get(i), open.stockCounts()[i]);
        }
        String typeId = open.design().type();
        typeIndex = Math.max(0, open.types().indexOf(typeId));
        if (model != null) {
            model.load(open.design());
            model.near = java.util.Set.copyOf(open.near());
            cut = model.gh() - 1;
            door = Math.max(0, List.of(DOORS).indexOf(Direction.byName(model.door)));
            yaw = new float[]{180, 270, 0, 90}[door] + 30;
            center.set(DesignModel.M + model.sx / 2f, DesignModel.M + (model.floorY >= 0 ? model.floorY + 2 : model.sy / 3f), DesignModel.M + model.sz / 2f);
            dist = Math.max(model.sx, Math.max(model.sz, model.sy)) * 1.7f + 6;
            readSpec(model.spec);
            readYard(model.yard);
        }
        siteOpen = model == null;
        if (typeId.equals("stad:road")) {
            tool = Tool.ROAD;
        }
        name = model != null ? model.name : open.design().name();
        defaultHotbar();
        status = open.message();
        if (!open.canEdit()) {
            status = open.editor().isEmpty() ? "You can look, but only officers can change the design."
                    : "Watching: " + open.editor() + " is using the table.";
        }
    }

    public BlockPos table() {
        return open.table();
    }

    // ---- set up ------------------------------------------------------------------------------------------------

    private void readSpec(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        BuildSpec s = BuildSpec.parse(json);
        floors = s.floors();
        roof = Math.max(0, BuildSpec.ROOFS.indexOf(s.roof()));
        beds = s.beds();
        features.clear();
        features.addAll(s.features());
        for (int i = 0; i < open.styles().size(); i++) {
            if (open.styles().get(i).walls().equals(s.walls()) && open.styles().get(i).roof().equals(s.roofMaterial())) {
                style = i;
            }
        }
    }

    private void readYard(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        Yard y = Yard.parse(json);
        crop = Math.max(0, Yard.CROPS.indexOf(y.crop()));
        water = Math.max(0, Yard.WATERS.indexOf(y.water()));
        fence = Math.max(0, Yard.FENCES.indexOf(y.fence()));
        animal = Math.max(0, Yard.ANIMALS.indexOf(y.animal()));
        tree = Math.max(0, Yard.TREES.indexOf(y.tree()));
        spacing = y.spacing();
        path = y.path();
        extra = y.extra();
        depth = y.depth();
        wall = Math.max(0, Yard.WALLS.indexOf(y.wall()));
        wallHeight = y.height();
        paving = Math.max(0, Yard.PAVINGS.indexOf(y.paving()));
        awning = Math.max(0, Yard.AWNINGS.indexOf(y.awning()));
        roadWidth = y.width();
    }

    private Yard yard() {
        return new Yard(Yard.CROPS.get(crop), Yard.WATERS.get(water), Yard.FENCES.get(fence), path, Yard.ANIMALS.get(animal), Yard.TREES.get(tree),
                spacing, extra, depth, Yard.WALLS.get(wall), wallHeight, Yard.PAVINGS.get(paving), Yard.AWNINGS.get(awning), roadWidth);
    }

    private String typeId() {
        return open.types().isEmpty() ? "stad:building" : open.types().get(typeIndex);
    }

    private boolean yardType() {
        return Yard.is(typeId());
    }

    private boolean roadType() {
        return typeId().equals("stad:road");
    }

    private TablePayloads.Style currentStyle() {
        return open.styles().isEmpty() ? new TablePayloads.Style("Plain", "oak", "none", "spruce", "oak") : open.styles().get(style);
    }

    private void defaultHotbar() {
        Block[] land = switch (typeId()) {
            case "stad:field" -> new Block[]{Blocks.FARMLAND, Blocks.WATER, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.DIRT_PATH,
                    fenceBlock(), Blocks.OAK_FENCE_GATE, Blocks.LANTERN};
            case "stad:pen" -> new Block[]{fenceBlock(), Blocks.OAK_FENCE_GATE, Blocks.HAY_BLOCK, Blocks.WATER_CAULDRON, Blocks.GRASS_BLOCK,
                    Blocks.SPRUCE_SLAB, Blocks.LANTERN, Blocks.DIRT_PATH, Blocks.WATER};
            case "stad:grove" -> new Block[]{Blocks.OAK_SAPLING, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING, Blocks.CHERRY_SAPLING,
                    Blocks.ACACIA_SAPLING, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH, Blocks.LANTERN};
            case "stad:quarry" -> new Block[]{fenceBlock(), Blocks.OAK_FENCE_GATE, Blocks.LANTERN, Blocks.TORCH, Blocks.COBBLESTONE, Blocks.LADDER,
                    Blocks.STONE_BRICKS, Blocks.CHEST, Blocks.DIRT};
            case "stad:dock" -> new Block[]{Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_FENCE, Blocks.LANTERN, Blocks.SPRUCE_SLAB,
                    Blocks.BARREL, Blocks.LADDER, Blocks.SPRUCE_TRAPDOOR, Blocks.WATER};
            case "stad:post" -> new Block[]{Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.LADDER, Blocks.LANTERN,
                    Blocks.SPRUCE_SLAB, Blocks.SPRUCE_STAIRS, Blocks.BELL, Blocks.TORCH};
            case "stad:wall" -> new Block[]{Blocks.SPRUCE_LOG, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.COBBLESTONE_WALL, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_FENCE_GATE, Blocks.LANTERN, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB};
            case "stad:stalls" -> new Block[]{fenceBlock(), Blocks.BARREL, Blocks.SPRUCE_SLAB, Blocks.RED_WOOL, Blocks.WHITE_WOOL, Blocks.BELL,
                    Blocks.LANTERN, Blocks.COBBLESTONE, Blocks.FLOWER_POT};
            case "stad:road" -> new Block[]{Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS,
                    Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.OAK_FENCE, Blocks.LANTERN};
            default -> null;
        };
        if (land != null) {
            for (int i = 0; i < 9; i++) {
                hotbar[i] = land[i].defaultBlockState();
            }
            return;
        }
        TablePayloads.Style st = currentStyle();
        Materials.Material walls = Materials.getOr(st.walls(), "oak"), roofM = Materials.getOr(st.roof(), "spruce"),
                floorM = Materials.getOr(st.floor(), "oak");
        Materials.Material frame = Materials.get(st.frame()).orElse(walls);
        Block[] b = {walls.full(), frame.log() != null ? frame.log() : frame.full(), roofM.stairs(), roofM.slab(), floorM.full(),
                Blocks.GLASS_PANE, walls.door() != null ? walls.door() : Blocks.OAK_DOOR, Blocks.LANTERN, Blocks.CHEST};
        for (int i = 0; i < 9; i++) {
            hotbar[i] = (b[i] != null ? b[i] : Blocks.OAK_PLANKS).defaultBlockState();
        }
    }

    private Block fenceBlock() {
        String f = Yard.FENCES.get(fence);
        Block b = f.equals("cobblestone") ? Blocks.COBBLESTONE_WALL
                : BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace((f.equals("none") ? "oak" : f) + "_fence"));
        return b == Blocks.AIR ? Blocks.OAK_FENCE : b;
    }

    /** What to show for a block in a slot: water as a bucket (it has no item of its own). */
    static ItemStack icon(Block b) {
        return new ItemStack(b == Blocks.WATER ? Items.WATER_BUCKET : b == Blocks.WATER_CAULDRON ? Items.CAULDRON : b.asItem());
    }

    @Override
    protected void init() {
        toolButtons.clear();
        featureButtons.clear();
        yardButtons.clear();
        editOnly.clear();
        styleButton = floorsButton = roofButton = doorButton = bedsButton = startOverButton = null;
        describeBox = null;
        vw = width;
        // the bottom: the tools on one row, the hotbar and the editing buttons on the next
        shownTools = java.util.Arrays.stream(Tool.values()).filter(t -> t != Tool.ROAD || roadType()).toList();
        if (!shownTools.contains(tool)) {
            tool = Tool.PLACE;
        }
        List<String> tools = new ArrayList<>();
        for (Tool t : shownTools) {
            tools.add(t == Tool.MARK ? "Mark" : t.label);
        }
        List<int[]> toolSpots = flow(tools, 4, 4);
        int toolRows = toolSpots.get(toolSpots.size() - 1)[1] + 1;
        int hx = 4 + 9 * SLOT + 6;
        List<String> edits = List.of("Blocks...", "Undo", "Redo", "Mirror X", "Mirror Z");
        List<int[]> editSpots = flow(edits, hx, hx);
        int editRows = editSpots.get(editSpots.size() - 1)[1] + 1;
        vh = height - toolRows * 18 - Math.max(SLOT, editRows * 18) - 8;
        toolsTop = vh + 3;
        hotbarY = toolsTop + toolRows * 18 + 1;
        for (int i = 0; i < tools.size(); i++) {
            Tool t = shownTools.get(i);
            int[] s = toolSpots.get(i);
            Button b = addRenderableWidget(StadButton.of(Component.literal(t.label), btn -> chooseTool(t)).bounds(s[0], toolsTop + s[1] * 18, s[2], 16)
                    .tooltip(Tooltip.create(Component.literal(t.hint))).build());
            toolButtons.add(b);
            editOnly.add(b);
        }
        for (int i = 0; i < edits.size(); i++) {
            String l = edits.get(i);
            int[] s = editSpots.get(i);
            Button b = addRenderableWidget(StadButton.of(Component.literal(l), btn -> {
                switch (l) {
                    case "Blocks..." -> setPicking(!picking);
                    case "Mirror X" -> mirrorX = !mirrorX;
                    case "Mirror Z" -> mirrorZ = !mirrorZ;
                    case "Undo" -> undo();
                    default -> redo();
                }
                refreshLabels();
            }).bounds(s[0], hotbarY + 1 + s[1] * 18, s[2], 16).tooltip(Tooltip.create(Component.literal(switch (l) {
                case "Blocks..." -> "All the blocks, to put in your hand (E). Right-click a slot for this too.";
                case "Undo" -> "Undo (Ctrl+Z)";
                case "Redo" -> "Redo (Ctrl+Y)";
                default -> "Build mirrored across the middle of the site, " + (l.endsWith("X") ? "east to west" : "north to south") + ".";
            }))).build());
            editOnly.add(b);
            if (l.equals("Mirror X")) {
                mirrorXButton = b;
            } else if (l.equals("Mirror Z")) {
                mirrorZButton = b;
            }
        }
        // the top bar: what it is on the left, what to do with it on the right
        nameBox = new EditBox(font, 4, 3, 80, 16, Component.literal("Name"));
        nameBox.setMaxLength(40);
        nameBox.setValue(name);
        if (sentName == null) {
            sentName = name;
        }
        nameBox.setResponder(v -> name = v);
        nameBox.setTooltip(Tooltip.create(Component.literal("The building's name")));
        addRenderableWidget(nameBox);
        typeButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            choosingType = !choosingType;
            rebuildWidgets();
        }).bounds(88, 3, 100, 16).icon(typeIcon(typeId())).tooltip(Tooltip.create(Component.literal("What it is: a home, a workplace for a job,"
                + " land for one (a field, a pen, a grove, a quarry, a dock, a guard post, a wall, a market square), or a road. Click to choose."))).build());
        int rx = width - 4;
        rx = topButton(rx, "Build in it", "Build it yourself, in creative, in the drafting world: a copy of the site with nothing else round it. "
                + "What you build becomes the design. The real site isn't touched, and nothing comes back with you.", b -> {
            flushName();
            send(TablePayloads.Action.Kind.FREE_BUILD, "");
            onClose();
        });
        editOnly.get(editOnly.size() - 1).setMessage(Component.literal("Build in it").withStyle(ChatFormatting.GREEN));
        rx = topButton(rx, "Plan it here", "Plan it on the site for the town's builders.", b -> {
            flushName();
            send(TablePayloads.Action.Kind.PLAN, "");
        });
        rx = topButton(rx, "Save", "Keep the design in My pack, to build again anywhere.", b -> send(TablePayloads.Action.Kind.SAVE, nameBox.getValue()));
        Button more = addRenderableWidget(StadButton.of(Component.literal(drawer ? "Generate ▴" : "Generate ▾"), b -> {
            drawer = !drawer;
            rebuildWidgets();
        }).tooltip(Tooltip.create(Component.literal("Generate a building to start from, change the site, or start over."))).bounds(rx - 64, 3, 62, 16).build());
        more.active = model != null;
        if (yardType()) {
            more.setMessage(Component.literal(drawer ? "Lay out ▴" : "Lay out ▾"));
            more.setTooltip(Tooltip.create(Component.literal("Lay out a " + word(typeId()) + " to fit the site, change the site, or start over.")));
        }
        // the layer controls, top right of the view
        int lx = viewRight() - 22;
        if (model != null && !picking && !siteOpen) {
            addRenderableWidget(StadButton.of(Component.literal("▲"), b -> setCut(cut + 1)).bounds(lx, TOP + 4, 18, 16)
                    .tooltip(Tooltip.create(Component.literal("Show a layer more (Shift+scroll, Page Up)"))).build());
            addRenderableWidget(StadButton.of(Component.literal("▼"), b -> setCut(cut - 1)).bounds(lx, TOP + 34, 18, 16)
                    .tooltip(Tooltip.create(Component.literal("Show a layer less, to see inside (Shift+scroll, Page Down)"))).build());
        }
        if (drawer) {
            initDrawer();
        }
        // a new site: two corners
        if (siteOpen && open.canEdit()) {
            int cx = viewRight() / 2 - 100, cy = siteY();
            cornerA = new EditBox(font, cx, cy, 98, 16, Component.literal("First corner"));
            cornerB = new EditBox(font, cx + 102, cy, 98, 16, Component.literal("Opposite corner"));
            List<BlockPos> c = open.corners();
            BlockPos a = !c.isEmpty() ? c.get(0) : model != null ? model.min : null;
            BlockPos bb = c.size() > 1 ? c.get(1) : model != null ? model.min.offset(model.sx - 1, model.sy - 1, model.sz - 1) : null;
            cornerA.setValue(a == null ? "" : a.getX() + " " + a.getY() + " " + a.getZ());
            cornerB.setValue(bb == null ? "" : bb.getX() + " " + bb.getY() + " " + bb.getZ());
            cornerA.setHint(Component.literal("x y z").withStyle(ChatFormatting.DARK_GRAY));
            cornerB.setHint(Component.literal("x y z").withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(cornerA);
            addRenderableWidget(cornerB);
            addRenderableWidget(StadButton.of(Component.literal("Use these corners"), b ->
                    send(TablePayloads.Action.Kind.SITE, cornerA.getValue() + " " + cornerB.getValue())).bounds(cx + 40, cy + 22, 120, 18).build());
        }
        if (picking) {
            searchBox = new EditBox(font, 8, 8, Math.min(140, vw / 2), 16, Component.literal("Search"));
            searchBox.setHint(Component.literal("Search blocks...").withStyle(ChatFormatting.DARK_GRAY));
            searchBox.setResponder(s -> {
                pickScroll = 0;
                filterPicks();
            });
            addRenderableWidget(searchBox);
            focusSearch = true;
            filterPicks();
        }
        for (Button b : editOnly) {
            b.active = open.canEdit() && (model != null || b.getMessage().getString().equals("Change site"));
        }
        typeButton.active = open.canEdit();
        nameBox.setEditable(open.canEdit());
        refreshLabels();
        if (choosingType) {
            initTypePicker();
        }
    }

    private static final List<String> TOWN_KINDS = List.of("stad:home", "stad:warehouse", "stad:barracks", "stad:watchtower", "stad:market",
            "stad:school", "stad:infirmary", "stad:building");

    /** The job a kind is for ("farmer"), or null. */
    private static String jobOf(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return null;
        }
        if (Yard.is(id)) {
            return Yard.job(id);
        }
        return dev.eiriksb.stad.colony.Workplaces.jobFor(id).orElse(null);
    }

    /** A kind's icon: its job's, or a house, a bell... */
    static ResourceLocation typeIcon(String type) {
        return switch (type) {
            case "stad:home" -> Theme.icon("house");
            case "stad:building" -> Theme.icon("bell");
            case "stad:field" -> Theme.icon("field");
            case "stad:pen" -> Theme.icon("fence");
            case "stad:grove" -> Theme.icon("sapling");
            case "stad:wall" -> Theme.icon("wall");
            case "stad:stalls" -> Theme.icon("stall");
            case "stad:road" -> Theme.icon("road");
            default -> {
                String job = jobOf(type);
                yield job != null ? Theme.job(job) : Theme.icon("bell");
            }
        };
    }

    private int pickerColumn() {
        return Math.min(132, (width - 32) / 3);
    }

    /** Where the list of kinds starts: centred. */
    private int pickerX() {
        return (width - 3 * pickerColumn() - 8) / 2;
    }

    /** The list of kinds over the view, in three columns: homes and the town's buildings, workplaces, and land. */
    private void initTypePicker() {
        for (net.minecraft.client.gui.components.events.GuiEventListener l : children()) {
            if (l instanceof net.minecraft.client.gui.components.AbstractWidget w && w != typeButton) {
                w.visible = false;
            }
        }
        int cw = pickerColumn(), x0 = pickerX(), y0 = TOP + 16;
        int[] rows = new int[3];
        for (int i = 0; i < open.types().size(); i++) {
            String t = open.types().get(i);
            int col = Yard.is(t) ? 2 : TOWN_KINDS.contains(t) ? 0 : 1;
            int index = i;
            String job = jobOf(t);
            List<String> needs = new ArrayList<>();
            ResourceLocation id = ResourceLocation.tryParse(t);
            if (id != null) {
                Requirements.needs(id).stream().filter(Requirements.Need::required).forEach(n -> needs.add(n.what()));
            }
            String tip = (job != null ? "For the " + job + ". " : "") + (needs.isEmpty() ? "" : "Needs " + dev.eiriksb.stad.planner.Design.join(needs) + ".");
            StadButton b = StadButton.of(Component.literal(font.plainSubstrByWidth(capital(word(t)), cw - 26)), btn -> {
                boolean wasYard = yardType();
                typeIndex = index;
                send(TablePayloads.Action.Kind.TYPE, open.types().get(typeIndex));
                if (model != null) {
                    model.type = open.types().get(typeIndex);
                }
                dirtyCheck = true;
                choosingType = false;
                if (wasYard != yardType() || yardType()) {
                    defaultHotbar();
                }
                tool = roadType() ? Tool.ROAD : tool == Tool.ROAD ? Tool.PLACE : tool;
                roadFrom = null;
                rebuildWidgets();
            }).bounds(x0 + col * (cw + 4), y0 + rows[col] * 20, cw, 18).icon(typeIcon(t)).tooltip(Tooltip.create(Component.literal(tip))).build();
            addRenderableWidget(b.chosen(index == typeIndex));
            rows[col]++;
        }
        pickerRows = Math.max(rows[0], Math.max(rows[1], rows[2]));
    }

    private int pickerRows;

    private void drawTypePicker(GuiGraphics g) {
        int cw = pickerColumn(), x0 = pickerX(), y0 = TOP + 16;
        Theme.panel(g, x0 - 8, TOP - 2, 3 * cw + 8 + 16, pickerRows * 20 + 26);
        String[] heads = {"Homes and the town", "Workplaces", "Land and roads"};
        for (int c = 0; c < 3; c++) {
            g.drawString(font, heads[c], x0 + c * (cw + 4), TOP + 4, Theme.GOLD);
        }
    }

    /** The drawer for a field, pen or grove: its settings, and laying it out. */
    private int initYardDrawer(int px, int pw, int y) {
        int half = (pw - 2) / 2;
        String t = typeId();
        List<Object[]> rows = new ArrayList<>(); // setting, tooltip, action, full width
        Runnable wood = () -> {
            fence = (fence + 1) % 4; // the woods
            defaultHotbar();
        };
        switch (t) {
            case "stad:field" -> {
                rows.add(new Object[]{"crop", "What the farmers sow. Mixed sows a different crop in each strip; you can also put crops on by hand.",
                        (Runnable) () -> crop = (crop + 1) % Yard.CROPS.size(), true});
                rows.add(new Object[]{"water", "Rows: channels along the field. Holes: one in the middle of each 9 by 9. Every plot has to be within"
                        + " four blocks of water.", (Runnable) () -> water = (water + 1) % Yard.WATERS.size(), false});
                rows.add(new Object[]{"fence", "A fence round it keeps animals off the crops.", (Runnable) () -> {
                    fence = (fence + 1) % Yard.FENCES.size();
                    defaultHotbar();
                }, false});
                rows.add(new Object[]{"path", "A path round the inside of the fence, to walk on.", (Runnable) () -> path = !path, false});
                rows.add(new Object[]{"extra", "Lanterns on the corner posts.", (Runnable) () -> extra = !extra, false});
            }
            case "stad:pen" -> {
                rows.add(new Object[]{"animal", "Which animals the shepherd brings into this pen.", (Runnable) () -> animal = (animal + 1) % Yard.ANIMALS.size(),
                        true});
                rows.add(new Object[]{"fence", "What the pen is fenced with.", (Runnable) () -> {
                    fence = (fence + 1) % (Yard.FENCES.size() - 1); // a pen has to have a fence
                    defaultHotbar();
                }, false});
                rows.add(new Object[]{"extra", "A roofed shelter with a hay bale, and a cauldron of water (for a pen 8 by 8 or bigger).",
                        (Runnable) () -> extra = !extra, false});
            }
            case "stad:grove" -> {
                rows.add(new Object[]{"tree", "Which saplings the lumberjack plants. Put saplings in the town hall for them.",
                        (Runnable) () -> tree = (tree + 1) % Yard.TREES.size(), true});
                rows.add(new Object[]{"spacing", "Blocks from one sapling to the next: further apart, the trees grow bigger.",
                        (Runnable) () -> spacing = spacing >= Yard.MAX_SPACING ? Yard.MIN_SPACING : spacing + 1, false});
                rows.add(new Object[]{"path", "A path through the middle.", (Runnable) () -> path = !path, false});
            }
            case "stad:quarry" -> {
                rows.add(new Object[]{"depth", "How deep the miner digs it, a layer at a time, leaving steps round the edge to walk down.",
                        (Runnable) () -> depth = Yard.DEPTHS.get((Yard.DEPTHS.indexOf(depth) + 1) % Yard.DEPTHS.size()), true});
                rows.add(new Object[]{"fence", "A fence round the pit, so nobody walks in.", (Runnable) () -> {
                    fence = (fence + 1) % Yard.FENCES.size();
                    defaultHotbar();
                }, false});
                rows.add(new Object[]{"extra", "Lanterns on the corners.", (Runnable) () -> extra = !extra, false});
            }
            case "stad:dock" -> {
                rows.add(new Object[]{"fence", "What the pier is built of.", wood, false});
                rows.add(new Object[]{"extra", "Lanterns at the end of the pier.", (Runnable) () -> extra = !extra, false});
            }
            case "stad:post" -> rows.add(new Object[]{"fence", "What the post is built of.", wood, false});
            case "stad:stalls" -> {
                rows.add(new Object[]{"awning", "The colour of the stalls' awnings.", (Runnable) () -> awning = (awning + 1) % Yard.AWNINGS.size(), true});
                rows.add(new Object[]{"paving", "What the square is paved with.", (Runnable) () -> paving = (paving + 1) % Yard.PAVINGS.size(), true});
                rows.add(new Object[]{"fence", "The wood of the stalls' posts and counters.", wood, false});
                rows.add(new Object[]{"extra", "A lantern under each awning.", (Runnable) () -> extra = !extra, false});
            }
            case "stad:road" -> {
                rows.add(new Object[]{"paving", "What the road is paved with (planks over water).", (Runnable) () -> paving = (paving + 1) % Yard.PAVINGS.size(),
                        true});
                rows.add(new Object[]{"width", "How wide the road is.", (Runnable) () -> roadWidth = roadWidth >= Yard.MAX_WIDTH ? Yard.MIN_WIDTH
                        : roadWidth + 1, false});
                rows.add(new Object[]{"extra", "Lamp posts beside it, every eight blocks or so.", (Runnable) () -> extra = !extra, false});
            }
            default -> {
                rows.add(new Object[]{"wall", "Logs make a palisade; stone bricks and cobblestone a stone wall.",
                        (Runnable) () -> wall = (wall + 1) % Yard.WALLS.size(), true});
                rows.add(new Object[]{"height", "How high the wall is.",
                        (Runnable) () -> wallHeight = wallHeight >= Yard.MAX_HEIGHT ? Yard.MIN_HEIGHT : wallHeight + 1, false});
                rows.add(new Object[]{"extra", "Watch spots on top of the corners, for the guards to patrol to.", (Runnable) () -> extra = !extra, false});
            }
        }
        if (!t.equals("stad:road")) {
            rows.add(new Object[]{"door", switch (t) {
                case "stad:dock" -> "Which side the shore is on: the pier starts there and runs out over the water.";
                case "stad:post" -> "Which side the ladder is on.";
                case "stad:wall" -> "Which side the door in the wall is on.";
                default -> "Which side the gate (or the way in) is on.";
            }, (Runnable) () -> door = (door + 1) % 4, false});
        }
        int col = 0;
        for (Object[] r : rows) {
            boolean full = (Boolean) r[3];
            if (full && col == 1) {
                y += 20;
                col = 0;
            }
            Runnable action = (Runnable) r[2];
            Button b = addRenderableWidget(StadButton.of(Component.empty(), btn -> {
                action.run();
                refreshLabels();
            }).bounds(full ? px : px + col * (half + 2), y, full ? pw : half, 18).tooltip(Tooltip.create(Component.literal((String) r[1]))).build());
            yardButtons.put(b, (String) r[0]);
            if (full || col == 1) {
                y += 20;
                col = 0;
            } else {
                col = 1;
            }
        }
        if (col == 1) {
            y += 20;
        }
        String help = switch (t) {
            case "stad:field" -> "Farmland and water are for the farmers to make; the builders level the ground and put up the fence and path.";
            case "stad:pen" -> "The builders put up the fence. The shepherd leads animals in through the gate and looks after them there.";
            case "stad:grove" -> "The lumberjack plants a sapling on each spot, and fells the trees once they've grown.";
            case "stad:quarry" -> "The miner digs the pit down a layer at a time, with steps round its edge, for stone, coal and ore.";
            case "stad:dock" -> "The builders build the pier; the fishers fish from the marked spots on it.";
            case "stad:post" -> "The builders build it; the guards patrol to it and keep watch from the top.";
            case "stad:stalls" -> "The builders pave the square and build the stalls; a merchant keeps each one. Trade with them there.";
            case "stad:road" -> "Draw it with the Road tool, or Route it to join the doors, the town hall and roads at the site's edge. Citizens"
                    + " walk faster on it.";
            default -> "The builders build it round whatever is inside. With watch spots, the guards patrol along it.";
        };
        if (t.equals("stad:road")) {
            editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Route it"), b -> {
                send(TablePayloads.Action.Kind.ROUTE, "");
                setStatus("Routing...", false);
            }).tooltip(Tooltip.create(Component.literal("Join up what's in the site (the doors of the town's buildings, the town hall, and roads"
                    + " at its edge) the easiest way over the land, round buildings and hills. It adds to the road drawn so far.")))
                    .bounds(px, y, pw, 18).build()));
            y += 20;
        }
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Lay it out"), b -> generate())
                .tooltip(Tooltip.create(Component.literal("Fill the site with a " + word(t) + " from these settings. Your own changes stay on top. "
                        + help)))
                .bounds(px, y, half, 18).build()));
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Flatten"), b -> send(TablePayloads.Action.Kind.FLATTEN, ""))
                .tooltip(Tooltip.create(Component.literal("Make what was laid out plain blocks, so laying it out again starts afresh.")))
                .bounds(px + half + 2, y, half, 18).build()));
        y += 22;
        drawerHelp = font.split(Component.literal(help), pw);
        return y;
    }

    private List<net.minecraft.util.FormattedCharSequence> drawerHelp = List.of();

    /** The drawer on the right: generating a building to start from, asking for a change, the site. */
    private void initDrawer() {
        int px = viewRight() + 4, pw = DRAWER - 8, y = TOP + 4;
        int half = (pw - 2) / 2, third = (pw - 4) / 3;
        drawerHelp = List.of();
        if (yardType()) {
            y = initYardDrawer(px, pw, y);
            if (y + 2 + drawerHelp.size() * 10 + 4 + 22 + 24 > vh - 21) {
                drawerHelp = List.of(); // no room: it's in the button's tooltip too
            }
            drawerInfoY = y + 2 + drawerHelp.size() * 10 + (drawerHelp.isEmpty() ? 0 : 4);
            int by = Math.max(drawerInfoY + 24, vh - 21);
            editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Change site"), b -> {
                siteOpen = !siteOpen;
                rebuildWidgets();
            }).bounds(px, by, half, 18).build()));
            startOverButton = addRenderableWidget(StadButton.of(Component.literal("Reset"), b -> startOver())
                    .tooltip(Tooltip.create(Component.literal("Clear the whole design: the site goes back to how it stands."))).bounds(px + half + 2, by, half, 18).build());
            editOnly.add(startOverButton);
            boolean gen = open.canEdit() && model != null;
            yardButtons.keySet().forEach(b -> b.active = gen);
            return;
        }
        styleButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            if (!open.styles().isEmpty()) {
                style = (style + 1) % open.styles().size();
                defaultHotbar();
            }
            refreshLabels();
        }).bounds(px, y, pw, 18).build());
        y += 20;
        floorsButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            floors = floors % BuildSpec.MAX_FLOORS + 1;
            refreshLabels();
        }).bounds(px, y, half, 18).build());
        roofButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            roof = (roof + 1) % BuildSpec.ROOFS.size();
            refreshLabels();
        }).bounds(px + half + 2, y, half, 18).build());
        y += 20;
        doorButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            door = (door + 1) % 4;
            refreshLabels();
        }).bounds(px, y, half, 18).build());
        bedsButton = addRenderableWidget(StadButton.of(Component.empty(), b -> {
            beds = (beds + 1) % (BuildSpec.MAX_BEDS + 1);
            refreshLabels();
        }).bounds(px + half + 2, y, half, 18).build());
        y += 20;
        for (int i = 0; i < BuildSpec.FEATURES.size(); i++) {
            String f = BuildSpec.FEATURES.get(i);
            featureButtons.add(addRenderableWidget(StadButton.of(Component.empty(), b -> {
                if (!features.remove(f)) {
                    features.add(f);
                }
                refreshLabels();
            }).bounds(px + i * (third + 2), y, third, 18).build()));
        }
        y += 20;
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Generate"), b -> generate())
                .tooltip(Tooltip.create(Component.literal("Fill the site with a building from these settings. Your own changes stay on top.")))
                .bounds(px, y, half, 18).build()));
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Flatten"), b -> send(TablePayloads.Action.Kind.FLATTEN, ""))
                .tooltip(Tooltip.create(Component.literal("Make the generated building plain blocks, so generating again starts afresh.")))
                .bounds(px + half + 2, y, half, 18).build()));
        y += 22;
        describeBox = new EditBox(font, px, y, pw - 30, 16, Component.literal("Ask for a change"));
        describeBox.setMaxLength(300);
        describeBox.setHint(Component.literal("Ask for a change...").withStyle(ChatFormatting.DARK_GRAY));
        describeBox.setTooltip(Tooltip.create(Component.literal("Say what to change (\"two floors, a stone porch\") and the town's architect redraws the generated building.")));
        describeBox.setValue(describeText);
        describeBox.setResponder(v -> describeText = v);
        addRenderableWidget(describeBox);
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Ask"), b -> describe()).bounds(px + pw - 28, y - 1, 28, 18).build()));
        y += 20;
        drawerInfoY = y + 2;
        int by = Math.max(y + 24, vh - 21);
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal("Change site"), b -> {
            siteOpen = !siteOpen;
            rebuildWidgets();
        }).bounds(px, by, half, 18).build()));
        startOverButton = addRenderableWidget(StadButton.of(Component.literal("Reset"), b -> startOver())
                .tooltip(Tooltip.create(Component.literal("Clear the whole design: the site goes back to how it stands."))).bounds(px + half + 2, by, half, 18).build());
        editOnly.add(startOverButton);
        boolean gen = open.canEdit() && model != null;
        for (Button b : List.of(styleButton, floorsButton, roofButton, doorButton, bedsButton)) {
            b.active = gen;
        }
        featureButtons.forEach(b -> b.active = gen);
        describeBox.setEditable(gen);
    }

    /** A button in the top bar, right to left: returns where the next one ends. */
    private int topButton(int right, String label, String tip, Button.OnPress press) {
        int w = font.width(label) + 12;
        editOnly.add(addRenderableWidget(StadButton.of(Component.literal(label), press).tooltip(Tooltip.create(Component.literal(tip)))
                .bounds(right - w, 3, w, 16).build()));
        return right - w - 2;
    }

    /** Lays labelled buttons out in rows from {@code x}, wrapping back to {@code wrapX}: x, row and width of each. */
    private List<int[]> flow(List<String> labels, int x, int wrapX) {
        List<int[]> out = new ArrayList<>();
        int row = 0;
        for (String l : labels) {
            int w = font.width(l) + 10;
            if (x + w > width - 4 && x > wrapX) {
                x = wrapX;
                row++;
            }
            out.add(new int[]{x, row, w});
            x += w + 2;
        }
        return out;
    }

    /** Where the view's open part ends on the right (the drawer covers the rest). */
    private int viewRight() {
        return drawer ? vw - DRAWER : vw;
    }

    private void refreshLabels() {
        for (int i = 0; i < toolButtons.size(); i++) {
            Tool t = shownTools.get(i);
            String label = t == Tool.MARK && tool == Tool.MARK ? "Mark: " + MARKERS.get(markKind).substring(5) : t.label;
            toolButtons.get(i).setMessage(Component.literal(label).withStyle(t == tool ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
        if (mirrorXButton != null) {
            mirrorXButton.setMessage(Component.literal("Mirror X").withStyle(mirrorX ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
            mirrorZButton.setMessage(Component.literal("Mirror Z").withStyle(mirrorZ ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
        if (typeButton != null) {
            String type = typeId();
            typeButton.setMessage(Component.literal(font.plainSubstrByWidth(capital(word(type)), 76)).withStyle(Yard.is(type) ? ChatFormatting.GREEN
                    : ChatFormatting.WHITE));
        }
        String t = typeId();
        for (Map.Entry<Button, String> e : yardButtons.entrySet()) {
            boolean woodOnly = t.equals("stad:dock") || t.equals("stad:post") || t.equals("stad:stalls");
            e.getKey().setMessage(Component.literal(switch (e.getValue()) {
                case "crop" -> "Crop: " + capital(Yard.CROPS.get(crop));
                case "water" -> "Water: " + Yard.WATERS.get(water);
                case "fence" -> (woodOnly ? "Wood: " : "Fence: ") + Yard.FENCES.get(fence).replace('_', ' ');
                case "path" -> "Path: " + (path ? "yes" : "no");
                case "extra" -> (t.equals("stad:pen") ? "Shelter: " : t.equals("stad:wall") ? "Posts: " : "Lamps: ") + (extra ? "yes" : "no");
                case "animal" -> "Animals: " + Yard.ANIMALS.get(animal);
                case "tree" -> "Trees: " + Yard.TREES.get(tree).replace('_', ' ');
                case "spacing" -> "Spacing: " + spacing;
                case "depth" -> "Depth: " + depth + " layers";
                case "wall" -> "Wall: " + Yard.WALLS.get(wall).replace('_', ' ');
                case "height" -> "Height: " + wallHeight;
                case "paving" -> "Paving: " + Yard.PAVINGS.get(paving).replace('_', ' ');
                case "awning" -> "Awnings: " + Yard.AWNINGS.get(awning);
                case "width" -> "Width: " + roadWidth;
                default -> (switch (t) {
                    case "stad:grove", "stad:stalls" -> "Way in: ";
                    case "stad:dock" -> "Shore: ";
                    case "stad:post" -> "Ladder: ";
                    case "stad:wall" -> "Door: ";
                    default -> "Gate: ";
                }) + DOORS[door].getSerializedName();
            }));
        }
        if (styleButton == null) {
            return;
        }
        styleButton.setMessage(Component.literal("Style: " + currentStyle().name()));
        floorsButton.setMessage(Component.literal(floors + (floors == 1 ? " floor" : " floors")));
        roofButton.setMessage(Component.literal(capital(BuildSpec.ROOFS.get(roof)) + " roof"));
        doorButton.setMessage(Component.literal("Door: " + DOORS[door].getSerializedName()));
        bedsButton.setMessage(Component.literal(beds + (beds == 1 ? " bed" : " beds")));
        for (int i = 0; i < featureButtons.size(); i++) {
            String f = BuildSpec.FEATURES.get(i);
            String label = switch (f) {
                case "big_windows" -> "Windows";
                default -> capital(f);
            };
            featureButtons.get(i).setMessage(Component.literal(label).withStyle(features.contains(f) ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        }
    }

    private static String word(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id == null ? type : id.getPath().equals("building") ? "other" : Requirements.word(id);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- server ------------------------------------------------------------------------------------------------

    private void send(TablePayloads.Action.Kind kind, String text) {
        PacketDistributor.sendToServer(new TablePayloads.Action(open.table(), kind, text));
    }

    private JsonObject specJson() {
        TablePayloads.Style st = currentStyle();
        JsonObject s = new JsonObject();
        s.addProperty("name", nameBox.getValue().isBlank() ? "Building" : nameBox.getValue());
        s.addProperty("type", "home");
        s.addProperty("base", "new");
        s.addProperty("width", 7);
        s.addProperty("depth", 7);
        s.addProperty("floors", floors);
        s.addProperty("roof", BuildSpec.ROOFS.get(roof));
        s.addProperty("walls", st.walls());
        s.addProperty("frame", st.frame());
        s.addProperty("roof_material", st.roof());
        s.addProperty("floor", st.floor());
        s.addProperty("beds", beds);
        s.add("rooms", new JsonArray());
        JsonArray f = new JsonArray();
        features.forEach(f::add);
        s.add("features", f);
        return s;
    }

    private void generate() {
        flushName();
        JsonObject o = new JsonObject();
        if (yardType()) {
            o.add("yard", com.google.gson.JsonParser.parseString(yard().toJson()));
        } else {
            o.add("spec", specJson());
        }
        o.addProperty("door", DOORS[door].getSerializedName());
        send(TablePayloads.Action.Kind.GENERATE, o.toString());
        setStatus("Generating...", false);
    }

    private void describe() {
        String text = describeBox.getValue().strip();
        if (text.isEmpty()) {
            return;
        }
        flushName();
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        o.add("spec", specJson());
        o.addProperty("door", DOORS[door].getSerializedName());
        send(TablePayloads.Action.Kind.DESCRIBE, o.toString());
        describeBox.setValue("");
    }

    private void startOver() {
        long now = System.currentTimeMillis();
        if (now - startOverAsked > 4000) {
            startOverAsked = now;
            setStatus("Click Reset again to clear the whole design: the site goes back to how it stands.", true);
            return;
        }
        startOverAsked = 0;
        send(TablePayloads.Action.Kind.START_OVER, "");
    }

    private void flushName() {
        if (nameBox != null && !nameBox.getValue().equals(sentName) && !nameBox.getValue().isBlank()) {
            sentName = nameBox.getValue();
            send(TablePayloads.Action.Kind.RENAME, sentName);
        }
    }

    /** The server changed the design, or has something to say. */
    public void update(TablePayloads.Update u) {
        if (u.design() != null && model != null) {
            model.load(u.design());
            readSpec(model.spec);
            readYard(model.yard);
            if (!nameBox.isFocused()) {
                name = model.name;
                nameBox.setValue(model.name);
                sentName = model.name;
            }
            if (!u.keep()) {
                undo.clear();
                redo.clear();
            }
            if (renderer != null) {
                renderer.markAll();
            }
            dirtyCheck = true;
            refreshLabels();
        }
        if (!u.message().isEmpty()) {
            setStatus(u.message(), u.error());
        }
    }

    private void setStatus(String s, boolean error) {
        status = s;
        statusError = error;
        statusAt = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        if (focusSearch && searchBox != null) {
            focusSearch = false;
            setFocused(searchBox); // after the click that opened the picker has let go of its button
        }
        if (dirtyCheck && model != null) {
            dirtyCheck = false;
            check = model.check();
            materials = model.materials();
        }
        if (nameBox != null && !nameBox.getValue().equals(sentName) && ++nameTicks > 20) {
            nameTicks = 0;
            flushName();
        }
    }

    /** Another screen for the same table takes over (the site changed): don't let go of the table, hand over the tools. */
    public void replacedBy(ArchitectScreen next) {
        replaced = true;
        System.arraycopy(hotbar, 0, next.hotbar, 0, hotbar.length);
        next.slot = slot;
        next.tool = tool;
        next.turns = turns;
        next.mirrorX = mirrorX;
        next.mirrorZ = mirrorZ;
        next.markKind = markKind;
        next.clip = clip;
        next.clipSize = clipSize;
        if (model != null && next.model != null && model.sx == next.model.sx && model.sz == next.model.sz) {
            next.yaw = yaw;
            next.pitch = pitch;
            next.dist = dist;
            next.center.set(center);
        }
    }

    @Override
    public void removed() {
        if (!replaced) {
            flushName();
            send(TablePayloads.Action.Kind.CLOSE, "");
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        super.removed();
    }

    // ---- the camera ---------------------------------------------------------------------------------------------

    private Matrix4f projection() {
        return new Matrix4f().perspective((float) Math.toRadians(60), (float) vw / Math.max(1, vh), 0.05f, 1000f);
    }

    private Matrix4f view() {
        return new Matrix4f().translate(0, 0, -dist).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw))
                .translate(-center.x, -center.y, -center.z);
    }

    /** Which way the camera looks, flat. */
    private Direction look() {
        double r = Math.toRadians(yaw);
        return Direction.getNearest(Math.sin(r), 0, -Math.cos(r));
    }

    private void setCut(int c) {
        if (model == null) {
            return;
        }
        cut = Math.max(0, Math.min(model.gh() - 1, c));
    }

    private Hit pick(double mx, double my) {
        if (model == null || mx < 0 || my < 0 || mx >= vw || my >= vh) {
            return null;
        }
        float nx = (float) (mx / vw * 2 - 1), ny = (float) (1 - my / vh * 2);
        Matrix4f inv = projection().mul(view()).invert();
        Vector3f a = inv.transformProject(nx, ny, -1, new Vector3f());
        Vector3f b = inv.transformProject(nx, ny, 1, new Vector3f());
        Vector3f d = b.sub(a).normalize();
        return raycast(a, d);
    }

    /** Walks the ray through the grid to the first block that isn't air (and isn't cut away). */
    private Hit raycast(Vector3f o, Vector3f d) {
        int top = Math.min(model.gh(), cut + 1);
        double[] lo = {0, 0, 0}, hi = {model.gw(), top, model.gd()};
        double[] org = {o.x, o.y, o.z}, dir = {d.x, d.y, d.z};
        double tmin = 0, tmax = 1e9;
        int enter = -1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-9) {
                if (org[i] < lo[i] || org[i] > hi[i]) {
                    return null;
                }
                continue;
            }
            double t1 = (lo[i] - org[i]) / dir[i], t2 = (hi[i] - org[i]) / dir[i];
            double near = Math.min(t1, t2), far = Math.max(t1, t2);
            if (near > tmin) {
                tmin = near;
                enter = i;
            }
            tmax = Math.min(tmax, far);
        }
        if (tmax < tmin) {
            return null;
        }
        double t = tmin + 1e-5;
        int[] cell = new int[3], step = new int[3];
        double[] tMax = new double[3], tDelta = new double[3];
        for (int i = 0; i < 3; i++) {
            double p = org[i] + dir[i] * t;
            cell[i] = (int) Math.floor(Math.min(Math.max(p, lo[i]), hi[i] - 1e-6));
            step[i] = dir[i] > 0 ? 1 : dir[i] < 0 ? -1 : 0;
            tDelta[i] = step[i] == 0 ? Double.MAX_VALUE : Math.abs(1 / dir[i]);
            double boundary = step[i] > 0 ? cell[i] + 1 : cell[i];
            tMax[i] = step[i] == 0 ? Double.MAX_VALUE : t + (boundary - p) / dir[i];
        }
        Direction face = enter < 0 ? Direction.UP : faceOf(enter, step[enter]);
        double tEnter = t;
        for (int n = 0; n < 512; n++) {
            if (cell[0] < 0 || cell[1] < 0 || cell[2] < 0 || cell[0] >= model.gw() || cell[1] >= top || cell[2] >= model.gd()) {
                return null;
            }
            if (!model.at(cell[0], cell[1], cell[2]).isAir()) {
                double y = org[1] + dir[1] * tEnter - cell[1];
                return new Hit(new BlockPos(cell[0], cell[1], cell[2]), face, y);
            }
            int axis = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
            tEnter = tMax[axis];
            cell[axis] += step[axis];
            tMax[axis] += tDelta[axis];
            face = faceOf(axis, step[axis]);
        }
        return null;
    }

    /** The face a ray going {@code step} along {@code axis} comes in through. */
    private static Direction faceOf(int axis, int step) {
        return switch (axis) {
            case 0 -> step > 0 ? Direction.WEST : Direction.EAST;
            case 1 -> step > 0 ? Direction.DOWN : Direction.UP;
            default -> step > 0 ? Direction.NORTH : Direction.SOUTH;
        };
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, vw, vh, 0xFF7FA7D0, 0xFFC9DCEC);
        Theme.panel(g, -6, vh, width + 12, height - vh + 6);
        hover = dragButton < 0 && !picking && inView(mouseX, mouseY) ? pick(mouseX, mouseY) : null;
        if (model != null) {
            try {
                render3D(g);
            } catch (RuntimeException e) {
                dev.eiriksb.stad.Stad.LOGGER.warn("Couldn't draw the architect's table's view", e);
                setStatus("The 3D view failed to draw: " + e.getMessage(), true);
            }
        }
        Theme.panel(g, -6, -6, vw + 12, TOP + 6);
        if (drawer) {
            Theme.panel(g, viewRight(), TOP - 1, vw - viewRight() + 6, vh - TOP + 2);
        }
        if (siteOpen && !choosingType) {
            drawSitePanel(g);
        }
        if (choosingType) {
            drawTypePicker(g);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (drawer) {
            drawDrawer(g);
        }
        drawHotbar(g, mouseX, mouseY);
        if (model != null && !picking) {
            drawViewText(g);
        }
        drawStatus(g);
        if (model != null) {
            drawChecklist(g, mouseX, mouseY);
        }
        if (picking) {
            drawPicker(g, mouseX, mouseY);
        }
    }

    private void render3D(GuiGraphics g) {
        if (renderer == null) {
            renderer = new DesignRenderer(model, open.biome());
        }
        renderer.cut = cut;
        renderer.update();
        g.flush();
        Window win = minecraft.getWindow();
        double scale = win.getGuiScale();
        int pw = (int) Math.round(vw * scale), ph = (int) Math.round(vh * scale), py = win.getHeight() - ph;
        Matrix4f proj = projection(), view = view();
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            RenderSystem.viewport(0, py, pw, ph);
            RenderSystem.enableScissor(0, py, pw, ph);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
            mv.set(view);
            RenderSystem.applyModelViewMatrix();
            FogRenderer.setupNoFog();
            RenderSystem.enableDepthTest();
            renderer.draw(view, proj);
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            renderer.drawEntities(buffers);
            drawLines(buffers.getBuffer(RenderType.lines()));
            buffers.endBatch();
        } finally {
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.disableScissor();
            RenderSystem.viewport(0, 0, win.getWidth(), win.getHeight());
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private void drawLines(VertexConsumer lines) {
        PoseStack pose = new PoseStack();
        int m = DesignModel.M;
        LevelRenderer.renderLineBox(pose, lines, m, m, m, m + model.sx, m + model.sy, m + model.sz, 1f, 0.85f, 0.2f, 1f);
        if (renderer.cutting()) {
            LevelRenderer.renderLineBox(pose, lines, m - 0.02, cut + 0.98, m - 0.02, m + model.sx + 0.02, cut + 1.02, m + model.sz + 0.02,
                    0.3f, 0.9f, 1f, 1f);
        }
        // a quarry: the pit the miner will dig, going down (as far as the view shows)
        List<BlockPos> pit = model.markers.entrySet().stream().filter(e -> e.getValue().startsWith("stad:quarry"))
                .map(e -> DesignModel.grid(e.getKey())).toList();
        if (!pit.isEmpty()) {
            String name = model.markers.values().stream().filter(v -> v.startsWith("stad:quarry")).findFirst().orElse("stad:quarry_16");
            int depth = 16;
            try {
                depth = Integer.parseInt(name.substring("stad:quarry_".length()));
            } catch (RuntimeException ignored) {
            }
            int x0 = pit.stream().mapToInt(BlockPos::getX).min().orElse(0), x1 = pit.stream().mapToInt(BlockPos::getX).max().orElse(0);
            int z0 = pit.stream().mapToInt(BlockPos::getZ).min().orElse(0), z1 = pit.stream().mapToInt(BlockPos::getZ).max().orElse(0);
            int top = pit.getFirst().getY();
            LevelRenderer.renderLineBox(pose, lines, x0 + 0.02, Math.max(0, top - depth + 1), z0 + 0.02, x1 + 0.98, top + 1.02, z1 + 0.98,
                    0.75f, 0.75f, 0.8f, 1f);
        }
        for (Map.Entry<Integer, String> e : model.markers.entrySet()) {
            BlockPos p = DesignModel.grid(e.getKey());
            if (p.getY() > cut) {
                continue;
            }
            if (e.getValue().equals("stad:road")) { // where the road was drawn: a mark on the top of the ground
                LevelRenderer.renderLineBox(pose, lines, p.getX() + 0.35, p.getY() + 1.01, p.getZ() + 0.35, p.getX() + 0.65, p.getY() + 1.03,
                        p.getZ() + 0.65, 1f, 0.6f, 0.2f, 1f);
                continue;
            }
            int c = markerColor(e.getValue());
            LevelRenderer.renderLineBox(pose, lines, p.getX() + 0.25, p.getY() + 0.25, p.getZ() + 0.25, p.getX() + 0.75, p.getY() + 0.75,
                    p.getZ() + 0.75, (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f);
        }
        Hit h = hover;
        if (h != null && open.canEdit()) {
            BlockPos target = targets(h);
            if (corner != null) {
                BlockPos a = corner, b = target;
                if (tool == Tool.FLOOR) {
                    b = new BlockPos(b.getX(), a.getY(), b.getZ());
                }
                LevelRenderer.renderLineBox(pose, lines, Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
                        cornerErase ? 1f : 0.3f, cornerErase ? 0.3f : 1f, 0.3f, 1f);
            }
            if (tool == Tool.ROAD && roadFrom != null) {
                for (int[] c : roadLine(roadFrom, target)) {
                    int g = ground(c[0], c[1]);
                    LevelRenderer.renderLineBox(pose, lines, c[0] + 0.1, g + 1.0, c[1] + 0.1, c[0] + 0.9, g + 1.08, c[1] + 0.9, 1f, 0.75f, 0.3f, 1f);
                }
            }
            boolean ok = model.inSite(target.getX(), target.getY(), target.getZ());
            LevelRenderer.renderLineBox(pose, lines, target.getX() - 0.005, target.getY() - 0.005, target.getZ() - 0.005, target.getX() + 1.005,
                    target.getY() + 1.005, target.getZ() + 1.005, ok ? 1f : 1f, ok ? 1f : 0.3f, ok ? 1f : 0.3f, 1f);
        }
    }

    /** The cell the tool would act on for a hit: the space in front of the face for placing tools, else the block. */
    private BlockPos targets(Hit h) {
        boolean against = switch (tool) {
            case PLACE, PASTE, MARK -> true;
            case BOX, WALLS, FLOOR, LINE -> !(corner != null && cornerErase);
            default -> false;
        };
        return against ? h.cell().relative(h.face()) : h.cell();
    }

    private static int markerColor(String marker) {
        return switch (marker) {
            case "stad:work" -> 0x40FF40;
            case "stad:sit" -> 0x40C0FF;
            case "stad:bed" -> 0xFF60C0;
            case "stad:storage" -> 0xFFC040;
            case "stad:door" -> 0xFFFFFF;
            case "stad:post" -> 0x4080FF;
            case "stad:fish" -> 0x40E0E0;
            default -> marker.startsWith("stad:quarry") ? 0xC0C0C0 : 0xFF4040;
        };
    }

    private void drawViewText(GuiGraphics g) {
        long now = System.currentTimeMillis();
        boolean statusShown = !status.isEmpty() && now - statusAt <= (statusError ? 14000 : 8000);
        if (!statusShown && now - openedAt < 12000 && !siteOpen) {
            g.drawString(font, font.plainSubstrByWidth("Drag: turn · Scroll: zoom · Middle-drag: pan · Shift+scroll: layers", viewRight() - 60),
                    4, TOP + 4, 0xFFFFFF, true);
        }
        int lx = viewRight() - 13;
        if (!siteOpen) { // the layer and the compass go with the layer buttons
            String layer = cut >= model.gh() - 1 ? "All" : String.valueOf(cut - DesignModel.M);
            g.drawCenteredString(font, layer, lx, TOP + 22, 0xFFFFFF);
            // compass: which way north is on screen
            int cx = lx - 34, cy = TOP + 26;
            double r = Math.toRadians(yaw);
            int nx = (int) Math.round(-Math.sin(r) * 10), ny = (int) Math.round(-Math.cos(r) * 10 * Math.sin(Math.toRadians(Math.max(20, pitch))));
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
            g.drawCenteredString(font, "N", cx + nx, cy + ny - 4, 0xFF5555);
        }
        if (hover != null) {
            BlockPos c = hover.cell();
            BlockState s = model.at(c.getX(), c.getY(), c.getZ());
            BlockPos world = model.min.offset(c.getX() - DesignModel.M, c.getY() - DesignModel.M, c.getZ() - DesignModel.M);
            String text = s.getBlock().getName().getString() + "  " + world.getX() + " " + world.getY() + " " + world.getZ();
            g.drawString(font, font.plainSubstrByWidth(text, viewRight() / 2 - 8), 4, vh - 12, 0xFFFFFF, true);
        }
        // what's in hand, bottom right
        BlockState sel = hotbar[slot];
        int have = stock.getOrDefault(BuiltInRegistries.ITEM.getKey(sel.getBlock().asItem()).toString(), 0);
        String info = sel.getBlock().getName().getString() + (turns % 4 != 0 ? ", turned " + (turns % 4 * 90) + "°" : "") + " · town has " + have;
        info = font.plainSubstrByWidth(info, viewRight() / 2 - 8);
        g.drawString(font, info, viewRight() - font.width(info) - 4, vh - 12, 0xE0E0E0, true);
        if (corner != null || now - toolAt < 5000) {
            String hint = tool.hint + (corner != null ? "  (now the other corner)" : "");
            g.drawString(font, font.plainSubstrByWidth(hint, viewRight() - 8), 4, vh - 36, 0xE0E0E0, true);
        }
    }

    /** Where the site's corner boxes go, down the view (the panel round them starts 58 above). */
    private int siteY() {
        return Math.max(TOP + 62, vh / 2 - 20);
    }

    private void drawSitePanel(GuiGraphics g) {
        int cx = viewRight() / 2, cy = siteY();
        Theme.panel(g, cx - 136, cy - 58, 272, 112);
        Theme.header(g, font, Component.literal("Choose the site"), cx, cy - 52);
        List<FormattedCharSequence> lines = font.split(Component.literal(open.canEdit()
                ? "Mark two corners with the scan wand and right-click the table with it, or type the corners here. "
                + "At most " + Project.MAX_SIDE + " by " + Project.MAX_SIDE + " blocks, on the town's land."
                : "An officer has to choose the site: mark two corners with the scan wand and right-click the table with it."), 250);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            g.drawCenteredString(font, lines.get(i), cx, cy - 36 + i * 10, Theme.TEXT);
        }
    }

    /** The site and what the design takes, under the drawer's buttons. */
    private void drawDrawer(GuiGraphics g) {
        int px = viewRight() + 4, y = drawerInfoY;
        int hy = y - drawerHelp.size() * 10 - 4;
        for (net.minecraft.util.FormattedCharSequence line : drawerHelp) {
            g.drawString(font, line, px, hy, 0x909090);
            hy += 10;
        }
        if (model != null) {
            g.drawString(font, "Site " + model.sx + " × " + model.sz + ", " + model.sy + " high", px, y, 0xA0A0A0);
            y += 11;
        }
        if (check != null) {
            g.drawString(font, materialsLine(), px, y, shortBy() == 0 ? 0xA0A0A0 : 0xFFB060);
        }
    }

    private int shortBy() {
        int n = 0;
        for (Map.Entry<Item, Integer> e : materials.entrySet()) {
            n += Math.max(0, e.getValue() - stock.getOrDefault(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), 0));
        }
        return n;
    }

    private String materialsLine() {
        int total = materials.values().stream().mapToInt(Integer::intValue).sum(), shortBy = shortBy();
        return total + " items to build" + (total == 0 ? "" : shortBy == 0 ? ", all in store" : ", " + shortBy + " short");
    }

    /** What the design still needs to be its kind of building: one line bottom left of the view, the whole list on hover. */
    private void drawChecklist(GuiGraphics g, int mouseX, int mouseY) {
        if (check == null || picking) {
            return;
        }
        String type = open.types().isEmpty() ? "building" : word(open.types().get(typeIndex));
        List<String> missing = check.missing();
        String line = check.ready() ? "✔ Ready to be a " + type : "✘ A " + type + " needs " + dev.eiriksb.stad.planner.Design.join(missing);
        line = font.plainSubstrByWidth(line, viewRight() - 12);
        int y = vh - 24, w = font.width(line) + 12;
        g.fill(2, y - 3, w + 9, y + 11, 0xB0140D09);
        Theme.small(g, check.ready() ? "check" : "cross", 4, y);
        g.drawString(font, line.substring(2), 16, y, check.ready() ? Theme.GOOD : Theme.BAD);
        if (mouseX >= 2 && mouseX < w + 8 && mouseY >= y - 2 && mouseY < y + 10) {
            List<Component> rows = new ArrayList<>();
            rows.add(Component.literal("To be a " + type + ":").withStyle(ChatFormatting.GOLD));
            for (DesignCheck.Row r : check.rows()) {
                rows.add(Component.literal((r.met() ? "✔ " : r.required() ? "✘ " : "· ") + r.what())
                        .withStyle(r.met() ? ChatFormatting.GREEN : r.required() ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
            rows.add(Component.literal(materialsLine()).withStyle(shortBy() == 0 ? ChatFormatting.GRAY : ChatFormatting.GOLD));
            g.renderComponentTooltip(font, rows, mouseX, mouseY);
        }
    }

    /** What the server last said, across the top of the view. */
    private void drawStatus(GuiGraphics g) {
        if (status.isEmpty() || picking || System.currentTimeMillis() - statusAt > (statusError ? 14000 : 8000)) {
            return;
        }
        int w = Math.min(viewRight() - 70, 320);
        List<FormattedCharSequence> lines = font.split(Component.literal(status), w);
        int n = Math.min(3, lines.size());
        int x = (viewRight() - w) / 2, y = TOP + 4;
        Theme.panel(g, x - 8, y - 6, w + 16, n * 10 + 11);
        for (int i = 0; i < n; i++) {
            g.drawCenteredString(font, lines.get(i), x + w / 2, y + i * 10, statusError ? Theme.BAD : 0xFFBFE6FF);
        }
    }

    private void drawHotbar(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < 9; i++) {
            int x = 4 + i * SLOT, y = hotbarY;
            Theme.blit(g, i == slot ? Theme.SLOT_SELECTED : Theme.SLOT, x, y, SLOT, SLOT);
            g.renderItem(icon(hotbar[i].getBlock()), x + 1, y + 1);
        }
        int hovered = slotAt(mouseX, mouseY);
        if (hovered >= 0) {
            g.renderTooltip(font, hotbar[hovered].getBlock().getName(), mouseX, mouseY);
        }
    }

    private int slotAt(double mx, double my) {
        if (my < hotbarY || my >= hotbarY + SLOT || mx < 4 || mx >= 4 + 9 * SLOT) {
            return -1;
        }
        return (int) ((mx - 4) / SLOT);
    }

    // ---- the block picker ---------------------------------------------------------------------------------------

    private static List<Block> allBlocks;

    private static List<Block> allBlocks() {
        if (allBlocks == null) {
            List<Block> out = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                Item item = b.asItem();
                if (item instanceof BlockItem bi && bi.getBlock() == b && Tables.allowed(b.defaultBlockState())) {
                    out.add(b);
                }
            }
            out.add(Blocks.WATER);
            out.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));
            allBlocks = out;
        }
        return allBlocks;
    }

    private void setPicking(boolean on) {
        picking = on;
        pickScroll = 0;
        rebuildWidgets();
    }

    private void filterPicks() {
        String q = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT).strip();
        Set<Block> first = new LinkedHashSet<>();
        TablePayloads.Style st = currentStyle();
        for (String mat : List.of(st.walls(), st.frame(), st.roof(), st.floor())) {
            Materials.get(mat).ifPresent(mm -> {
                for (Block b : new Block[]{mm.full(), mm.stairs(), mm.slab(), mm.log(), mm.fence(), mm.door()}) {
                    if (b != null) {
                        first.add(b);
                    }
                }
            });
        }
        for (Block b : allBlocks()) {
            if (stock.getOrDefault(BuiltInRegistries.ITEM.getKey(b.asItem()).toString(), 0) > 0) {
                first.add(b);
            }
        }
        List<Block> out = new ArrayList<>();
        for (Block b : first) {
            if (matches(b, q)) {
                out.add(b);
            }
        }
        for (Block b : allBlocks()) {
            if (!first.contains(b) && matches(b, q)) {
                out.add(b);
            }
        }
        pickList = out;
    }

    private static boolean matches(Block b, String q) {
        return q.isEmpty() || b.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                || BuiltInRegistries.BLOCK.getKey(b).getPath().contains(q.replace(' ', '_'));
    }

    private int pickColumns() {
        return Math.max(1, (vw - 16) / SLOT);
    }

    private int pickTop() {
        return 30;
    }

    private void drawPicker(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, vw, vh, 0xE0101010);
        searchBox.render(g, mouseX, mouseY, 0);
        int cols = pickColumns(), rows = Math.max(1, (vh - pickTop() - 4) / SLOT);
        int start = pickScroll * cols;
        Block hovered = null;
        for (int i = 0; i < rows * cols && start + i < pickList.size(); i++) {
            Block b = pickList.get(start + i);
            int x = 8 + (i % cols) * SLOT, y = pickTop() + (i / cols) * SLOT;
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                g.fill(x, y, x + SLOT, y + SLOT, 0x60FFFFFF);
                hovered = b;
            }
            g.renderItem(icon(b), x + 1, y + 1);
        }
        g.drawString(font, font.plainSubstrByWidth(pickList.size() + " blocks · click one for slot " + (slot + 1), vw - searchBox.getWidth() - 20),
                searchBox.getWidth() + 14, 12, 0xA0A0A0);
        if (hovered != null) {
            int have = stock.getOrDefault(BuiltInRegistries.ITEM.getKey(hovered.asItem()).toString(), 0);
            g.renderComponentTooltip(font, List.of(hovered.getName(), Component.literal("The town has " + have).withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
    }

    private Block pickAt(double mx, double my) {
        int cols = pickColumns();
        int col = (int) ((mx - 8) / SLOT), row = (int) ((my - pickTop()) / SLOT);
        if (mx < 8 || my < pickTop() || col >= cols) {
            return null;
        }
        int i = (pickScroll + row) * cols + col;
        return i >= 0 && i < pickList.size() ? pickList.get(i) : null;
    }

    // ---- input --------------------------------------------------------------------------------------------------

    private boolean inView(double mx, double my) {
        return mx >= 0 && my >= 0 && mx < (picking ? vw : viewRight()) && my < vh && (picking || my >= TOP);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (choosingType) {
            if (!super.mouseClicked(mx, my, button)) {
                choosingType = false; // a click outside the list closes it
                rebuildWidgets();
            }
            return true;
        }
        if (picking && inView(mx, my)) {
            if (searchBox.mouseClicked(mx, my, button)) {
                setFocused(searchBox);
                return true;
            }
            Block b = pickAt(mx, my);
            if (b != null) {
                hotbar[slot] = b.defaultBlockState();
                turns = 0;
                setPicking(false);
            }
            return true;
        }
        int s = slotAt(mx, my);
        if (s >= 0) {
            slot = s;
            if (button == 1) {
                setPicking(true);
            }
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (inView(mx, my) && model != null && !siteOpen) {
            setFocused(null);
            dragButton = button;
            dragX = mx;
            dragY = my;
            moved = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragButton < 0) {
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        if (!moved && Math.abs(mx - dragX) + Math.abs(my - dragY) > 3) {
            moved = true;
        }
        if (moved) {
            if (dragButton == 0) {
                yaw += (float) dx * 0.6f;
                pitch = Math.max(-89, Math.min(89, pitch + (float) dy * 0.6f));
            } else {
                float k = dist / Math.max(1, vh) * 1.15f;
                double r = Math.toRadians(yaw);
                center.add((float) (-Math.cos(r) * dx * k), (float) (dy * k), (float) (-Math.sin(r) * dx * k));
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragButton >= 0 && button == dragButton) {
            dragButton = -1;
            if (!moved && open.canEdit()) {
                Hit h = pick(mx, my);
                if (h != null) {
                    if (button == 0) {
                        primary(h);
                    } else if (button == 1) {
                        secondary(h);
                    } else if (button == 2) {
                        pickBlock(h.cell());
                    }
                }
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (picking && inView(mx, my)) {
            int rows = (pickList.size() + pickColumns() - 1) / pickColumns();
            pickScroll = Math.max(0, Math.min(Math.max(0, rows - 3), pickScroll - (int) Math.signum(sy)));
            return true;
        }
        if (inView(mx, my) && model != null) {
            if (hasShiftDown()) {
                setCut(cut + (int) Math.signum(sy));
            } else {
                dist = Math.max(3, Math.min(150, dist * (sy > 0 ? 0.88f : 1.12f)));
            }
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (picking && key == GLFW.GLFW_KEY_ESCAPE) {
            setPicking(false);
            return true;
        }
        if (choosingType && key == GLFW.GLFW_KEY_ESCAPE) {
            choosingType = false;
            rebuildWidgets();
            return true;
        }
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER && box == describeBox) {
                describe();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER && box == nameBox) {
                flushName();
                setFocused(null);
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        boolean ctrl = hasControlDown();
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            turns = (turns + (hasShiftDown() ? 3 : 1)) % 4;
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            slot = key - GLFW.GLFW_KEY_1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            setCut(cut + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            setCut(cut - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_E && !ctrl) {
            setPicking(true);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ---- the tools ----------------------------------------------------------------------------------------------

    private void chooseTool(Tool t) {
        if (t == Tool.MARK && tool == Tool.MARK) {
            markKind = (markKind + 1) % MARKERS.size();
        }
        tool = t;
        corner = null;
        roadFrom = null;
        toolAt = System.currentTimeMillis();
        refreshLabels();
    }

    private BlockState selected() {
        return hotbar[slot];
    }

    private void primary(Hit h) {
        switch (tool) {
            case PLACE -> placeAt(h);
            case BREAK -> breakAt(h.cell());
            case PICK -> pickBlock(h.cell());
            case BOX, WALLS, FLOOR, LINE -> corner(h.cell().relative(h.face()), false);
            case COPY -> corner(h.cell(), false);
            case REPLACE -> replace(h.cell(), selected());
            case PASTE -> paste(h.cell().relative(h.face()));
            case MARK -> mark(h.cell().relative(h.face()));
            case ROAD -> road(h.cell());
        }
    }

    private void secondary(Hit h) {
        switch (tool) {
            case ROAD -> {
                if (roadFrom != null) {
                    roadFrom = null;
                    return;
                }
                Map<Integer, String> off = new LinkedHashMap<>();
                model.markers.forEach((k, m) -> {
                    BlockPos g = DesignModel.grid(k);
                    if (m.equals("stad:road") && g.getX() == h.cell().getX() && g.getZ() == h.cell().getZ()) {
                        off.put(k, "");
                    }
                });
                commit(Map.of(), off);
            }
            case BOX, WALLS, FLOOR, LINE -> corner(h.cell(), true);
            case REPLACE -> replace(h.cell(), DesignModel.air());
            case MARK -> {
                BlockPos a = h.cell().relative(h.face());
                int key = model.markers.containsKey(DesignModel.key(a.getX(), a.getY(), a.getZ())) ? DesignModel.key(a.getX(), a.getY(), a.getZ())
                        : DesignModel.key(h.cell().getX(), h.cell().getY(), h.cell().getZ());
                if (model.markers.containsKey(key)) {
                    commit(Map.of(), Map.of(key, ""));
                }
            }
            default -> breakAt(h.cell());
        }
    }

    private void pickBlock(BlockPos cell) {
        BlockState s = model.at(cell.getX(), cell.getY(), cell.getZ());
        if (s.is(Blocks.WATER)) {
            hotbar[slot] = Blocks.WATER.defaultBlockState();
            setStatus("Picked Water.", false);
            return;
        }
        if (s.isAir() || !(s.getBlock().asItem() instanceof BlockItem bi)) {
            return;
        }
        hotbar[slot] = bi.getBlock().defaultBlockState();
        turns = 0;
        setStatus("Picked " + bi.getBlock().getName().getString() + ".", false);
    }

    private void placeAt(Hit h) {
        BlockPos at = h.cell().relative(h.face());
        Map<Integer, BlockState> changes = new LinkedHashMap<>();
        for (Placing.Piece piece : Placing.place(selected().getBlock(), h.face(), look(), h.fracY(), turns)) {
            BlockPos p = at.offset(piece.offset());
            if (!model.inSite(p.getX(), p.getY(), p.getZ())) {
                setStatus("That's outside the site.", true);
                return;
            }
            changes.put(DesignModel.key(p.getX(), p.getY(), p.getZ()), piece.state());
        }
        commit(changes, Map.of());
    }

    private void breakAt(BlockPos cell) {
        if (!model.inSite(cell.getX(), cell.getY(), cell.getZ())) {
            setStatus("That's outside the site: the land round it stays as it is.", true);
            return;
        }
        Map<Integer, BlockState> changes = new LinkedHashMap<>();
        BlockState s = model.at(cell.getX(), cell.getY(), cell.getZ());
        changes.put(DesignModel.key(cell.getX(), cell.getY(), cell.getZ()), DesignModel.air());
        Placing.otherHalf(s, cell).filter(o -> model.inSite(o.getX(), o.getY(), o.getZ()))
                .ifPresent(o -> changes.put(DesignModel.key(o.getX(), o.getY(), o.getZ()), DesignModel.air()));
        commit(changes, Map.of());
    }

    private void corner(BlockPos p, boolean erase) {
        if (corner == null || cornerErase != erase) {
            corner = p;
            cornerErase = erase;
            return;
        }
        BlockPos a = corner;
        corner = null;
        if (tool == Tool.COPY) {
            copy(a, p);
            return;
        }
        BlockState fill = erase ? DesignModel.air() : Placing.place(selected().getBlock(), Direction.UP, look(), 0, turns).getFirst().state();
        Map<Integer, BlockState> changes = new LinkedHashMap<>();
        for (BlockPos c : shape(a, p)) {
            if (model.inSite(c.getX(), c.getY(), c.getZ())) {
                changes.put(DesignModel.key(c.getX(), c.getY(), c.getZ()), fill);
            }
        }
        commit(changes, Map.of());
    }

    private List<BlockPos> shape(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>();
        if (tool == Tool.LINE) {
            int n = Math.max(Math.abs(b.getX() - a.getX()), Math.max(Math.abs(b.getY() - a.getY()), Math.abs(b.getZ() - a.getZ())));
            for (int i = 0; i <= n; i++) {
                double t = n == 0 ? 0 : (double) i / n;
                out.add(BlockPos.containing(a.getX() + 0.5 + (b.getX() - a.getX()) * t, a.getY() + 0.5 + (b.getY() - a.getY()) * t,
                        a.getZ() + 0.5 + (b.getZ() - a.getZ()) * t));
            }
            return out;
        }
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        if (tool == Tool.FLOOR) {
            y0 = y1 = a.getY();
        }
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (tool == Tool.WALLS && x != x0 && x != x1 && z != z0 && z != z1) {
                        continue;
                    }
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    private void replace(BlockPos cell, BlockState to) {
        Block from = model.at(cell.getX(), cell.getY(), cell.getZ()).getBlock();
        if (from == Blocks.AIR) {
            return;
        }
        Map<Integer, BlockState> changes = new LinkedHashMap<>();
        int m = DesignModel.M;
        for (int y = m; y < m + model.sy; y++) {
            for (int z = m; z < m + model.sz; z++) {
                for (int x = m; x < m + model.sx; x++) {
                    BlockState s = model.at(x, y, z);
                    if (s.getBlock() == from) {
                        changes.put(DesignModel.key(x, y, z), to.isAir() ? to : Placing.like(s, to.getBlock()));
                    }
                }
            }
        }
        commit(changes, Map.of());
        setStatus("Replaced " + changes.size() + " " + from.getName().getString().toLowerCase(Locale.ROOT) + ".", false);
    }

    private void copy(BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
        int x1 = Math.max(a.getX(), b.getX()), y1 = Math.max(a.getY(), b.getY()), z1 = Math.max(a.getZ(), b.getZ());
        clip = new LinkedHashMap<>();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (model.inGrid(x, y, z)) {
                        clip.put(new BlockPos(x - x0, y - y0, z - z0), model.at(x, y, z));
                    }
                }
            }
        }
        clipSize = new BlockPos(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        setStatus("Copied " + clipSize.getX() + " × " + clipSize.getY() + " × " + clipSize.getZ() + ". Paste it with the Paste tool.", false);
    }

    private void paste(BlockPos at) {
        if (clip == null) {
            setStatus("Copy something first, with the Copy tool.", true);
            return;
        }
        net.minecraft.world.level.block.Rotation rot = net.minecraft.world.level.block.Rotation.values()[turns % 4];
        net.minecraft.core.Vec3i size = new net.minecraft.core.Vec3i(clipSize.getX(), clipSize.getY(), clipSize.getZ());
        Map<Integer, BlockState> changes = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : clip.entrySet()) {
            BlockPos p = at.offset(dev.eiriksb.stad.building.Placement.rotate(e.getKey(), size, rot));
            if (model.inSite(p.getX(), p.getY(), p.getZ())) {
                changes.put(DesignModel.key(p.getX(), p.getY(), p.getZ()), e.getValue().rotate(rot));
            }
        }
        commit(changes, Map.of());
    }

    /** The road tool: a stretch of road from the last click to this one (or the first block of it), on the ground. */
    private void road(BlockPos cell) {
        if (!model.inSite(cell.getX(), DesignModel.M, cell.getZ())) {
            setStatus("That's outside the site.", true);
            return;
        }
        Map<Integer, String> marks = new LinkedHashMap<>();
        for (int[] c : roadLine(roadFrom != null ? roadFrom : cell, cell)) {
            int g = ground(c[0], c[1]);
            if (g >= 0) {
                marks.put(DesignModel.key(c[0], g, c[1]), "stad:road");
            }
        }
        roadFrom = cell;
        commit(Map.of(), marks);
    }

    /** The columns (grid x and z) from {@code a} to {@code b}, a step at a time, sideways or along, never diagonally. */
    private List<int[]> roadLine(BlockPos a, BlockPos b) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(b.getX() - a.getX()), dz = Math.abs(b.getZ() - a.getZ());
        int sx = Integer.signum(b.getX() - a.getX()), sz = Integer.signum(b.getZ() - a.getZ());
        int x = a.getX(), z = a.getZ();
        out.add(new int[]{x, z});
        for (int ix = 0, iz = 0; ix < dx || iz < dz; ) {
            if (ix < dx && (iz >= dz || (1 + 2L * ix) * dz < (1 + 2L * iz) * dx)) { // whichever keeps nearer the straight line
                ix++;
                x += sx;
            } else {
                iz++;
                z += sz;
            }
            out.add(new int[]{x, z});
        }
        return out;
    }

    /** The top of the land in a column of the site (grid y; not trees), or -1, as the table finds it ({@link Yard}). */
    private int ground(int gx, int gz) {
        int m = DesignModel.M;
        for (int gy = m + model.sy - 1; gy >= m; gy--) {
            BlockState s = model.terrain.get(gx, gy, gz);
            if (!s.getFluidState().isEmpty() || !dev.eiriksb.stad.architect.Fit.open(s) && !s.is(net.minecraft.tags.BlockTags.LEAVES)
                    && !s.is(net.minecraft.tags.BlockTags.LOGS)) {
                return gy;
            }
        }
        return -1;
    }

    private void mark(BlockPos p) {
        if (!model.inSite(p.getX(), p.getY(), p.getZ())) {
            return;
        }
        if (!DesignCheck.passable(model.at(p.getX(), p.getY(), p.getZ()))) {
            setStatus("Markers go in an open space (where someone would stand).", true);
            return;
        }
        int key = DesignModel.key(p.getX(), p.getY(), p.getZ());
        String kind = MARKERS.get(markKind);
        commit(Map.of(), Map.of(key, kind.equals(model.markers.get(key)) ? "" : kind));
    }

    /** Makes a change: mirrored, joined up, applied, sent to the server and kept for undo. */
    private void commit(Map<Integer, BlockState> blocks, Map<Integer, String> marks) {
        Map<Integer, BlockState> changes = new LinkedHashMap<>(blocks);
        Map<Integer, String> markChanges = new LinkedHashMap<>(marks);
        if (mirrorX || mirrorZ) {
            for (Map.Entry<Integer, BlockState> e : blocks.entrySet()) {
                for (int[] mm : mirrors()) {
                    changes.putIfAbsent(mirrored(e.getKey(), mm[0] == 1, mm[1] == 1), Placing.mirror(e.getValue(), mm[0] == 1, mm[1] == 1));
                }
            }
            for (Map.Entry<Integer, String> e : marks.entrySet()) {
                for (int[] mm : mirrors()) {
                    markChanges.putIfAbsent(mirrored(e.getKey(), mm[0] == 1, mm[1] == 1), e.getValue());
                }
            }
        }
        if (!changes.isEmpty()) {
            Placing.connect(model, changes);
        }
        List<Change> list = new ArrayList<>();
        for (Map.Entry<Integer, BlockState> e : changes.entrySet()) {
            BlockState before = model.design.get(e.getKey());
            BlockState now = before != null ? before : model.land(e.getKey());
            if (!now.equals(e.getValue())) {
                list.add(new Change(e.getKey(), before, e.getValue()));
            }
        }
        List<MarkChange> ml = new ArrayList<>();
        for (Map.Entry<Integer, String> e : markChanges.entrySet()) {
            String before = model.markers.get(e.getKey());
            String after = e.getValue().isEmpty() ? null : e.getValue();
            if (!java.util.Objects.equals(before, after)) {
                ml.add(new MarkChange(e.getKey(), before, after));
            }
        }
        if (list.isEmpty() && ml.isEmpty()) {
            return;
        }
        Op op = new Op(list, ml);
        apply(op, true);
        undo.push(op);
        while (undo.size() > 100) {
            undo.removeLast();
        }
        redo.clear();
    }

    private List<int[]> mirrors() {
        List<int[]> out = new ArrayList<>();
        if (mirrorX) {
            out.add(new int[]{1, 0});
        }
        if (mirrorZ) {
            out.add(new int[]{0, 1});
        }
        if (mirrorX && mirrorZ) {
            out.add(new int[]{1, 1});
        }
        return out;
    }

    private int mirrored(int key, boolean x, boolean z) {
        int kx = Project.kx(key), ky = Project.ky(key), kz = Project.kz(key);
        return Project.key(x ? model.sx - 1 - kx : kx, ky, z ? model.sz - 1 - kz : kz);
    }

    private void apply(Op op, boolean forward) {
        int n = op.blocks().size();
        int[] keys = new int[n], states = new int[n];
        for (int i = 0; i < n; i++) {
            Change c = op.blocks().get(i);
            BlockState s = forward ? c.after() : c.before() != null ? c.before() : model.land(c.key());
            model.design.put(c.key(), s);
            keys[i] = c.key();
            states[i] = Block.getId(s);
            if (renderer != null) {
                renderer.mark(Project.ky(c.key()) + DesignModel.M);
            }
        }
        int[] mk = new int[op.marks().size()];
        List<String> mn = new ArrayList<>();
        for (int i = 0; i < mk.length; i++) {
            MarkChange c = op.marks().get(i);
            String s = forward ? c.after() : c.before();
            if (s == null) {
                model.markers.remove(c.key());
            } else {
                model.markers.put(c.key(), s);
            }
            mk[i] = c.key();
            mn.add(s == null ? "" : s);
        }
        int max = TablePayloads.Edit.MAX;
        for (int from = 0; from < Math.max(1, n); from += max) {
            int to = Math.min(n, from + max);
            int[] k = java.util.Arrays.copyOfRange(keys, from, to), st = java.util.Arrays.copyOfRange(states, from, to);
            boolean first = from == 0;
            PacketDistributor.sendToServer(new TablePayloads.Edit(open.table(), k, st, first ? mk : new int[0], first ? mn : List.of()));
        }
        dirtyCheck = true;
    }

    private void undo() {
        if (!undo.isEmpty()) {
            Op op = undo.pop();
            apply(op, false);
            redo.push(op);
        }
    }

    private void redo() {
        if (!redo.isEmpty()) {
            Op op = redo.pop();
            apply(op, true);
            undo.push(op);
        }
    }
}
