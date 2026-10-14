package dev.eiriksb.stad.war;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/**
 * Who raids a town (PLAN.md §6.7: "raider factions that fit the biome"): Norse raiders in the cold north, marauders in
 * deserts, badlands and savanna, and bandits or the game's own pillagers everywhere else. Each faction dresses and arms
 * its raiders its own way, and every raid has a named leader.
 */
public enum Faction {
    PILLAGERS("pillagers", "pillager", List.of(), List.of()),
    BANDITS("bandits", "bandit",
            List.of("Jack", "Moll", "Silas", "Rook", "Mags", "Crispin", "Tam", "Wren", "Nell", "Garrick"),
            List.of("One-Eye", "the Knife", "Blackheart", "Quickfingers", "the Fox", "Crowfoot")),
    NORSE("Norse raiders", "Norse raider",
            List.of("Ivar", "Ragnar", "Sigurd", "Halfdan", "Thorfinn", "Ulf", "Gunnhild", "Harald", "Eric", "Astrid"),
            List.of("the Bold", "Ironside", "Bloodaxe", "the Grim", "the Red", "Wolfcloak")),
    MARAUDERS("marauders", "marauder",
            List.of("Kesh", "Varo", "Imra", "Tazu", "Orhan", "Selka", "Doran", "Ysra"),
            List.of("of the Dunes", "the Scorpion", "Sandstorm", "the Vulture", "Sunscorch"));

    /** "Norse raiders", "bandits". */
    public final String plural;
    public final String one;
    private final List<String> names;
    private final List<String> epithets;

    Faction(String plural, String one, List<String> names, List<String> epithets) {
        this.plural = plural;
        this.one = one;
        this.names = names;
        this.epithets = epithets;
    }

    /** Who comes for this town: by the biome its town hall stands in. */
    public static Faction of(ServerLevel level, Colony colony, RandomSource random) {
        if (!StadConfig.RAIDER_FACTIONS.get()) {
            return PILLAGERS;
        }
        Holder<Biome> biome = level.getBiome(colony.center);
        if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Tags.Biomes.IS_SNOWY) || biome.is(Tags.Biomes.IS_COLD_OVERWORLD)) {
            return NORSE;
        }
        if (biome.is(Tags.Biomes.IS_DESERT) || biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_SAVANNA)) {
            return MARAUDERS;
        }
        return random.nextBoolean() ? BANDITS : PILLAGERS;
    }

    /** A name for a raid's leader: "Ivar Ironside". */
    public String leaderName(RandomSource random) {
        return names.get(random.nextInt(names.size())) + " " + epithets.get(random.nextInt(epithets.size()));
    }

    /** What a raider carries: mostly a weapon to swing, sometimes a bow. */
    ItemStack weapon(RandomSource random) {
        return switch (this) {
            case BANDITS -> random.nextFloat() < 0.4f ? new ItemStack(Items.BOW) : new ItemStack(Items.IRON_SWORD);
            case NORSE -> random.nextFloat() < 0.3f ? new ItemStack(Items.BOW) : new ItemStack(Items.IRON_AXE);
            case MARAUDERS -> random.nextFloat() < 0.5f ? new ItemStack(Items.BOW) : new ItemStack(Items.GOLDEN_SWORD);
            case PILLAGERS -> new ItemStack(Items.CROSSBOW);
        };
    }

    /** What a raider wears in {@code slot}; the leader gets the best of it. */
    ItemStack wear(EquipmentSlot slot, boolean leader) {
        return switch (this) {
            case BANDITS -> switch (slot) {
                case HEAD -> dyed(Items.LEATHER_HELMET, 0x2b2b2b);
                case CHEST -> leader ? new ItemStack(Items.CHAINMAIL_CHESTPLATE) : dyed(Items.LEATHER_CHESTPLATE, 0x3b4a2a);
                case LEGS -> dyed(Items.LEATHER_LEGGINGS, 0x2b2b2b);
                case FEET -> dyed(Items.LEATHER_BOOTS, 0x3a2a1a);
                default -> ItemStack.EMPTY;
            };
            case NORSE -> switch (slot) {
                case HEAD -> new ItemStack(Items.IRON_HELMET);
                case CHEST -> leader ? new ItemStack(Items.IRON_CHESTPLATE) : dyed(Items.LEATHER_CHESTPLATE, 0x6b4a2b);
                case LEGS -> dyed(Items.LEATHER_LEGGINGS, 0x4a5a6a);
                case FEET -> dyed(Items.LEATHER_BOOTS, 0x4a3b2a);
                case OFFHAND -> leader ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY;
                default -> ItemStack.EMPTY;
            };
            case MARAUDERS -> switch (slot) {
                case HEAD -> leader ? new ItemStack(Items.GOLDEN_HELMET) : dyed(Items.LEATHER_HELMET, 0xd8c08a);
                case CHEST -> dyed(Items.LEATHER_CHESTPLATE, 0xb5652e);
                case LEGS -> dyed(Items.LEATHER_LEGGINGS, 0xd8c08a);
                case FEET -> dyed(Items.LEATHER_BOOTS, 0x8a6a3a);
                default -> ItemStack.EMPTY;
            };
            case PILLAGERS -> ItemStack.EMPTY;
        };
    }

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, false));
        return s;
    }
}
