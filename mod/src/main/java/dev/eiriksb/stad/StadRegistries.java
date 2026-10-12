package dev.eiriksb.stad;

import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything Stad adds to the game's registries. */
public final class StadRegistries {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Stad.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Stad.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN = ENTITIES.register("citizen",
            () -> EntityType.Builder.of(CitizenEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .clientTrackingRange(10)
                    .build("citizen"));

    public static final DeferredItem<Item> CITIZEN_SPAWN_EGG = ITEMS.register("citizen_spawn_egg",
            () -> new DeferredSpawnEggItem(CITIZEN, 0x9c6b3e, 0x3f7a4c, new Item.Properties()));

    private StadRegistries() {}

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(StadRegistries::attributes);
        modBus.addListener(StadRegistries::creativeTabs);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(CITIZEN.get(), CitizenEntity.createAttributes().build());
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(CITIZEN_SPAWN_EGG);
        }
    }
}
