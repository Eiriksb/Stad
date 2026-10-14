package dev.eiriksb.stad;

import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.colony.ColonyProtection;
import dev.eiriksb.stad.colony.ColonyTicker;
import dev.eiriksb.stad.command.StadCommands;
import dev.eiriksb.stad.net.StadNetwork;
import dev.eiriksb.stad.twt.StadAdapter;
import dev.eiriksb.stad.twt.StadConversations;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TwtReadyEvent;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stad - build a town with citizens who live, work and talk. Talking, memory, voices and the AI runtime come from
 * They Will Talk; Stad adds the citizens, colonies, jobs and buildings.
 */
@Mod(Stad.MOD_ID)
public final class Stad {
    public static final String MOD_ID = "stad";
    public static final Logger LOGGER = LoggerFactory.getLogger("Stad");

    /** Stad's citizens, as They Will Talk sees them. Stored in TWT's database as kind "stad". */
    public static final VillagerKind CITIZEN = TheyWillTalkApi.get().registerKind("stad", "Citizen",
            () -> StadConfig.CITIZENS_TALK.get());

    public Stad(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, StadConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, StadClientConfig.SPEC);
        StadRegistries.register(modBus);
        StadNetwork.register(modBus);
        TheyWillTalkApi.get().registerAdapter(new StadAdapter());
        TheyWillTalkApi.get().addConversationHook(new StadConversations());
        ColonyTicker.register();
        dev.eiriksb.stad.colony.ColonyChunks.register(modBus);
        BuildingPacks.register();
        dev.eiriksb.stad.pack.Palettes.register();
        dev.eiriksb.stad.pack.SharedPacks.register(modBus);
        dev.eiriksb.stad.item.ScanWandItem.register();
        dev.eiriksb.stad.architect.FreeBuild.register();
        ColonyProtection.register();
        dev.eiriksb.stad.agent.Leadership.register();
        dev.eiriksb.stad.war.Raids.register();
        dev.eiriksb.stad.war.Wars.register();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> StadCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(this::onTwtReady);
    }

    private void onTwtReady(TwtReadyEvent event) {
        LOGGER.info("They Will Talk is ready (API v{}); citizens talk as kind '{}'", TheyWillTalkApi.VERSION, CITIZEN.id);
    }
}
