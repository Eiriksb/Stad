package dev.eiriksb.stad.block;

import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.architect.Project;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Keeps the architect's table's project. Broken, the table keeps it on the item. */
public class ArchitectTableBlockEntity extends BlockEntity {
    private Project project = new Project();

    public ArchitectTableBlockEntity(BlockPos pos, BlockState state) {
        super(StadRegistries.ARCHITECT_TABLE_ENTITY.get(), pos, state);
    }

    public Project project() {
        return project;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Project", project.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        project = tag.contains("Project") ? Project.load(tag.getCompound("Project")) : new Project();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (project.hasSite()) { // the loot table copies this onto the dropped table
            CompoundTag t = new CompoundTag();
            t.put("Project", project.save());
            t.putString("id", String.valueOf(BlockEntityType.getKey(getType())));
            components.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(t));
        }
    }
}
