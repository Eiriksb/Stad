package dev.eiriksb.stad.block;

import dev.eiriksb.stad.StadRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The town hall's storage: 27 slots, where citizens go to eat (and later fetch what the town needs). It's a container,
 * so hoppers and item commands work with it.
 */
public class TownHallBlockEntity extends BlockEntity implements Container {
    private final SimpleContainer storage = new SimpleContainer(27) {
        @Override
        public void setChanged() {
            super.setChanged();
            TownHallBlockEntity.this.setChanged();
        }
    };

    public TownHallBlockEntity(BlockPos pos, BlockState state) {
        super(StadRegistries.TOWN_HALL_ENTITY.get(), pos, state);
    }

    public SimpleContainer storage() {
        return storage;
    }

    @Override
    public int getContainerSize() {
        return storage.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return storage.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return storage.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return storage.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        storage.setItem(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        storage.clearContent();
    }

    /** Takes one piece of food, or an empty stack when there's none. */
    public ItemStack takeFood() {
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack s = storage.getItem(i);
            if (!s.isEmpty() && s.has(DataComponents.FOOD)) {
                ItemStack one = s.split(1);
                storage.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean hasFood() {
        for (int i = 0; i < storage.getContainerSize(); i++) {
            if (storage.getItem(i).has(DataComponents.FOOD)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Storage", storage.createTag(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storage.fromTag(tag.getList("Storage", Tag.TAG_COMPOUND), registries);
    }
}
