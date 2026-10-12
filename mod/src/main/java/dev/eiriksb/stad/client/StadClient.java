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
    }
}
