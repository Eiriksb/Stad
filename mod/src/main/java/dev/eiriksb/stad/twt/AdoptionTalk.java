package dev.eiriksb.stad.twt;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Adoption;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.theywilltalk.api.BeforeReply;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "This is your home now" (PLAN.md §6.5): said to a citizen while standing in a building, the building is surveyed.
 * If it has what it needs it becomes the town's and theirs (an order, so they may still refuse a leader they resent);
 * if not, they say what's missing ("I'd need a bed in here").
 */
public final class AdoptionTalk {
    private AdoptionTalk() {}

    private static final Pattern GIVE = Pattern.compile("\\b(?:(?:this|here|it)(?:'s| is| will be| can be|s)? (?:now )?(?:your|yours|you're)"
            + "(?: (?:new|own))? (home|house|place|room|cottage|cabin|hut|workshop|warehouse|storehouse|barracks|watchtower|tower)|(?:this|it) is yours"
            + "|you (?:can|could|may|should) (?:live|stay|sleep|move in|settle) (?:in )?here|move in(?:to)? (?:here|this)"
            + "|make (?:this|it) your (home|house|workshop|hut|warehouse|barracks|watchtower|tower))", Pattern.CASE_INSENSITIVE);

    /** The context for the reply, or null when this isn't about giving someone a building. Background thread. */
    public static BeforeReply beforeReply(TurnInfo turn) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || turn.kind() != Stad.CITIZEN || turn.player() == null) {
            return null;
        }
        Matcher m = GIVE.matcher(turn.text());
        if (!m.find()) {
            return null;
        }
        String word = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            List<String> lines = server.submit(() -> handle(server, turn, word)).get(3, TimeUnit.SECONDS);
            return lines == null ? null : BeforeReply.context(lines);
        } catch (Exception e) {
            Stad.LOGGER.debug("AdoptionTalk: {}", e.toString());
            return null;
        }
    }

    private static List<String> handle(MinecraftServer server, TurnInfo turn, String word) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.player());
        CitizenEntity citizen = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(turn.villager()) instanceof CitizenEntity c) {
                citizen = c;
            }
        }
        if (player == null || citizen == null || citizen.colony().isEmpty()) {
            return null;
        }
        Colony colony = citizen.colony().get();
        ServerLevel level = player.serverLevel();
        String who = player.getGameProfile().getName();
        ResourceLocation type = Requirements.typeOf(word);
        if (type == null) {
            type = BuildingDef.HOME;
        }
        String kind = Requirements.word(type);
        Adoption.Check c = Adoption.check(level, colony, player.blockPosition(), type);
        List<String> lines = new ArrayList<>();
        String offer = who + " just offered you the building they're standing in as your " + kind + ".";
        if (c.problem() != null) {
            if (c.survey() != null && c.survey().problem() != null) {
                lines.add(offer + " But " + who + " isn't inside a proper building you can see: " + c.problem()
                        + " Say you're not sure which place they mean, or what's wrong with it.");
            } else {
                lines.add(offer + " But it can't be: " + c.problem() + " Say so in your own words.");
            }
            return lines;
        }
        lines.add(offer + " You've looked round: it's " + Adoption.describe(c) + ".");
        if (!c.missing().isEmpty()) {
            lines.add("It won't do as a " + kind + " yet: you'd need " + c.missingText() + ". Tell " + who
                    + " what you'd need, in your own words" + (c.wishes().isEmpty() ? "." : ", and that " + dev.eiriksb.stad.planner.Design.join(c.wishes())
                    + " would be nice too."));
            return lines;
        }
        if (!colony.rank(player.getUUID()).atLeast(OrderKind.ADOPT_BUILDING.minRank) && c.existing() == null) {
            lines.add("It would do nicely, but " + who + " isn't an officer of " + colony.name() + ", so it isn't theirs to give. Say so kindly.");
            return lines;
        }
        String name = type.equals(BuildingDef.HOME) ? Adoption.name(type, citizen.getName().getString().split(" ")[0]) : Adoption.name(type, null);
        Adoption.Result adopted = Adoption.adopt(player, level, colony, c, c.existing() != null ? c.existing().name : name);
        if (adopted.building() == null) {
            lines.add("You'd take it, but it can't be done: " + adopted.reason() + " Say so.");
            return lines;
        }
        Building b = adopted.building();
        if (!type.equals(BuildingDef.HOME)) {
            lines.add("It's now the town's " + b.name + "." + (c.wishes().isEmpty() ? "" : " It could do with " + dev.eiriksb.stad.planner.Design.join(c.wishes()) + ".")
                    + " Say what you think of it, in a sentence or two.");
            return lines;
        }
        Order.Result r = Adoption.giveHome(player, citizen, b);
        switch (r.outcome()) {
            case ACCEPTED, GRUDGING -> lines.add("You've moved in: the " + b.name + " is your home now."
                    + (r.outcome() == Order.Outcome.GRUDGING ? " You're not thrilled about it, though" + (r.reason() == null ? "." : ": " + r.reason() + ".") : "")
                    + (c.wishes().isEmpty() ? "" : " It could do with " + dev.eiriksb.stad.planner.Design.join(c.wishes()) + ".")
                    + " Say what you think of it in your own words.");
            case REFUSED -> lines.add("You won't move in: " + r.reason().replaceFirst("^[^:]*: ", "").replace("they're", "you're")
                    .replace("they ", "you ").replace("their", "your") + " Say so in your own words.");
            default -> lines.add("You can't move in: " + r.reason() + " Say so.");
        }
        return lines;
    }
}
