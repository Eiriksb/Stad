package dev.eiriksb.stad.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tells a town's members (players) something. */
public final class Announce {
    private Announce() {}

    public static void members(MinecraftServer server, Colony colony, Component message) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (colony.rank(p.getUUID()).atLeast(Rank.MEMBER)) {
                p.sendSystemMessage(message);
            }
        }
    }

    /** Tells the officers and owner (the ones who can do something about it). */
    public static void leaders(MinecraftServer server, Colony colony, Component message) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (colony.rank(p.getUUID()).atLeast(Rank.OFFICER)) {
                p.sendSystemMessage(message);
            }
        }
    }
}
