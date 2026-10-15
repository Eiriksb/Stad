package dev.eiriksb.stad.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;

import java.util.Locale;
import java.util.UUID;

/**
 * Something the town needs brought somewhere: food for the town hall, materials for the builder. Couriers fetch it
 * from the warehouse; players can bring it themselves. (PLAN.md §6.1, "Requests and logistics".)
 *
 * @param what an item id, or {@link #FOOD} for any food
 */
public final class Request {
    public static final String FOOD = "#food";

    public final int id;
    public final String what;
    public int count;
    /** the container it goes into */
    public final BlockPos dest;
    public final String destName;
    public final String reason;
    public final long created;
    /** the courier on it (not saved) */
    public UUID courier;

    Request(int id, String what, int count, BlockPos dest, String destName, String reason, long created) {
        this.id = id;
        this.what = what;
        this.count = count;
        this.dest = dest;
        this.destName = destName;
        this.reason = reason;
        this.created = created;
    }

    public boolean matches(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        if (FOOD.equals(what)) {
            return Homes.edible(s);
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(what);
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** "16 food", "12 oak planks" */
    public String label() {
        if (FOOD.equals(what)) {
            return count + " food";
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(what));
        return count + " " + item.getDescription().getString().toLowerCase(Locale.ROOT);
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", id);
        t.putString("What", what);
        t.putInt("Count", count);
        t.put("Dest", NbtUtils.writeBlockPos(dest));
        t.putString("DestName", destName);
        t.putString("Reason", reason);
        t.putLong("Created", created);
        return t;
    }

    static Request load(CompoundTag t) {
        return new Request(t.getInt("Id"), t.getString("What"), t.getInt("Count"), NbtUtils.readBlockPos(t, "Dest").orElse(BlockPos.ZERO),
                t.getString("DestName"), t.getString("Reason"), t.getLong("Created"));
    }
}
