package dev.eiriksb.stad.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.block.TownHallBlock;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Jobs;
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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
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
        var stad = Commands.literal("stad").then(DraftCommands.node());
        PackCommands.nodes().forEach(stad::then);
        WarCommands.nodes().forEach(stad::then);
        stad.then(PerfCommands.node());
        if (DevCommands.enabled()) {
            stad.then(DevCommands.node());
        }
        d.register(stad
                .then(Commands.literal("colony")
                        .then(Commands.literal("info").executes(StadCommands::info))
                        .then(Commands.literal("found").requires(s -> s.hasPermission(2)).executes(StadCommands::found))
                        .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.greedyString()).executes(StadCommands::rename)))
                        .then(Commands.literal("rank").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Rank.values()).map(Rank::id), b))
                                        .executes(StadCommands::rank))))
                        .then(Commands.literal("list").requires(s -> s.hasPermission(2)).executes(StadCommands::list))
                        .then(Commands.literal("dissolve").executes(StadCommands::dissolve))
                        .then(Commands.literal("builds").executes(StadCommands::builds))
                        .then(Commands.literal("requests").executes(StadCommands::requests))
                        .then(Commands.literal("standing").executes(StadCommands::standing))
                        .then(Commands.literal("setstanding").requires(s -> s.hasPermission(2)).then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100)).executes(StadCommands::setStanding))))
                        .then(Commands.literal("plan").then(Commands.argument("building", ResourceLocationArgument.id())
                                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuildingPacks.all().stream().map(BuildingDef::key), b))
                                .executes(StadCommands::plan)
                                .then(Commands.argument("palette", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                dev.eiriksb.stad.pack.Palettes.all().stream().map(p -> p.id().getPath()), b))
                                        .executes(StadCommands::plan))))
                        .then(Commands.literal("cancel").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(StadCommands::cancel))))
                .then(Commands.literal("citizen")
                        .then(Commands.literal("invite").then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(c -> order(c, OrderKind.INVITE))))
                        .then(Commands.literal("dismiss").then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(c -> order(c, OrderKind.DISMISS))))
                        .then(Commands.literal("job").then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(Jobs.all().stream(),
                                                java.util.stream.Stream.of("none")), b))
                                        .executes(c -> order(c, OrderKind.ASSIGN_JOB)))))
                        .then(Commands.literal("talk").then(Commands.argument("uuid", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(StadCommands::talk))))
                        .then(Commands.literal("history").then(Commands.argument("uuid", StringArgumentType.word()).executes(StadCommands::history)))
                        .then(Commands.literal("voice").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("citizens", EntityArgument.entities()).executes(StadCommands::voice)))
                        .then(Commands.literal("status").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("citizens", EntityArgument.entities()).executes(StadCommands::status)))
                        .then(Commands.literal("needs").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("citizens", EntityArgument.entities())
                                        .then(Commands.argument("hunger", FloatArgumentType.floatArg(0, 20))
                                                .then(Commands.argument("rest", FloatArgumentType.floatArg(0, 20)).executes(StadCommands::needs)))))));
    }

    /** Why {@code p} can't set the stance, or null (for the town hall screen and commands). */
    public static String stanceProblem(ServerPlayer p, Colony c, dev.eiriksb.stad.war.Politics.Stance to) {
        return WarCommands.stanceProblem(p, c, to);
    }

    /** Sets a town's stance if {@code p} may; tells them why not otherwise. Returns whether it changed. */
    public static boolean setStance(ServerPlayer p, Colony c, dev.eiriksb.stad.war.Politics.Stance to) {
        String problem = WarCommands.stanceProblem(p, c, to);
        if (problem != null) {
            p.displayClientMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
            return false;
        }
        WarCommands.applyStance(p, c, to);
        return true;
    }

    /** The colony the command is about: the one you're standing in, else the one you lead. */
    static Optional<Colony> here(CommandSourceStack s) {
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
            s.sendSuccess(() -> Component.literal(String.format(" %s%s: food %d, rest %d, happiness %d%%, %s%s", r.name,
                    r.job == null ? "" : " (" + r.job + ", " + r.workToday + " done today)", r.hunger, r.rest, r.happiness,
                    r.home == null ? "homeless" : "bed at " + r.home.toShortString(),
                    r.plan.isEmpty() ? "" : "; plans: " + String.join("; ", r.plan))), false);
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

    private static int builds(CommandContext<CommandSourceStack> ctx) {
        Optional<Colony> found = here(ctx.getSource());
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        Colony c = found.get();
        if (c.buildings().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Nothing is planned in " + c.name() + ". Use the building plans on the ground."), false);
        }
        for (Building b : c.buildings()) {
            ctx.getSource().sendSuccess(() -> Component.literal("#" + b.id + " " + b.name + " at " + b.origin.toShortString() + ": " + status(b)), false);
        }
        return c.buildings().size();
    }

    private static int standing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> found = here(ctx.getSource());
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        var s = found.get().standing(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("In " + found.get().name() + " you're " + s.word() + " (" + (s.score() > 0 ? "+" : "")
                + s.score() + ").").withStyle(ChatFormatting.GOLD), false);
        for (var e : s.events().subList(0, Math.min(8, s.events().size()))) {
            ctx.getSource().sendSuccess(() -> Component.literal(" " + (e.delta() > 0 ? "+" : "") + e.delta() + "  " + e.cause())
                    .withStyle(e.delta() > 0 ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        }
        return s.score();
    }

    private static int setStanding(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        Optional<Colony> found = here(ctx.getSource());
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        found.get().setStanding(target.getUUID(), value);
        ctx.getSource().sendSuccess(() -> Component.literal(target.getGameProfile().getName() + "'s standing in " + found.get().name() + " is now " + value + "."), true);
        return value;
    }

    private static int requests(CommandContext<CommandSourceStack> ctx) {
        Optional<Colony> found = here(ctx.getSource());
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        Colony c = found.get();
        if (c.requests().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " doesn't need anything right now."), false);
        }
        for (dev.eiriksb.stad.colony.Request r : c.requests()) {
            ctx.getSource().sendSuccess(() -> Component.literal(r.label() + " for " + r.destName + " (" + r.reason + ")"
                    + (r.courier != null ? ", a courier is on it" : "")), false);
        }
        return c.requests().size();
    }

    public static String status(Building b) {
        return switch (b.status) {
            case PLANNED -> "planned";
            case BUILDING -> "being built, " + Math.round(b.fractionDone() * 100) + "%";
            case WAITING -> "waiting for " + Construction.describe(b.missing, 3) + " (" + Math.round(b.fractionDone() * 100) + "%)";
            case DONE -> "done";
        };
    }

    /** Plans a building where you stand, facing the way you look (what the building plans do, as a command). */
    private static int plan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation key = ResourceLocationArgument.getId(ctx, "building");
        Optional<BuildingDef> def = BuildingPacks.get(key);
        Optional<Colony> found = ColonyManager.get(player.server).at(player.level().dimension(), player.blockPosition());
        if (def.isEmpty()) {
            return fail(ctx.getSource(), "There's no building called " + key + ".");
        }
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "Stand on your town's land.");
        }
        String paletteName = ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("palette"))
                ? StringArgumentType.getString(ctx, "palette") : null;
        Optional<dev.eiriksb.stad.pack.Palette> palette = dev.eiriksb.stad.pack.Palettes.get(paletteName);
        if (paletteName != null && palette.isEmpty()) {
            return fail(ctx.getSource(), "There's no style called " + paletteName + " (see /stad pack palettes).");
        }
        dev.eiriksb.stad.planner.Design styled = palette.map(p -> dev.eiriksb.stad.planner.Design.styled(def.get(), p)).orElse(null);
        var blueprint = styled != null ? styled.blueprint() : def.get().blueprint();
        Rotation rotation = Placement.facingViewer(player.getDirection());
        BlockPos ground = player.blockPosition().below();
        BlockPos origin = Placement.originFor(blueprint, ground, rotation).relative(player.getDirection(),
                Placement.rotatedSize(blueprint.size(), rotation).getZ() / 2 + 2);
        Planning.Result r = styled != null ? Planning.plan(player, player.serverLevel(), found.get(), styled, origin, rotation)
                : Planning.plan(player, player.serverLevel(), found.get(), def.get(), origin, rotation);
        if (r.building() == null) {
            return fail(ctx.getSource(), r.problem());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Planned the " + r.building().name + " (#" + r.building().id + "). Its outline shows for a few seconds.")
                .withStyle(ChatFormatting.GREEN), false);
        return r.building().id;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> found = here(ctx.getSource());
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (found.isEmpty()) {
            return fail(ctx.getSource(), "You're not in a town.");
        }
        Colony c = found.get();
        Optional<Building> b = c.building(id);
        if (b.isEmpty() || b.get().done()) {
            return fail(ctx.getSource(), "There's no unfinished building #" + id + ".");
        }
        if (!c.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return fail(ctx.getSource(), "Only officers can cancel buildings.");
        }
        c.removeBuilding(id);
        ctx.getSource().sendSuccess(() -> Component.literal("Cancelled the " + b.get().name + "."), false);
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

    private static CitizenEntity near(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        try {
            return player.serverLevel().getEntity(UUID.fromString(StringArgumentType.getString(ctx, "uuid"))) instanceof CitizenEntity c
                    && c.distanceTo(player) <= dev.eiriksb.stad.citizen.CitizenScreens.REACH ? c : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Says something to a citizen (what the chat screen does, as a command). */
    private static int talk(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CitizenEntity c = near(ctx, player);
        if (c == null) {
            return fail(ctx.getSource(), "That citizen isn't nearby.");
        }
        return dev.eiriksb.theywilltalk.api.TheyWillTalkApi.get().talk(c, player, StringArgumentType.getString(ctx, "text")) ? 1
                : fail(ctx.getSource(), c.getName().getString() + " can't talk right now.");
    }

    /** The last things a citizen and you said to each other. */
    private static int history(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CitizenEntity c = near(ctx, player);
        if (c == null) {
            return fail(ctx.getSource(), "That citizen isn't nearby.");
        }
        java.util.List<dev.eiriksb.theywilltalk.api.ChatLine> lines;
        try {
            lines = dev.eiriksb.theywilltalk.api.TheyWillTalkApi.get().history(c.getUUID(), player.getUUID(), 10).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail(ctx.getSource(), "Couldn't read the conversation: " + e.getMessage());
        }
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("You haven't talked yet."), false);
        }
        for (var l : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(l.speaker() + ": ").withStyle(l.fromPlayer() ? ChatFormatting.AQUA : ChatFormatting.GOLD)
                    .append(Component.literal(l.text()).withStyle(ChatFormatting.WHITE)), false);
        }
        return 1;
    }

    /** Admins: how citizens sound (their voice description, pitch and pace). */
    private static int voice(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = 0;
        for (var e : EntityArgument.getEntities(ctx, "citizens")) {
            if (e instanceof CitizenEntity c) {
                n++;
                ctx.getSource().sendSuccess(() -> Component.literal(c.getName().getString() + String.format(" (%d, pitch %.2f, pace %.2f): ",
                        dev.eiriksb.stad.citizen.CitizenVoice.age(c), dev.eiriksb.stad.citizen.CitizenVoice.pitch(c),
                        dev.eiriksb.stad.citizen.CitizenVoice.pace(c))).withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(dev.eiriksb.stad.citizen.CitizenVoice.design(c)).withStyle(ChatFormatting.GRAY)), false);
            }
        }
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
        String arg = kind == OrderKind.ASSIGN_JOB ? StringArgumentType.getString(ctx, "job") : null;
        if ("none".equals(arg)) {
            arg = null;
        }
        String job = arg;
        Order.Result r = OrderService.issue(new Order(player, kind, citizen, arg));
        String who = citizen.getName().getString();
        if (r.done()) {
            if (r.outcome() == Order.Outcome.GRUDGING) {
                ctx.getSource().sendSuccess(() -> Component.literal(who + " agrees, grudgingly" + (r.reason() == null ? "" : " (" + r.reason() + ")")
                        + ", and will work slower today.").withStyle(ChatFormatting.YELLOW), false);
            }
            Colony c = ColonyManager.get(player.server).ofCitizen(uuid).orElse(null);
            ctx.getSource().sendSuccess(() -> Component.literal(switch (kind) {
                case INVITE -> who + " moves into " + (c == null ? "town" : c.name()) + "!";
                case DISMISS -> who + " packs up and leaves town.";
                case ASSIGN_JOB -> job == null ? who + " stops working." : who + " is now the town's " + job + ".";
                case ASSIGN_HOME -> who + " moves in.";
                case MARCH -> who + " marches.";
                case ENVOY -> who + " sets off.";
                case PLAN_BUILDING, ADOPT_BUILDING, DECLARE_WAR -> "";
            }).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        return fail(ctx.getSource(), r.reason());
    }
}
