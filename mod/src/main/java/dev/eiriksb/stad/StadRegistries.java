package dev.eiriksb.stad;

import dev.eiriksb.stad.block.TownHallBlock;
import dev.eiriksb.stad.block.TownHallBlockEntity;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.item.BuildingPlansItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything Stad adds to the game's registries. */
public final class StadRegistries {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Stad.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Stad.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Stad.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Stad.MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Stad.MOD_ID);

    /** the area marked out with the building plans */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>, net.minecraft.core.component.DataComponentType<
            dev.eiriksb.stad.item.PlanArea>> PLAN_AREA = COMPONENTS.registerComponentType("plan_area",
            b -> b.persistent(dev.eiriksb.stad.item.PlanArea.CODEC).networkSynchronized(dev.eiriksb.stad.item.PlanArea.STREAM_CODEC));

    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN = ENTITIES.register("citizen",
            () -> EntityType.Builder.of(CitizenEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .clientTrackingRange(10)
                    .build("citizen"));

    public static final DeferredHolder<EntityType<?>, EntityType<dev.eiriksb.stad.war.RaiderEntity>> RAIDER = ENTITIES.register("raider",
            () -> EntityType.Builder.of(dev.eiriksb.stad.war.RaiderEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .clientTrackingRange(10)
                    .build("raider"));

    public static final DeferredBlock<TownHallBlock> TOWN_HALL = BLOCKS.registerBlock("town_hall", TownHallBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(3.0f, 1200.0f).sound(SoundType.WOOD).noOcclusion());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownHallBlockEntity>> TOWN_HALL_ENTITY =
            BLOCK_ENTITIES.register("town_hall", () -> BlockEntityType.Builder.of(TownHallBlockEntity::new, TOWN_HALL.get()).build(null));

    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM = ITEMS.registerSimpleBlockItem(TOWN_HALL);

    public static final DeferredBlock<dev.eiriksb.stad.block.ArchitectTableBlock> ARCHITECT_TABLE = BLOCKS.registerBlock("architect_table",
            dev.eiriksb.stad.block.ArchitectTableBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).noOcclusion());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.eiriksb.stad.block.ArchitectTableBlockEntity>> ARCHITECT_TABLE_ENTITY =
            BLOCK_ENTITIES.register("architect_table", () -> BlockEntityType.Builder.of(dev.eiriksb.stad.block.ArchitectTableBlockEntity::new,
                    ARCHITECT_TABLE.get()).build(null));

    public static final DeferredItem<BlockItem> ARCHITECT_TABLE_ITEM = ITEMS.registerSimpleBlockItem(ARCHITECT_TABLE);

    public static final DeferredItem<BuildingPlansItem> BUILDING_PLANS = ITEMS.registerItem("building_plans", BuildingPlansItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<dev.eiriksb.stad.item.ScanWandItem> SCAN_WAND = ITEMS.registerItem("scan_wand",
            dev.eiriksb.stad.item.ScanWandItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<Item> CITIZEN_SPAWN_EGG = ITEMS.register("citizen_spawn_egg",
            () -> new DeferredSpawnEggItem(CITIZEN, 0x9c6b3e, 0x3f7a4c, new Item.Properties()));

    private StadRegistries() {}

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(StadRegistries::attributes);
        modBus.addListener(StadRegistries::creativeTabs);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(CITIZEN.get(), CitizenEntity.createAttributes().build());
        event.put(RAIDER.get(), dev.eiriksb.stad.war.RaiderEntity.createAttributes().build());
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(CITIZEN_SPAWN_EGG);
        } else if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(TOWN_HALL_ITEM);
            event.accept(ARCHITECT_TABLE_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(BUILDING_PLANS);
            event.accept(SCAN_WAND);
        }
    }
}
