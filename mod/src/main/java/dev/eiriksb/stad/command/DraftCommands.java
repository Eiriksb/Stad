package dev.eiriksb.stad.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.planner.Architect;
import dev.eiriksb.stad.planner.BuildSpec;
import dev.eiriksb.stad.planner.Drafts;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code /stad draft ...}: the buttons under a draft in chat, and a way to draw one up without talking (a spec as
 * JSON, or a description for the model).
 */
final class DraftCommands {
    private DraftCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("draft")
                .then(Commands.literal("show").executes(DraftCommands::show))
                .then(Commands.literal("approve").executes(DraftCommands::approve))
                .then(Commands.literal("cancel").executes(DraftCommands::cancel))
                .then(Commands.literal("here").executes(c -> move(c, Drafts.Where.HERE)))
                .then(Commands.literal("turn").executes(c -> move(c, Drafts.Where.TURN)))
                .then(Commands.literal("save").executes(DraftCommands::save))
                .then(Commands.literal("spec").then(Commands.argument("json", StringArgumentType.greedyString()).executes(DraftCommands::spec)))
                .then(Commands.literal("describe").then(Commands.argument("text", StringArgumentType.greedyString()).executes(DraftCommands::describe)));
    }

    private static int fail(CommandSourceStack s, String msg) {
        s.sendFailure(Component.literal(msg));
        return 0;
    }

    private static Optional<Drafts.Draft> draft(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return Drafts.get(ctx.getSource().getPlayerOrException().getUUID());
    }

    private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Drafts.Draft> d = draft(ctx);
        if (d.isEmpty()) {
            return fail(ctx.getSource(), "You have no draft. Talk to your builder about what you'd like built.");
        }
        Colony colony = ColonyManager.get(player.server).byId(d.get().colony).orElse(null);
        String problem = colony == null ? null : Drafts.problem(d.get(), colony, player.serverLevel());
        ctx.getSource().sendSuccess(() -> Component.literal(d.get().design.name() + ": " + d.get().design.describe() + ". It stands "
                + Drafts.where(d.get(), player) + " (at " + d.get().site.toShortString() + ", turned " + d.get().rotation + ")." + (problem == null ? "" : " " + problem)), false);
        Drafts.sync(player);
        Drafts.sendControls(player);
        return 1;
    }

    private static int approve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Drafts.Draft> d = draft(ctx);
        if (d.isEmpty()) {
            return fail(ctx.getSource(), "You have no draft to build.");
        }
        UUID builder = d.get().builder;
        Drafts.Approval a = Drafts.approve(player);
        Architect.reactToApproval(player, a, builder);
        if (a.building() == null) {
            return fail(ctx.getSource(), a.reason());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Planned the " + a.building().name + " (#" + a.building().id + ")"
                + (a.reason() == null ? "." : "; the builder isn't happy about it.")).withStyle(ChatFormatting.GREEN), false);
        return a.building().id;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!Drafts.cancel(ctx.getSource().getPlayerOrException())) {
            return fail(ctx.getSource(), "You have no draft.");
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Draft dropped."), false);
        return 1;
    }

    private static int move(CommandContext<CommandSourceStack> ctx, Drafts.Where where) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!Drafts.move(player, where, null)) {
            return fail(ctx.getSource(), "You have no draft.");
        }
        Drafts.Draft d = draft(ctx).orElseThrow();
        Colony colony = ColonyManager.get(player.server).byId(d.colony).orElseThrow();
        String problem = Drafts.problem(d, colony, player.serverLevel());
        if (problem != null) {
            player.displayClientMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
        }
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Drafts.Draft> d = draft(ctx);
        if (d.isEmpty()) {
            return fail(ctx.getSource(), "You have no draft to save.");
        }
        try {
            var key = MyPack.save(player.server, d.get().design, player.getGameProfile().getName());
            ctx.getSource().sendSuccess(() -> Component.literal("Saved the " + d.get().design.name() + " in My pack (" + key
                    + "). It's in the Building Plans now.").withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            Stad.LOGGER.warn("Couldn't save design", e);
            return fail(ctx.getSource(), "Couldn't save it: " + e.getMessage());
        }
    }

    /** A spec straight from JSON (for testing the generator, or for scripted building). */
    private static int spec(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colonyFor(ctx, player);
        if (colony.isEmpty()) {
            return 0;
        }
        BuildSpec spec;
        try {
            spec = BuildSpec.parse(StringArgumentType.getString(ctx, "json"));
        } catch (RuntimeException e) {
            return fail(ctx.getSource(), "That isn't a building spec: " + e.getMessage());
        }
        UUID builder = Architect.nearestBuilder(player, colony.get()).map(CitizenEntity::getUUID).orElse(null);
        Drafts.Draft d = Drafts.draw(player, colony.get(), builder, spec, Drafts.Where.SAME, null);
        report(ctx, player, colony.get(), d);
        return 1;
    }

    /** What the builder would make of a description (asks the language model, like talking to them would). */
    private static int describe(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colonyFor(ctx, player);
        if (colony.isEmpty()) {
            return 0;
        }
        if (!dev.eiriksb.theywilltalk.api.TheyWillTalkApi.get().llm().ready()) {
            return fail(ctx.getSource(), "The language model isn't running (see They Will Talk's Models page).");
        }
        String current = Drafts.get(player.getUUID()).filter(x -> x.colony == colony.get().id).map(x -> x.design.spec().toJson()).orElse(null);
        UUID builder = Architect.nearestBuilder(player, colony.get()).map(CitizenEntity::getUUID).orElse(null);
        ctx.getSource().sendSuccess(() -> Component.literal("Drawing it up...").withStyle(ChatFormatting.GRAY), false);
        Architect.spec(player.getGameProfile().getName(), StringArgumentType.getString(ctx, "text"), current)
                .thenAccept(spec -> player.server.execute(() -> report(ctx, player, colony.get(),
                        Drafts.draw(player, colony.get(), builder, spec, Drafts.Where.SAME, null))))
                .exceptionally(t -> {
                    player.server.execute(() -> ctx.getSource().sendFailure(Component.literal("The drawing failed: " + t.getMessage())));
                    return null;
                });
        return 1;
    }

    private static Optional<Colony> colonyFor(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        Optional<Colony> colony = ColonyManager.get(player.server).at(player.level().dimension(), player.blockPosition());
        if (colony.isEmpty()) {
            fail(ctx.getSource(), "Stand on your town's land.");
        } else if (!colony.get().rank(player.getUUID()).atLeast(Rank.MEMBER)) {
            fail(ctx.getSource(), "Only the people of " + colony.get().name() + " can draw up buildings there.");
            return Optional.empty();
        }
        return colony;
    }

    private static void report(CommandContext<CommandSourceStack> ctx, ServerPlayer player, Colony colony, Drafts.Draft d) {
        String problem = Drafts.problem(d, colony, player.serverLevel());
        ctx.getSource().sendSuccess(() -> Component.literal(d.design.name() + ": " + d.design.describe() + ". It stands "
                + Drafts.where(d, player) + ". " + Drafts.materialsSentence(d, player.serverLevel(), colony)
                + (d.design.notes().isEmpty() ? "" : " " + String.join(" ", d.design.notes()))
                + (problem == null ? "" : " " + problem)), false);
        Drafts.sendControls(player);
    }
}
