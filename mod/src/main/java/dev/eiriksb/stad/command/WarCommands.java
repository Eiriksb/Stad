package dev.eiriksb.stad.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.war.Envoys;
import dev.eiriksb.stad.war.Politics;
import dev.eiriksb.stad.war.Raids;
import dev.eiriksb.stad.war.War;
import dev.eiriksb.stad.war.Wars;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@code /stad war ...} and {@code /stad raid ...}: stances, wars, raids and peace (PLAN.md §6.7). */
final class WarCommands {
    private WarCommands() {}

    static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(
                Commands.literal("war")
                        .then(Commands.literal("status").executes(WarCommands::status))
                        .then(Commands.literal("stance").then(Commands.argument("stance", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("peaceful", "open"), b)).executes(WarCommands::stance)))
                        .then(Commands.literal("poaching").then(Commands.argument("on", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off"), b)).executes(WarCommands::poaching)))
                        .then(Commands.literal("window").then(Commands.argument("from", IntegerArgumentType.integer(0, 23))
                                .then(Commands.argument("to", IntegerArgumentType.integer(0, 23)).executes(WarCommands::window))))
                        .then(Commands.literal("declare").then(town().executes(WarCommands::declare)))
                        .then(Commands.literal("raid").executes(WarCommands::raid))
                        .then(Commands.literal("retreat").executes(WarCommands::retreat))
                        .then(Commands.literal("peace").then(town().executes(WarCommands::peace)))
                        .then(Commands.literal("envoy").then(Commands.argument("uuid", StringArgumentType.word()).executes(WarCommands::envoy))),
                Commands.literal("raid").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("start").executes(c -> startRaid(c, Raids.WARNING_TICKS))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600))
                                        .executes(c -> startRaid(c, IntegerArgumentType.getInteger(c, "seconds") * 20))))
                        .then(Commands.literal("stop").executes(WarCommands::stopRaid)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> town() {
        return Commands.argument("town", StringArgumentType.greedyString()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                ColonyManager.get(c.getSource().getServer()).all().stream().map(Colony::name), b));
    }

    private static int fail(CommandSourceStack s, String msg) {
        s.sendFailure(Component.literal(msg));
        return 0;
    }

    private static void ok(CommandSourceStack s, String msg) {
        s.sendSuccess(() -> Component.literal(msg), false);
    }

    /** The town a player speaks for: the one they're standing in if they're an officer there, else the first they run. */
    static Optional<Colony> mine(ServerPlayer p) {
        ColonyManager m = ColonyManager.get(p.server);
        return m.at(p.level().dimension(), p.blockPosition()).filter(c -> c.rank(p.getUUID()).atLeast(Rank.OFFICER))
                .or(() -> m.all().stream().filter(c -> c.rank(p.getUUID()).atLeast(Rank.OFFICER)).findFirst());
    }

    private static Optional<Colony> named(CommandContext<CommandSourceStack> ctx) {
        String n = StringArgumentType.getString(ctx, "town").strip().toLowerCase(Locale.ROOT);
        var all = ColonyManager.get(ctx.getSource().getServer()).all();
        return all.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).equals(n)).findFirst()
                .or(() -> all.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).startsWith(n)).findFirst());
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p).or(() -> ColonyManager.get(p.server).all().stream().filter(x -> x.rank(p.getUUID()).atLeast(Rank.MEMBER)).findFirst());
        if (c.isEmpty()) {
            return fail(ctx.getSource(), "You're not part of a town.");
        }
        Colony t = c.get();
        long now = System.currentTimeMillis();
        ok(ctx.getSource(), t.name() + " is " + (t.politics.open() ? "open to war" : "peaceful") + "; rivals " + (t.politics.poaching ? "may" : "may not")
                + " lure its people away; it can be raided " + t.politics.window() + ".");
        for (War w : Wars.of(p.server, t.id)) {
            String other = ColonyManager.get(p.server).byId(w.other(t.id)).map(Colony::name).orElse("?");
            long ready = w.declared + dev.eiriksb.stad.StadConfig.WAR_WARNING_MINUTES.get() * 60_000L;
            ok(ctx.getSource(), " At war with " + other + (w.attacker == t.id ? " (you declared it)" : " (they declared it)") + ": "
                    + (w.state == War.State.RAID ? "a raid is on! Banner " + Wars.capture(w) + "/" + dev.eiriksb.stad.StadConfig.CAPTURE_SECONDS.get() + "s"
                    : now < ready ? "raids can start in " + Wars.duration(ready - now) : "a raid can start any time in the window")
                    + (w.peaceOffers.isEmpty() ? "" : "; peace offered by " + (w.peaceOffers.contains(t.id) ? "you" : other)) + ".");
        }
        Raids.of(t).ifPresent(r -> ok(ctx.getSource(), " Raiders " + (r.phase == Raids.Phase.WARNING ? "are coming" : "are attacking") + " from the "
                + r.from.getName() + "."));
        return 1;
    }

    private static int stance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p);
        String s = StringArgumentType.getString(ctx, "stance").toLowerCase(Locale.ROOT);
        Politics.Stance to = s.startsWith("open") || s.equals("war") ? Politics.Stance.OPEN : s.startsWith("peace") ? Politics.Stance.PEACEFUL : null;
        if (c.isEmpty() || to == null) {
            return fail(ctx.getSource(), c.isEmpty() ? "You don't run a town." : "Peaceful or open?");
        }
        String problem = StadCommands.stanceProblem(p, c.get(), to);
        if (problem != null) {
            return fail(ctx.getSource(), problem);
        }
        StadCommands.setStance(p, c.get(), to);
        return 1;
    }

    /** Why {@code p} can't set {@code c}'s stance to {@code to}, or null. */
    static String stanceProblem(ServerPlayer p, Colony c, Politics.Stance to) {
        if (!c.owner().equals(p.getUUID())) {
            return "Only " + c.ownerName() + " can change " + c.name() + "'s stance.";
        }
        return c.politics.cantChange(to, Wars.atWar(p.server, c.id), System.currentTimeMillis());
    }

    static void applyStance(ServerPlayer p, Colony c, Politics.Stance to) {
        long now = System.currentTimeMillis();
        c.politics.set(to, now);
        c.changed();
        dev.eiriksb.stad.colony.Announce.members(p.server, c, Component.literal(c.name() + " is now " + to.word() + "."
                + (to == Politics.Stance.OPEN ? " Other towns that are open to war can declare war on it." : "")).withStyle(ChatFormatting.GOLD));
    }

    private static int poaching(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p);
        if (c.isEmpty() || !c.get().owner().equals(p.getUUID())) {
            return fail(ctx.getSource(), "Only a town's founder can change that.");
        }
        c.get().politics.poaching = StringArgumentType.getString(ctx, "on").equalsIgnoreCase("on");
        c.get().changed();
        ok(ctx.getSource(), c.get().politics.poaching ? "Rivals may now lure " + c.get().name() + "'s unhappy citizens away."
                : "Nobody can lure " + c.get().name() + "'s citizens away now.");
        return 1;
    }

    private static int window(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p);
        if (c.isEmpty()) {
            return fail(ctx.getSource(), "You don't run a town.");
        }
        if (Wars.atWar(p.server, c.get().id)) {
            return fail(ctx.getSource(), "Not while there's a war on.");
        }
        c.get().politics.windowFrom = IntegerArgumentType.getInteger(ctx, "from");
        c.get().politics.windowTo = IntegerArgumentType.getInteger(ctx, "to");
        c.get().changed();
        ok(ctx.getSource(), c.get().name() + " can be raided " + c.get().politics.window() + " (server time).");
        return 1;
    }

    private static int declare(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> from = mine(p), to = named(ctx);
        if (from.isEmpty() || to.isEmpty()) {
            return fail(ctx.getSource(), from.isEmpty() ? "You don't run a town." : "There's no town called " + StringArgumentType.getString(ctx, "town") + ".");
        }
        Wars.Result r = Wars.declare(p, from.get(), to.get(), System.currentTimeMillis());
        return r.war() == null ? fail(ctx.getSource(), r.problem()) : r.war().id;
    }

    private static int raid(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> on = ColonyManager.get(p.server).at(p.level().dimension(), p.blockPosition());
        Optional<War> war = on.flatMap(d -> Wars.of(p.server, d.id).stream().filter(w -> w.defender == d.id
                && ColonyManager.get(p.server).byId(w.attacker).map(a -> a.rank(p.getUUID()).atLeast(Rank.OFFICER)).orElse(false)).findFirst());
        if (war.isEmpty()) {
            return fail(ctx.getSource(), "Stand on the land of a town your town is at war with.");
        }
        Wars.Result r = Wars.startRaid(p, war.get(), System.currentTimeMillis());
        return r.war() == null ? fail(ctx.getSource(), r.problem()) : 1;
    }

    private static int retreat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p);
        Optional<War> w = c.flatMap(x -> Wars.raidOf(x.id)).filter(x -> x.attacker == c.get().id);
        if (w.isEmpty()) {
            return fail(ctx.getSource(), "Your town isn't raiding anyone.");
        }
        Wars.end(p.server, w.get(), War.Outcome.RETREAT, System.currentTimeMillis());
        return 1;
    }

    private static int peace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = mine(p), other = named(ctx);
        Optional<War> w = c.flatMap(x -> other.flatMap(o -> Wars.between(p.server, x.id, o.id)));
        if (w.isEmpty()) {
            return fail(ctx.getSource(), "Your town isn't at war with them.");
        }
        Wars.Result r = Wars.offerPeace(p, w.get(), c.get(), System.currentTimeMillis());
        if (r.problem() != null) {
            return fail(ctx.getSource(), r.problem());
        }
        if (w.get().on()) {
            ok(ctx.getSource(), "You've offered " + other.get().name() + " peace. It's peace once one of their officers agrees.");
        }
        return 1;
    }

    private static int envoy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        CitizenEntity c;
        try {
            c = p.serverLevel().getEntity(UUID.fromString(StringArgumentType.getString(ctx, "uuid"))) instanceof CitizenEntity ce ? ce : null;
        } catch (IllegalArgumentException e) {
            c = null;
        }
        if (c == null || c.colony().isEmpty()) {
            return fail(ctx.getSource(), "That citizen isn't here.");
        }
        int home = c.colony().get().id;
        Optional<War> w = Wars.of(p.server, home).stream().findFirst();
        if (w.isEmpty()) {
            return fail(ctx.getSource(), "Their town isn't at war.");
        }
        String problem = Envoys.send(p, c, w.get(), System.currentTimeMillis());
        if (problem != null) {
            return fail(ctx.getSource(), problem);
        }
        ok(ctx.getSource(), c.getName().getString() + " has gone to talk peace.");
        return 1;
    }

    private static int startRaid(CommandContext<CommandSourceStack> ctx, int warning) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = ColonyManager.get(p.server).at(p.level().dimension(), p.blockPosition());
        if (c.isEmpty()) {
            return fail(ctx.getSource(), "Stand in a town.");
        }
        if (Raids.of(c.get()).isPresent()) {
            return fail(ctx.getSource(), c.get().name() + " is being raided already.");
        }
        Raids.start(p.serverLevel(), c.get(), warning);
        ok(ctx.getSource(), "Raiders will attack " + c.get().name() + " in " + warning / 20 + " seconds (" + Raids.strength(c.get()) + " of them).");
        return 1;
    }

    private static int stopRaid(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        Optional<Colony> c = ColonyManager.get(p.server).at(p.level().dimension(), p.blockPosition());
        return c.isPresent() && Raids.stop(p.serverLevel(), c.get()) ? 1 : fail(ctx.getSource(), "No raid here.");
    }
}
