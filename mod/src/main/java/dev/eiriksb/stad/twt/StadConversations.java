package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.theywilltalk.api.ConversationHook;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Stad's side of conversations: "this is your home now" ({@link AdoptionTalk}), builders draw up buildings when players talk about them ({@link
 * dev.eiriksb.stad.planner.Architect}), talking with a player is company, and citizens speak up when something happens.
 */
public final class StadConversations implements ConversationHook {
    @Override
    public dev.eiriksb.theywilltalk.api.BeforeReply beforeReply(TurnInfo turn) {
        dev.eiriksb.theywilltalk.api.BeforeReply envoy = EnvoyTalk.beforeReply(turn);
        if (envoy != null) {
            return envoy;
        }
        dev.eiriksb.theywilltalk.api.BeforeReply moved = WorkplaceTalk.beforeReply(turn);
        if (moved != null) {
            return moved;
        }
        dev.eiriksb.theywilltalk.api.BeforeReply given = AdoptionTalk.beforeReply(turn);
        return given != null ? given : dev.eiriksb.stad.planner.Architect.beforeReply(turn);
    }

    @Override
    public void afterReply(TurnInfo turn, String reply, String emotion) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || turn.kind() != dev.eiriksb.stad.Stad.CITIZEN) {
            return;
        }
        dev.eiriksb.stad.citizen.CitizenScreens.reply(server, turn, reply);
        server.execute(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(turn.villager()) instanceof CitizenEntity c) {
                    c.hadCompany();
                    if (!SPOKE) {
                        show(c, reply, emotion); // an older They Will Talk, without spoke(): replies to players at least
                    }
                }
            }
        });
    }

    /** whether They Will Talk tells hooks about every line said ({@link #spoke}, since 0.3.0-alpha.9) */
    private static final boolean SPOKE = java.util.Arrays.stream(ConversationHook.class.getMethods()).anyMatch(m -> m.getName().equals("spoke"));

    /** Any line a citizen says (a reply, or chatting with another): their face shows it. */
    @Override
    public void spoke(java.util.UUID villager, String text, String emotion) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(villager) instanceof CitizenEntity c) {
                show(c, text, emotion);
            }
        }
    }

    /** Tells the players who can see a citizen's face what they said and how they felt (to mouth it when there's no voice). */
    private static void show(CitizenEntity c, String text, String emotion) {
        var line = new dev.eiriksb.stad.net.CitizenSpeechPayload(c.getUUID(), text, emotion == null ? "neutral" : emotion);
        for (ServerPlayer p : ((ServerLevel) c.level()).players()) {
            if (p.distanceToSqr(c) < 32 * 32 && p.connection.hasChannel(dev.eiriksb.stad.net.CitizenSpeechPayload.TYPE)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, line);
            }
        }
    }

    /**
     * {@code citizen} tells the nearest member of their town (within {@code range}) about something, in their own words
     * and voice: {@code instruction} says what. Server thread. Returns whether someone was there to hear it.
     */
    public static boolean speakUp(CitizenEntity citizen, double range, String instruction) {
        var colony = citizen.colony().orElse(null);
        if (colony == null) {
            return false;
        }
        ServerPlayer nearest = null;
        for (ServerPlayer p : ((ServerLevel) citizen.level()).players()) {
            if (colony.rank(p.getUUID()).atLeast(dev.eiriksb.stad.colony.Rank.MEMBER) && p.distanceTo(citizen) <= range
                    && (nearest == null || p.distanceTo(citizen) < nearest.distanceTo(citizen))) {
                nearest = p;
            }
        }
        return nearest != null && TheyWillTalkApi.get().speak(citizen, nearest, instruction.replace("{player}", nearest.getGameProfile().getName()));
    }

    static boolean isCitizen(Entity e) {
        return e instanceof CitizenEntity;
    }
}
