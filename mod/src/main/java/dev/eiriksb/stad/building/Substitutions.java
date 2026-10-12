package dev.eiriksb.stad.building;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.pack.Roles;
import dev.eiriksb.stad.planner.Materials;
import dev.eiriksb.stad.twt.StadConversations;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Making do (PLAN.md §6.3): when a builder runs out of a material and the town has enough of a similar one (another
 * wood for wood, another stone for stone), they ask the leaders: "We're out of spruce. Want me to use birch for the
 * walls?" A yes swaps it for the rest of the building. Server thread.
 */
public final class Substitutions {
    private Substitutions() {}

    /**
     * From step {@code fromStep} on, {@code from} is built in {@code to} (material names), in the shapes listed (all
     * of them when empty: planks, stairs, slabs...).
     */
    public record Swap(String from, String to, int fromStep, java.util.Set<Materials.Shape> shapes) {
        public Swap {
            shapes = shapes.isEmpty() ? java.util.Set.of() : java.util.EnumSet.copyOf(shapes);
        }

        public boolean covers(Materials.Shape shape) {
            return shapes.isEmpty() || shapes.contains(shape);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("From", from);
            t.putString("To", to);
            t.putInt("Step", fromStep);
            t.putString("Shapes", String.join(",", shapes.stream().map(Enum::name).toList()));
            return t;
        }

        public static Swap load(CompoundTag t) {
            java.util.Set<Materials.Shape> shapes = java.util.EnumSet.noneOf(Materials.Shape.class);
            for (String s : t.getString("Shapes").split(",")) {
                try {
                    shapes.add(Materials.Shape.valueOf(s));
                } catch (IllegalArgumentException ignored) {
                    // empty, or a shape that's gone
                }
            }
            return new Swap(t.getString("From"), t.getString("To"), t.getInt("Step"), shapes);
        }
    }

    /** A swap the builder could make, for the shapes the town is short of: {@code part} is what it's for ("walls", "roof"). */
    public record Offer(Materials.Material from, Materials.Material to, String part, java.util.Set<Materials.Shape> shapes) {
        public String key() {
            return from.name() + ">" + to.name();
        }

        /** "use birch instead of spruce for the walls" */
        public String describe() {
            return "use " + to.label() + " instead of " + from.label() + " for the " + part;
        }
    }

    /** The placement with the swaps made (each from its step on). */
    public static Placement apply(Placement p, List<Swap> swaps) {
        List<Placement.Step> steps = new ArrayList<>(p.steps());
        for (Swap s : swaps) {
            Materials.Material from = Materials.get(s.from()).orElse(null), to = Materials.get(s.to()).orElse(null);
            if (from == null || to == null) {
                continue;
            }
            for (int i = Math.max(0, s.fromStep()); i < steps.size(); i++) {
                Placement.Step st = steps.get(i);
                var known = Materials.of(st.state().getBlock());
                if (st.clearing() || known.isEmpty() || known.get().getKey() != from || !s.covers(known.get().getValue())) {
                    continue;
                }
                BlockState ns = Roles.restyle(st.state(), to);
                if (ns != st.state()) {
                    Item item = ns.getBlock().asItem();
                    steps.set(i, new Placement.Step(st.pos(), ns, item, Placement.cost(ns, item)));
                }
            }
        }
        return new Placement(p.origin(), p.rotation(), p.size(), List.copyOf(steps), p.markers());
    }

    /** What the builder could use instead of something the town has run out of, or null. */
    public static Offer offer(ServerLevel level, Colony colony, Building b) {
        Placement p = b.placement().orElse(null);
        if (p == null || b.missing.isEmpty()) {
            return null;
        }
        Map<Materials.Material, Map<Materials.Shape, Integer>> need = new LinkedHashMap<>();
        List<Placement.Step> steps = p.steps();
        for (int i = b.progress; i < steps.size(); i++) {
            Placement.Step s = steps.get(i);
            if (s.clearing() || s.count() == 0 || Construction.satisfied(level, s)) {
                continue;
            }
            Materials.of(s.state().getBlock()).ifPresent(k -> need.computeIfAbsent(k.getKey(), x -> new EnumMap<>(Materials.Shape.class))
                    .merge(k.getValue(), s.count(), Integer::sum));
        }
        ColonyStorage supply = ColonyStorage.supply(level, colony), warehouses = ColonyStorage.warehouses(level, colony);
        // what's short, material by material: only those shapes are swapped (the oak door can stay oak)
        Map<Materials.Material, java.util.Set<Materials.Shape>> shortOf = new LinkedHashMap<>();
        for (Item item : b.missing.keySet()) {
            Materials.of(Block.byItem(item)).ifPresent(k -> shortOf.computeIfAbsent(k.getKey(), x -> java.util.EnumSet.noneOf(Materials.Shape.class))
                    .add(k.getValue()));
        }
        for (Map.Entry<Materials.Material, java.util.Set<Materials.Shape>> s : shortOf.entrySet()) {
            Materials.Material from = s.getKey();
            if (!need.containsKey(from)) {
                continue;
            }
            Map<Materials.Shape, Integer> wanted = new EnumMap<>(Materials.Shape.class);
            need.get(from).forEach((shape, n) -> {
                if (s.getValue().contains(shape)) {
                    wanted.put(shape, n);
                }
            });
            Materials.Material best = null;
            int bestStock = -1;
            for (String name : Materials.names()) {
                Materials.Material to = Materials.get(name).orElseThrow();
                if (to == from || to.wood() != from.wood() || b.declined.contains(from.name() + ">" + to.name())) {
                    continue;
                }
                int stock = 0;
                boolean enough = true;
                for (Map.Entry<Materials.Shape, Integer> e : wanted.entrySet()) {
                    Block block = to.block(e.getKey());
                    int have = block == null ? 0 : supply.count(block.asItem()) + warehouses.count(block.asItem());
                    if (have < e.getValue()) {
                        enough = false;
                        break;
                    }
                    stock += have;
                }
                if (enough && stock > bestStock) {
                    best = to;
                    bestStock = stock;
                }
            }
            if (best != null) {
                return new Offer(from, best, part(wanted), wanted.keySet());
            }
        }
        return null;
    }

