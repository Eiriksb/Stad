package dev.eiriksb.stad.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.block.TownHallBlock;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.OrderKind;
import dev.eiriksb.stad.colony.OrderService;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** {@code /stad ...} */
public final class StadCommands {
    private StadCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("stad")
                .then(Commands.literal("colony")
                        .then(Commands.literal("info").executes(StadCommands::info))
                        .then(Commands.literal("found").requires(s -> s.hasPermission(2)).executes(StadCommands::found))
                        .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString()).executes(StadCommands::rename)))
                        .then(Commands.literal("rank").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Rank.values()).map(Rank::id), b))
                                        .executes(StadCommands::rank))))
                        .then(Commands.literal("list").requires(s -> s.hasPermission(2)).executes(StadCommands::list))
                        .then(Commands.literal("dissolve").executes(StadCommands::dissolve)))
                .then(Commands.literal("citizen")
                        .then(Commands.literal("invite").then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(c -> order(c, OrderKind.INVITE))))
                        .then(Commands.literal("dismiss").then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(c -> order(c, OrderKind.DISMISS))))
                        .then(Commands.literal("status").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("citizens", EntityArgument.entities()).executes(StadCommands::status)))
                        .then(Commands.literal("needs").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("citizens", EntityArgument.entities())
                                        .then(Commands.argument("hunger", FloatArgumentType.floatArg(0, 20))
                                                .then(Commands.argument("rest", FloatArgumentType.floatArg(0, 20)).executes(StadCommands::needs)))))));
    }

    /** The colony the command is about: the one you're standing in, else the one you lead. */
    private static Optional<Colony> here(CommandSourceStack s) {
        ColonyManager m = ColonyManager.get(s.getServer());
        Optional<Colony> at = m.at(s.getLevel().dimension(), BlockPos.containing(s.getPosition()));
        if (at.isPresent() || s.getPlayer() == null) {
            return at;
        }
        return m.ownedBy(s.getPlayer().getUUID()).stream().findFirst();
    }

    private static int fail(CommandSourceStack s, String msg) {
        s.sendFailure(Component.literal(msg));
        return 0;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        Optional<Colony> found = here(s);
        if (found.isEmpty()) {
            return fail(s, "You're not in a town (and don't lead one).");
        }
        Colony c = found.get();
        ServerLevel level = s.getServer().getLevel(c.dimension);
        int beds = level == null ? 0 : Homes.beds(level, c).size();
        s.sendSuccess(() -> Component.literal(c.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("  founded by " + c.ownerName() + ", at " + c.center.toShortString()).withStyle(ChatFormatting.GRAY)), false);
        s.sendSuccess(() -> Component.literal(c.population() + " citizens, " + beds + " beds").withStyle(ChatFormatting.GRAY), false);
        for (CitizenRecord r : c.citizens()) {
            s.sendSuccess(() -> Component.literal(String.format(" %s: food %d, rest %d, happiness %d%%, %s", r.name, r.hunger, r.rest,
                    r.happiness, r.home == null ? "homeless" : "bed at " + r.home.toShortString())), false);
        }
        c.ranks().forEach((uuid, rank) -> s.sendSuccess(() -> Component.literal(" " + c.playerName(uuid) + ": " + rank.id())
                .withStyle(ChatFormatting.DARK_GRAY), false));
        return c.population();
    }

    private static int found(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        if (!level.getBlockState(pos).canBeReplaced()) {
            return fail(ctx.getSource(), "Stand somewhere with room for the town hall.");
        }
        level.setBlockAndUpdate(pos, StadRegistries.TOWN_HALL.get().defaultBlockState());
        TownHallBlock.found(level, pos, player);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> found = here(ctx.getSource());
        String name = StringArgumentType.getString(ctx, "name").strip();
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        if (!found.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return fail(ctx.getSource(), "Only officers can rename the town.");
        }
        if (name.length() < 2 || name.length() > 32) {
            return fail(ctx.getSource(), "Town names are 2 to 32 characters.");
        }
        found.get().rename(name);
        ctx.getSource().sendSuccess(() -> Component.literal("The town is now called " + name + "."), true);
        return 1;
    }

    private static int rank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Rank rank = Rank.byId(StringArgumentType.getString(ctx, "rank"));
        Optional<Colony> found = here(ctx.getSource());
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        Colony c = found.get();
        if (!c.owner().equals(player.getUUID())) {
            return fail(ctx.getSource(), "Only the founder of " + c.name() + " can change ranks.");
        }
        if (target.getUUID().equals(player.getUUID()) && rank != Rank.OWNER) {
            return fail(ctx.getSource(), "Make someone else the owner to step down.");
        }
        c.setRank(target.getUUID(), target.getGameProfile().getName(), rank);
        ctx.getSource().sendSuccess(() -> Component.literal(target.getGameProfile().getName() + " is now " + rank.describe() + " of " + c.name() + "."), true);
        target.sendSystemMessage(Component.literal("You are now " + rank.describe() + " of " + c.name() + ".").withStyle(ChatFormatting.GOLD));
        return 1;
    }

    /** Ends the town you're in: its founder, or an admin. The town hall stays as a block. */
    private static int dissolve(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        Optional<Colony> found = here(s);
        if (found.isEmpty()) {
            return fail(s, "You're not in a town.");
        }
        Colony c = found.get();
        boolean owner = s.getPlayer() != null && c.owner().equals(s.getPlayer().getUUID());
        if (!owner && !s.hasPermission(2)) {
            return fail(s, "Only the founder of " + c.name() + " can end it.");
        }
        ColonyManager.get(s.getServer()).dissolve(s.getServer(), c);
        s.sendSuccess(() -> Component.literal(c.name() + " is no more."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var all = ColonyManager.get(ctx.getSource().getServer()).all();
        for (Colony c : all) {
            ctx.getSource().sendSuccess(() -> Component.literal(c.id + ": " + c.name() + " (" + c.ownerName() + ") at " + c.center.toShortString()
                    + " in " + c.dimension.location() + ", " + c.population() + " citizens"), false);
        }
        return all.size();
    }

    /** What citizens are doing right now (admins). */
    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = 0;
        for (var e : EntityArgument.getEntities(ctx, "citizens")) {
            if (e instanceof CitizenEntity c) {
                ctx.getSource().sendSuccess(() -> Component.literal(c.getName().getString() + " at " + c.blockPosition().toShortString() + ": "
                        + c.activity()), false);
                n++;
            }
        }
        return n;
    }

    /** Sets citizens' hunger and rest (admins, testing). */
    private static int needs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        float hunger = FloatArgumentType.getFloat(ctx, "hunger");
        float rest = FloatArgumentType.getFloat(ctx, "rest");
        int n = 0;
        for (var e : EntityArgument.getEntities(ctx, "citizens")) {
            if (e instanceof CitizenEntity c) {
                c.setHunger(hunger);
                c.setRest(rest);
                n++;
            }
        }
        int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Set the needs of " + count + " citizens."), true);
        return n;
    }

    private static int order(CommandContext<CommandSourceStack> ctx, OrderKind kind) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID uuid;
        try {
            uuid = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
        } catch (IllegalArgumentException e) {
            return fail(ctx.getSource(), "That's not a citizen.");
        }
        if (!(player.serverLevel().getEntity(uuid) instanceof CitizenEntity citizen) || citizen.distanceTo(player) > 16) {
            return fail(ctx.getSource(), "That citizen isn't nearby.");
        }
        Order.Result r = OrderService.issue(new Order(player, kind, citizen));
        String who = citizen.getName().getString();
        if (r.done()) {
            Colony c = ColonyManager.get(player.server).ofCitizen(uuid).orElse(null);
            ctx.getSource().sendSuccess(() -> Component.literal(switch (kind) {
                case INVITE -> who + " moves into " + (c == null ? "town" : c.name()) + "!";
                case DISMISS -> who + " packs up and leaves town.";
            }).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        return fail(ctx.getSource(), r.reason());
    }
}
