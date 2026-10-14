package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.Workplaces;
import dev.eiriksb.theywilltalk.api.BeforeReply;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Work at the lakeside fishery" (PLAN.md §6.8): moves a worker to another workplace of their kind, named, or the one
 * the player is standing in ("work here").
 */
public final class WorkplaceTalk {
    private WorkplaceTalk() {}

    private static final Pattern MOVE = Pattern.compile("\\b(?:work|go to work|start working|you(?:'ll| will) work) (?:at|from|in) (?:the )?"
            + "([a-z' ]{3,40}?|here|this one)(?:[.,!?]|$| instead| from now| now| please)", Pattern.CASE_INSENSITIVE);

    /** The context for the reply, or null when this isn't about where they work. Background thread. */
    public static BeforeReply beforeReply(TurnInfo turn) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || turn.kind() != Stad.CITIZEN || turn.player() == null) {
            return null;
        }
        Matcher m = MOVE.matcher(turn.text().strip());
        if (!m.find()) {
            return null;
        }
        String where = m.group(1).strip();
        try {
            List<String> lines = server.submit(() -> handle(server, turn, where)).get(3, TimeUnit.SECONDS);
            return lines == null ? null : BeforeReply.context(lines);
        } catch (Exception e) {
            Stad.LOGGER.debug("WorkplaceTalk: {}", e.toString());
            return null;
        }
    }

    private static List<String> handle(MinecraftServer server, TurnInfo turn, String where) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.player());
        CitizenEntity citizen = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(turn.villager()) instanceof CitizenEntity c) {
                citizen = c;
            }
        }
        if (player == null || citizen == null || citizen.colony().isEmpty() || citizen.record().isEmpty()) {
            return null;
        }
        Colony colony = citizen.colony().get();
        CitizenRecord r = citizen.record().get();
        String who = player.getGameProfile().getName();
        Optional<Building> b = find(colony, player, where);
        if (b.isEmpty()) {
            return null; // not a workplace they know: let the conversation go on as it would
        }
        if (!colony.rank(player.getUUID()).atLeast(OrderKind.ASSIGN_JOB.minRank)) {
            return List.of(who + " wants you to work from the " + b.get().name + ", but they aren't one of the town's officers. Say so kindly.");
        }
        String problem = Workplaces.move(colony, r, b.get());
        if (problem != null) {
            return List.of(who + " wants you to work from the " + b.get().name + ", but you can't: " + problem + " Say so in your own words.");
        }
        return List.of("You work from the " + b.get().name + " now, as " + who + " asked. Say so in a few words.");
    }

    private static Optional<Building> find(Colony colony, ServerPlayer player, String where) {
        String w = where.toLowerCase(Locale.ROOT);
        if (w.equals("here") || w.equals("this one")) {
            return colony.buildings().stream().filter(Workplaces::isWorkplace)
                    .filter(b -> !b.ring() && b.placement().map(p -> p.bounds().isInside(player.blockPosition())).orElse(false)).findFirst();
        }
        return colony.buildings().stream().filter(Workplaces::isWorkplace)
                .filter(b -> b.name.toLowerCase(Locale.ROOT).contains(w) || w.contains(b.name.toLowerCase(Locale.ROOT))).findFirst();
    }
}