    private static String part(Map<Materials.Shape, Integer> wanted) {
        Materials.Shape most = wanted.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Materials.Shape.FULL);
        return switch (most) {
            case STAIRS, SLAB -> "roof";
            case LOG -> "frame";
            case FENCE -> "fences";
            case DOOR -> "doors";
            default -> wanted.size() > 2 ? "rest of it" : "walls";
        };
    }

    /** The builder asks: speaks to whoever's near, and puts the question with buttons in the leaders' chat. */
    public static void ask(ServerLevel level, Colony colony, Building b, CitizenEntity builder) {
        Offer o = b.offer;
        String who = builder.getName().getString();
        StadConversations.speakUp(builder, 24, "(You're building the " + b.name + " but the town has run out of " + o.from().label()
                + ". There's enough " + o.to().label() + ", though. Ask {player} whether you should " + o.describe() + ", in a sentence or two.)");
        MutableComponent line = Component.literal("[" + colony.name() + "] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(who + " (builder): We're out of " + o.from().label() + " for the " + b.name + ". Shall I use "
                        + o.to().label() + " for the " + o.part + " instead? ").withStyle(ChatFormatting.YELLOW))
                .append(button("Use " + o.to().label(), "/stad build swap " + b.id, "Build the rest in " + o.to().label(), ChatFormatting.GREEN))
                .append(button("Wait for " + o.from().label(), "/stad build keep " + b.id, "Keep to the plan; bring more " + o.from().label(),
                        ChatFormatting.RED));
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (colony.rank(p.getUUID()).atLeast(Rank.OFFICER)) {
                p.sendSystemMessage(line);
            }
        }
    }

    private static Component button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal("[" + label + "] ").withStyle(s -> s.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /** Yes to the builder's question; returns why not, or null when it's done. */
    public static String accept(ServerPlayer player, Colony colony, Building b) {
        if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers of " + colony.name() + " can change what the builders use.";
        }
        if (b.offer == null) {
            return "The builder hasn't asked about the " + b.name + ".";
        }
        b.swaps.add(new Swap(b.offer.from().name(), b.offer.to().name(), b.progress, b.offer.shapes()));
        b.offer = null;
        b.replan();
        b.missing.clear();
        b.lastAsked = -100_000;
        colony.changed();
        return null;
    }

    /** No: keep to the plan (and don't ask about that swap again). */
    public static String decline(ServerPlayer player, Colony colony, Building b) {
        if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return "Only officers of " + colony.name() + " can change what the builders use.";
        }
        if (b.offer == null) {
            return "The builder hasn't asked about the " + b.name + ".";
        }
        b.declined.add(b.offer.key());
        b.offer = null;
        return null;
    }

    private static final Pattern YES = Pattern.compile("^\\W*(yes|yeah|yep|yup|sure|ok|okay|alright|fine|go ahead|do it|please do|"
            + "of course|absolutely|why not|sounds good|use (it|them|that|those|the))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile("^\\W*(no|nope|nah|don't|do not|wait|rather not|keep|stick)\\b|\\b(don't|do not) use\\b|"
            + "\\bwait for\\b|\\bstick (to|with)\\b", Pattern.CASE_INSENSITIVE);

    /** Whether something said answers the builder's question: true for yes, false for no, null for neither. */
    public static Boolean answer(String text, Offer o) {
        String t = text.toLowerCase(Locale.ROOT);
        if (NO.matcher(t).find()) {
            return false;
        }
        if (YES.matcher(t).find() || t.matches(".*\\buse (the )?" + Pattern.quote(o.to().label()) + "\\b.*")) {
            return true;
        }
        return null;
    }
}
