package dev.eiriksb.stad.twt;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import dev.eiriksb.stad.Stad;

/**
 * Simple Voice Chat plugin (found by SVC through the annotation, only when it's installed): every bit of a voice the
 * player hears from an entity goes to {@link dev.eiriksb.stad.client.LipSync}, which moves citizens' mouths to it
 * (anyone else's is ignored there).
 */
@ForgeVoicechatPlugin
public final class StadVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return Stad.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(ClientReceiveSoundEvent.EntitySound.class, e -> dev.eiriksb.stad.client.LipSync.heard(e.getEntityId(), e.getRawAudio()));
    }
}
