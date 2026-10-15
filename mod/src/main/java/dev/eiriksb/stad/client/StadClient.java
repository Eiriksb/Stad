package dev.eiriksb.stad.client;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.StadRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only setup. */
@EventBusSubscriber(modid = Stad.MOD_ID, value = Dist.CLIENT)
public final class StadClient {
    private StadClient() {}

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(StadRegistries.CITIZEN.get(), CitizenRenderer::new);
        event.registerEntityRenderer(StadRegistries.RAIDER.get(), RaiderRenderer::new);
    }

    @SubscribeEvent
    static void screens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(StadRegistries.CITIZEN_MENU.get(), CitizenGearScreen::new);
    }

    @SubscribeEvent
    static void guiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "draft"), GhostPreview::hud);
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "placing"), BuildingPlacer::hud);
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "guide"), GuideHud::hud);
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "drafting"),
                dev.eiriksb.stad.client.architect.DraftingHud::hud);
    }

    /** Citizens are put together from the resources: again when they change. */
    @SubscribeEvent
    static void reloads(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> CitizenSkins.clear());
    }

    @SubscribeEvent
    static void loggedOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        CitizenSkins.clear();
        LipSync.clear();
    }

    @SubscribeEvent
    static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(dev.eiriksb.stad.client.architect.DraftingHud.FINISH);
        BuildingPlacer.KEYS.forEach(event::register);
        event.register(GuideHud.TOGGLE);
    }
}
