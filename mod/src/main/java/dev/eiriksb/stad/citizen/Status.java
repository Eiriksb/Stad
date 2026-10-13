package dev.eiriksb.stad.citizen;

import dev.eiriksb.stad.citizen.goal.EatAtTownHallGoal;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * What a citizen is doing right now, or why they can't work and what would fix it, in a line for the player: on their
 * card, in the town hall and in what they say. The job goals tell the citizen what they're up to
 * ({@link CitizenEntity#doing}) or what's stopping them ({@link CitizenEntity#stuck}); a problem that lasts a while is
 * told to the town's leaders once a day. Server thread.
 */
public record Status(String text, boolean problem) {
    /** how long what a goal said holds (it says it again every few seconds while it's true) */
    static final long FRESH = 300;
    /** how long a problem lasts before the leaders are told */
    private static final long TELL_AFTER = 1200;

    public static Status of(CitizenEntity c) {
        CitizenRecord r = c.record().orElse(null);
        if (r == null) {
            return new Status("Looking for a town", false);
        }
        if (c.isBaby()) {
            return new Status(c.doingFresh() ? c.doing() : "Playing", false);
        }
        if (r.illness != null) {
            return new Status("Ill in bed with " + r.illness, false);
        }
        if (c.isSleeping()) {
            return new Status("Asleep", false);
        }
        if (c.isNight()) {
            return new Status("Home for the night", false);
        }
        if (c.hunger() < 4) {
            return EatAtTownHallGoal.townHasFood(c) ? new Status("Too hungry to work: off to eat", false)
                    : new Status("Too hungry to work: there's no food in the town hall", true);
        }
        Colony colony = c.colony().orElse(null);
        if (colony != null && colony.onStrike(c.level().getDayTime() / 24000)) {
            return new Status("On strike today: the town is unhappy", true);
        }
        if (r.job == null) {
            return new Status("No job: right-click them to give them one", true);
        }
        if (!c.canWork()) {
            return new Status("Taking the afternoon off", false);
        }
        if (c.stuckFresh()) {
            return new Status(c.stuck(), true);
        }
        if (c.doingFresh()) {
            return new Status(c.doing(), false);
        }
        return new Status("Looking for something to do", false);
    }

    /** Every few seconds: a problem that's lasted a minute is told to the town's leaders (once a day each). */
    static void watch(CitizenEntity c) {
        if (!(c.level() instanceof ServerLevel level)) {
            return;
        }
        CitizenRecord r = c.record().orElse(null);
        Colony colony = c.colony().orElse(null);
        if (r == null || colony == null) {
            return;
        }
        Status s = of(c);
        long now = level.getGameTime();
        // only problems with the work itself (not "no job": the town hall lists those)
        if (!s.problem() || r.job == null || !c.stuckFresh() && !s.text().startsWith("Too hungry")) {
            r.problem = null;
            return;
        }
        if (!s.text().equals(r.problem)) {
            r.problem = s.text();
            r.problemSince = now;
            return;
        }
        long day = level.getDayTime() / 24000;
        if (now - r.problemSince >= TELL_AFTER && !(day == r.toldDay && s.text().equals(r.told))) {
            r.told = s.text();
            r.toldDay = day;
            Announce.leaders(level.getServer(), colony, Component.literal("[" + colony.name() + "] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(r.name + " (" + r.job + "): " + s.text()).withStyle(ChatFormatting.YELLOW)));
        }
    }
}
