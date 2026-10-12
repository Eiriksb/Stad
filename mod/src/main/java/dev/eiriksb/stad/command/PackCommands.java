package dev.eiriksb.stad.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Requirements;
import dev.eiriksb.stad.building.Substitutions;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Adoption;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Order;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.pack.PackEditor;
import dev.eiriksb.stad.pack.Palette;
import dev.eiriksb.stad.pack.Palettes;
import dev.eiriksb.stad.pack.Roles;
import dev.eiriksb.stad.pack.Scans;
import dev.eiriksb.stad.pack.SharedPacks;
import dev.eiriksb.stad.planner.MyPack;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /stad adopt|scan|pack|build ...}: taking over buildings players made, scanning them into My pack, looking
 * after the packs, and answering the builder about swapped materials (PLAN.md §6.3, §6.5).
 */
final class PackCommands {
    private PackCommands() {}

    private static final List<String> KINDS = List.of("home", "builder_hut", "warehouse", "workshop", "barracks", "watchtower");

    static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(
                Commands.literal("adopt")
                        .executes(c -> adopt(c, null, null))
                        .then(Commands.literal("check").executes(c -> check(c, null))
                                .then(Commands.argument("kind", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(KINDS, b))
                                        .executes(c -> check(c, StringArgumentType.getString(c, "kind")))))
                        .then(Commands.literal("as").then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(KINDS, b))
                                .executes(c -> adopt(c, StringArgumentType.getString(c, "kind"), null))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> adopt(c, StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "name"))))))
                        .then(Commands.literal("give").then(Commands.argument("uuid", StringArgumentType.word()).executes(PackCommands::give)))
                        .then(Commands.literal("release").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(PackCommands::release))),
                Commands.literal("scan")
                        .then(Commands.literal("pos1").executes(c -> corner(c, true, false))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> corner(c, true, true))))
                        .then(Commands.literal("pos2").executes(c -> corner(c, false, false))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> corner(c, false, true))))
                        .then(Commands.literal("open").executes(PackCommands::openScan))
                        .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.greedyString()).executes(PackCommands::saveScan)))
                        .then(Commands.literal("clear").executes(PackCommands::clearScan)),
                Commands.literal("pack")
                        .then(Commands.literal("list").executes(PackCommands::list))
                        .then(Commands.literal("palettes").executes(PackCommands::palettes))
                        .then(Commands.literal("edit").then(building().executes(PackCommands::edit)))
                        .then(Commands.literal("delete").then(building().executes(PackCommands::delete)))
                        .then(Commands.literal("role").then(building().then(Commands.argument("block", ResourceLocationArgument.id())
                                .then(Commands.argument("from", StringArgumentType.word()).suggests(PackCommands::roles)
                                        .then(Commands.argument("to", StringArgumentType.word()).suggests(PackCommands::roles)
                                                .executes(PackCommands::role))))))
                        .then(Commands.literal("export").executes(c -> export(c, "my_pack"))
                                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> export(c, StringArgumentType.getString(c, "name"))))),
                Commands.literal("build")
                        .then(Commands.literal("swap").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> answer(c, true))))
                        .then(Commands.literal("keep").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> answer(c, false)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> building() {
        return Commands.argument("building", ResourceLocationArgument.id())
                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuildingPacks.all().stream().map(BuildingDef::key), b));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> roles(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(Arrays.stream(Roles.Role.values()).map(Roles.Role::id), b);
    }

    private static int fail(CommandSourceStack s, String msg) {
        s.sendFailure(Component.literal(msg));
        return 0;
    }

    private static void ok(CommandSourceStack s, String msg) {
        s.sendSuccess(() -> Component.literal(msg), false);
    }

    private static Optional<Colony> colony(ServerPlayer player) {
        return ColonyManager.get(player.server).at(player.level().dimension(), player.blockPosition());
    }

    // ---- adopting ---------------------------------------------------------------------------------------------

    private static ResourceLocation kind(String word) {
        return word == null ? null : Requirements.typeOf(word);
    }

    private static int check(CommandContext<CommandSourceStack> ctx, String kindWord) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colony(player);
        if (colony.isEmpty()) {
            return fail(ctx.getSource(), "Stand in a building on your town's land.");
        }
        Adoption.Check c = Adoption.check(player.serverLevel(), colony.get(), player.blockPosition(), kind(kindWord));
        if (c.problem() != null && c.existing() == null) {
            return fail(ctx.getSource(), c.problem());
        }
        if (c.existing() != null) {
            ok(ctx.getSource(), c.problem() != null ? c.problem() : "That's the town's " + c.existing().name + " already.");
            return 1;
        }
        String kind = Requirements.word(c.type());
        ok(ctx.getSource(), "It's " + Adoption.describe(c) + ". " + (c.missing().isEmpty() ? "It'll do as a " + kind + "."
                : "To be a " + kind + " it needs " + c.missingText() + ".") + (c.wishes().isEmpty() ? ""
                : " It'd be nicer with " + dev.eiriksb.stad.planner.Design.join(c.wishes()) + "."));
        if (c.survey() != null && c.survey().box() != null) {
            dev.eiriksb.stad.building.Outlines.preview(player, c.survey().box(), 100);
        }
        return c.missing().isEmpty() ? 1 : 0;
    }

    private static int adopt(CommandContext<CommandSourceStack> ctx, String kindWord, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colony(player);
        if (colony.isEmpty()) {
            return fail(ctx.getSource(), "Stand in a building on your town's land.");
        }
        ResourceLocation wanted = kind(kindWord);
        if (kindWord != null && wanted == null) {
            return fail(ctx.getSource(), "There's no kind of building called " + kindWord + " (try " + String.join(", ", KINDS) + ").");
        }
        Adoption.Check c = Adoption.check(player.serverLevel(), colony.get(), player.blockPosition(), wanted);
        if (c.existing() != null && c.problem() == null) {
            return fail(ctx.getSource(), "That's the town's " + c.existing().name + " already.");
        }
        Adoption.Result r = Adoption.adopt(player, player.serverLevel(), colony.get(), c, name != null ? name.strip() : Adoption.name(c.type() == null
                ? dev.eiriksb.stad.building.BuildingDef.HOME : c.type(), null));
        if (r.building() == null) {
            return fail(ctx.getSource(), r.reason());
        }
        ok(ctx.getSource(), "The " + r.building().name + " is now one of " + colony.get().name() + "'s buildings (#" + r.building().id + ")."
                + (c.wishes().isEmpty() ? "" : " It'd be nicer with " + dev.eiriksb.stad.planner.Design.join(c.wishes()) + "."));
        return r.building().id;
    }

    private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colony(player);
        UUID uuid;
        try {
            uuid = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
        } catch (IllegalArgumentException e) {
            return fail(ctx.getSource(), "That isn't a citizen's id.");
        }
        CitizenEntity citizen = player.serverLevel().getEntity(uuid) instanceof CitizenEntity ce ? ce : null;
        if (colony.isEmpty() || citizen == null) {
            return fail(ctx.getSource(), colony.isEmpty() ? "Stand in a house on your town's land." : "That citizen isn't here.");
        }
        Adoption.Check c = Adoption.check(player.serverLevel(), colony.get(), player.blockPosition(), dev.eiriksb.stad.building.BuildingDef.HOME);
        Adoption.Result a = Adoption.adopt(player, player.serverLevel(), colony.get(), c, c.existing() != null ? c.existing().name
                : Adoption.name(dev.eiriksb.stad.building.BuildingDef.HOME, citizen.getName().getString().split(" ")[0]));
        if (a.building() == null) {
            return fail(ctx.getSource(), a.reason());
        }
        Order.Result r = Adoption.giveHome(player, citizen, a.building());
        if (!r.done()) {
            return fail(ctx.getSource(), r.reason());
        }
        ok(ctx.getSource(), citizen.getName().getString() + " moves into the " + a.building().name + ".");
        return 1;
    }

    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Colony> colony = colony(player);
        int id = IntegerArgumentType.getInteger(ctx, "id");
        Optional<Building> b = colony.flatMap(c -> c.building(id));
        if (b.isEmpty() || !b.get().key.getPath().startsWith("adopted/")) {
            return fail(ctx.getSource(), "There's no taken-over building #" + id + " here.");
        }
        if (!colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            return fail(ctx.getSource(), "Only officers can do that.");
        }
        colony.get().removeBuilding(id);
        ok(ctx.getSource(), "The " + b.get().name + " isn't the town's any more (it still stands).");
        return 1;
    }

    // ---- scanning ---------------------------------------------------------------------------------------------

    private static int corner(CommandContext<CommandSourceStack> ctx, boolean first, boolean given) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = given ? BlockPosArgument.getLoadedBlockPos(ctx, "pos") : player.blockPosition();
        Scans.Selection s = Scans.corner(player, pos, first);
        ok(ctx.getSource(), (first ? "First" : "Second") + " corner at " + pos.toShortString() + (s.complete() ? ": " + Scans.size(s) + "." : "."));
        return 1;
    }

    private static int openScan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return PackEditor.openScan(ctx.getSource().getPlayerOrException()) != null ? 1 : 0;
    }

    private static int saveScan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Scans.Selection sel = Scans.get(player).orElse(null);
        String problem = Scans.problem(sel);
        if (problem != null) {
            return fail(ctx.getSource(), problem);
        }
        PackEditor.Session s = PackEditor.session(player).filter(x -> x.key() == null).orElseGet(() -> PackEditor.scan(player, sel.box()));
        try {
            ResourceLocation key = PackEditor.save(player, s, StringArgumentType.getString(ctx, "name"), s.type(), s.fixes());
            ok(ctx.getSource(), "Saved it in My pack as a " + Requirements.word(s.type()) + " (" + key + "). It's in the Building Plans now.");
            return 1;
        } catch (Exception e) {
            return fail(ctx.getSource(), "Couldn't save it: " + e.getMessage());
        }
    }

    private static int clearScan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Scans.clear(ctx.getSource().getPlayerOrException());
        ok(ctx.getSource(), "Selection cleared.");
        return 1;
    }

    // ---- packs ------------------------------------------------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<BuildingDef> all = new ArrayList<>(BuildingPacks.all());
        all.sort(java.util.Comparator.comparing(BuildingDef::pack).thenComparing(BuildingDef::name));
        String pack = null;
        for (BuildingDef d : all) {
            if (!d.pack().equals(pack)) {
                pack = d.pack();
                String p = pack;
                ctx.getSource().sendSuccess(() -> Component.literal(p).withStyle(ChatFormatting.GOLD), false);
            }
            ctx.getSource().sendSuccess(() -> Component.literal(" " + d.name() + " (" + Requirements.word(d.type()) + ") ")
                    .append(Component.literal(d.key().toString()).withStyle(ChatFormatting.GRAY)), false);
        }
        ok(ctx.getSource(), "Shared packs for every world go in " + SharedPacks.folder() + ".");
        return all.size();
    }

    private static int palettes(CommandContext<CommandSourceStack> ctx) {
        for (Palette p : Palettes.all()) {
            ctx.getSource().sendSuccess(() -> Component.literal(p.name()).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" (" + p.id() + "): " + p.describe()).withStyle(ChatFormatting.GRAY)), false);
        }
        return Palettes.all().size();
    }

    private static Optional<BuildingDef> def(CommandContext<CommandSourceStack> ctx) {
        return BuildingPacks.get(ResourceLocationArgument.getId(ctx, "building"));
    }

    private static int edit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<BuildingDef> d = def(ctx);
        if (d.isEmpty()) {
            return fail(ctx.getSource(), "There's no such building in the packs.");
        }
        PackEditor.openExisting(ctx.getSource().getPlayerOrException(), d.get());
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation key = ResourceLocationArgument.getId(ctx, "building");
        if (!MyPack.contains(key)) {
            return fail(ctx.getSource(), "Only My pack's buildings can be deleted here; other packs are data packs of their own.");
        }
        if (!PackEditor.mayEdit(player)) {
            return fail(ctx.getSource(), "Only operators can change this world's building pack.");
        }
        try {
            if (!MyPack.delete(player.server, key)) {
                return fail(ctx.getSource(), "There's no " + key + " in My pack.");
            }
        } catch (Exception e) {
            return fail(ctx.getSource(), "Couldn't delete it: " + e.getMessage());
        }
        ok(ctx.getSource(), "Deleted " + key + " from My pack.");
        return 1;
    }

    private static int role(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<BuildingDef> d = def(ctx);
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ResourceLocationArgument.getId(ctx, "block"));
        Optional<Roles.Role> from = Roles.Role.of(StringArgumentType.getString(ctx, "from")), to = Roles.Role.of(StringArgumentType.getString(ctx, "to"));
        if (d.isEmpty() || block.isEmpty() || from.isEmpty() || to.isEmpty()) {
            return fail(ctx.getSource(), "Usage: /stad pack role <building> <block> <guessed role> <role>.");
        }
        List<Roles.Fix> fixes = new ArrayList<>(d.get().fixes());
        fixes.removeIf(f -> f.block() == block.get() && f.from() == from.get());
        if (from.get() != to.get()) {
            fixes.add(new Roles.Fix(block.get(), from.get(), to.get()));
        }
        PackEditor.Session s = new PackEditor.Session("command", d.get().key(), d.get().blueprint(), d.get().name(), d.get().type(), fixes,
                d.get().spec(), d.get().description());
        try {
            ResourceLocation key = PackEditor.save(player, s, d.get().name(), d.get().type(), fixes);
            long n = Roles.groups(Roles.analyse(d.get().blueprint(), fixes)).stream()
                    .filter(g -> g.block() == block.get() && g.guessed() == from.get()).mapToInt(Roles.Group::count).sum();
            ok(ctx.getSource(), n + " " + block.get().getName().getString() + " in " + d.get().name() + " are now " + to.get().label
                    + (key.equals(d.get().key()) ? "." : " (saved as a copy in My pack: " + key + ")."));
            return 1;
        } catch (Exception e) {
            return fail(ctx.getSource(), "Couldn't save it: " + e.getMessage());
        }
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PackEditor.mayEdit(player)) {
            return fail(ctx.getSource(), "Only operators can export this world's building pack.");
        }
        try {
            Path zip = MyPack.export(player.server, name);
            ok(ctx.getSource(), "Exported My pack to " + zip.toAbsolutePath() + ". Share it: it goes in a world's datapacks folder, or in "
                    + SharedPacks.FOLDER + "/ to have it in every world.");
            Stad.LOGGER.info("Exported My pack to {}", zip.toAbsolutePath());
            return 1;
        } catch (Exception e) {
            return fail(ctx.getSource(), "Couldn't export it: " + e.getMessage());
        }
    }

    // ---- the builder's questions ---------------------------------------------------------------------------------

    private static int answer(CommandContext<CommandSourceStack> ctx, boolean yes) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        Optional<Colony> colony = ColonyManager.get(player.server).all().stream().filter(c -> c.building(id).isPresent()
                && c.rank(player.getUUID()).atLeast(Rank.MEMBER)).findFirst();
        Optional<Building> b = colony.flatMap(c -> c.building(id));
        if (b.isEmpty()) {
            return fail(ctx.getSource(), "There's no building #" + id + " in your town.");
        }
        var offer = b.get().offer;
        String problem = yes ? Substitutions.accept(player, colony.get(), b.get()) : Substitutions.decline(player, colony.get(), b.get());
        if (problem != null) {
            return fail(ctx.getSource(), problem);
        }
        ok(ctx.getSource(), yes ? "The builder will " + offer.describe() + "." : "The builder will wait for more " + offer.from().label() + ".");
        return 1;
    }
}
