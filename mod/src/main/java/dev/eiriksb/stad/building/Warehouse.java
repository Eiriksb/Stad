package dev.eiriksb.stad.building;

import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A warehouse's shelves (PLAN.md §6.1): each chest (a double chest counts as one) or barrel is for one kind of goods,
 * which is its name ("Food": it shows when it's opened, and on a sign over it). Goods go to their own shelf, then to the
 * "Everything else" one, then wherever there's room ({@link ColonyStorage#put}); and the courier, with nothing else to
 * do, moves what's on the wrong shelf to the right one. A chest named in an anvil after a shelf ("Food", "ores") is
 * that shelf; the rest are given one, the kind with fewest shelves first, as the warehouse is finished.
 */
public final class Warehouse {
    private Warehouse() {}

    /** The kinds of goods, in the order a new warehouse's shelves are given out. */
    public enum Shelf {
        OTHER("Everything else", "other", "misc"),
        BUILDING("Building blocks", "building", "blocks", "stone"),
        WOOD("Wood", "wood", "logs", "planks"),
        ORE("Ores and metals", "ore", "metal", "mining"),
        FOOD("Food", "food", "kitchen"),
        FARM("Farming", "farm", "seeds", "garden"),
        CLOTH("Wool and cloth", "wool", "cloth", "dye"),
        TOOLS("Tools and arms", "tool", "arms", "weapon", "armour", "armor");

        public final String label;
        private final String[] words;

        Shelf(String label, String... words) {
            this.label = label;
            this.words = words;
        }

        /** The shelf a container's name means, if any ("Food", "Ores", "my wood chest"...). */
        public static Shelf named(String name) {
            if (name == null) {
                return null;
            }
            String n = name.toLowerCase(Locale.ROOT);
            for (Shelf s : values()) {
                if (n.equals(s.label.toLowerCase(Locale.ROOT))) {
                    return s;
                }
            }
            for (Shelf s : values()) {
                for (String w : s.words) {
                    if (n.contains(w)) {
                        return s;
                    }
                }
            }
            return null;
        }
    }

    /** The shelf something belongs on. */
    public static Shelf of(ItemStack s) {
        Item i = s.getItem();
        if (s.has(DataComponents.FOOD) || i == Items.WHEAT || i == Items.SUGAR || i == Items.EGG || i == Items.MILK_BUCKET) {
            return Shelf.FOOD;
        }
        if (dev.eiriksb.stad.citizen.Tools.kindOf(s) != null || i instanceof TieredItem || i instanceof ArmorItem || s.isDamageableItem()) {
            return Shelf.TOOLS;
        }
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || i == Items.STICK || s.is(ItemTags.WOODEN_SLABS) || s.is(ItemTags.WOODEN_STAIRS)
                || s.is(ItemTags.WOODEN_FENCES) || s.is(ItemTags.WOODEN_DOORS) || s.is(ItemTags.FENCE_GATES) || s.is(ItemTags.WOODEN_TRAPDOORS)) {
            return Shelf.WOOD;
        }
        if (s.is(ItemTags.SAPLINGS) || s.is(ItemTags.FLOWERS) || i == Items.WHEAT_SEEDS || i == Items.BEETROOT_SEEDS || i == Items.PUMPKIN_SEEDS
                || i == Items.MELON_SEEDS || i == Items.BONE_MEAL || i == Items.SUGAR_CANE || i == Items.CACTUS || i == Items.HAY_BLOCK
                || i == Items.HONEYCOMB || i == Items.GLASS_BOTTLE || i == Items.BONE || i == Items.PUMPKIN || i == Items.MELON) {
            return Shelf.FARM;
        }
        if (s.is(ItemTags.WOOL) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BEDS) || i == Items.STRING || i == Items.LEATHER
                || i == Items.FEATHER || i == Items.RABBIT_HIDE || i instanceof net.minecraft.world.item.DyeItem) {
            return Shelf.CLOTH;
        }
        if (dev.eiriksb.stad.citizen.Smithing.ore(s) || s.is(net.neoforged.neoforge.common.Tags.Items.INGOTS)
                || s.is(net.neoforged.neoforge.common.Tags.Items.NUGGETS) || s.is(net.neoforged.neoforge.common.Tags.Items.GEMS)
                || s.is(net.neoforged.neoforge.common.Tags.Items.RAW_MATERIALS) || s.is(ItemTags.COALS) || i == Items.REDSTONE
                || s.is(net.neoforged.neoforge.common.Tags.Items.ORES) || s.is(net.neoforged.neoforge.common.Tags.Items.STORAGE_BLOCKS)) {
            return Shelf.ORE;
        }
        if (i instanceof BlockItem || i == Items.CLAY_BALL || i == Items.BRICK || i == Items.FLINT) {
            return Shelf.BUILDING;
        }
        return Shelf.OTHER;
    }

    /** The shelf a container is, from its name (null: none, it takes anything). */
    public static Shelf of(Container c) {
        return c instanceof BaseContainerBlockEntity be && be.getCustomName() != null ? Shelf.named(be.getCustomName().getString()) : null;
    }

    /** A shelf: a rack of containers stacked one over another (a double chest's two halves count as one). */
    record Unit(List<BlockPos> parts) {}

    /** A finished warehouse's shelves, from the ground up. */
    static List<Unit> units(ServerLevel level, Building b) {
        Map<List<Long>, List<BlockPos>> racks = new java.util.LinkedHashMap<>();
        for (Unit u : containers(level, b)) {
            List<Long> columns = u.parts().stream().map(p -> ((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL)).sorted().toList();
            racks.computeIfAbsent(columns, k -> new ArrayList<>()).addAll(u.parts());
        }
        return racks.values().stream().map(Unit::new).toList();
    }

    /** Its containers, each double chest as one. */
    private static List<Unit> containers(ServerLevel level, Building b) {
        List<BlockPos> found = new ArrayList<>();
        ColonyStorage.containersOf(level, b).forEach(box -> found.add(box.pos()));
        found.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        List<Unit> out = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (BlockPos p : found) {
            if (!seen.add(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = p.relative(ChestBlock.getConnectedDirection(s));
                if (found.contains(other) && seen.add(other)) {
                    out.add(new Unit(List.of(p, other)));
                    continue;
                }
            }
            out.add(new Unit(List.of(p)));
        }
        return out;
    }

    /**
     * Gives each of the town's warehouses' containers that isn't one yet a shelf (the kind with fewest shelves so far,
     * in {@link Shelf}'s order), and puts its name on it and on a sign over it. Returns how many it named.
     */
    public static int label(ServerLevel level, Colony colony) {
        int named = 0;
        for (Building b : colony.buildings()) {
            if (!b.done() || !b.is(BuildingDef.WAREHOUSE)) {
                continue;
            }
            List<Unit> units = units(level, b);
            Map<Shelf, Integer> have = new EnumMap<>(Shelf.class);
            List<Unit> blank = new ArrayList<>();
            for (Unit u : units) {
                Shelf s = shelf(level, u);
                if (s == null) {
                    blank.add(u);
                } else {
                    have.merge(s, 1, Integer::sum);
                }
            }
            for (Unit u : blank) {
                Shelf s = java.util.Arrays.stream(Shelf.values()).min(Comparator.comparingInt((Shelf k) -> have.getOrDefault(k, 0))
                        .thenComparingInt(Enum::ordinal)).orElseThrow();
                have.merge(s, 1, Integer::sum);
                name(level, u, s);
                named++;
            }
        }
        return named;
    }

    private static Shelf shelf(ServerLevel level, Unit u) {
        for (BlockPos p : u.parts()) {
            if (level.getBlockEntity(p) instanceof Container c && of(c) != null) {
                return of(c);
            }
        }
        return null;
    }

    /** Names a shelf (both halves of a double chest), and writes it on a sign just over it, if there's one. */
    static void name(ServerLevel level, Unit u, Shelf s) {
        Component label = Component.literal(s.label);
        for (BlockPos p : u.parts()) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof BaseContainerBlockEntity) {
                CompoundTag t = be.saveWithoutMetadata(level.registryAccess()); // there's no setter: through its saved form
                t.putString("CustomName", Component.Serializer.toJson(label, level.registryAccess()));
                be.loadWithComponents(t, level.registryAccess());
                be.setChanged();
            }
            BlockPos above = p.above();
            if (level.getBlockEntity(above) instanceof SignBlockEntity sign) { // over the top of the rack
                sign.setText(new SignText().setMessage(1, label), true);
                sign.setChanged();
                level.sendBlockUpdated(above, level.getBlockState(above), level.getBlockState(above), 3);
            }
        }
    }
}
