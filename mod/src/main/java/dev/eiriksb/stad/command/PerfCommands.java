package dev.eiriksb.stad.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.perf.Perf;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Optional;

/** {@code /stad perf}: where Stad's share of the server tick goes; {@code /stad perf crowd <n>} fills a town to test it. */
final class PerfCommands {
    /** Marks the citizens {@code crowd} made, so {@code crowd clear} takes away only those. */
    static final String CROWD = "stad_crowd";

    private PerfCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("perf").requires(s -> s.hasPermission(2)).executes(PerfCommands::report)
                .then(Commands.literal("reset").executes(ctx -> {
                    Perf.reset();
                    ctx.getSource().sendSuccess(() -> Component.literal("Cleared the timings."), false);
                    return 1;
                }))
                .then(Commands.literal("crowd").then(Commands.argument("count", IntegerArgumentType.integer(1, 200)).executes(PerfCommands::crowd))
                        .then(Commands.literal("clear").executes(PerfCommands::clear)));
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        var server = s.getServer();
        int loaded = 0;
        int total = 0;
        for (Colony c : ColonyManager.get(server).all()) {
            total += c.population();
            ServerLevel level = server.getLevel(c.dimension);
            if (level != null) {
                loaded += (int) c.citizens().stream().filter(r -> level.getEntity(r.uuid) != null).count();
            }
        }
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        int citizens = loaded;
        int all = total;
        s.sendSuccess(() -> Component.literal(String.format("Server: %.1f ms per tick (50 is the limit). Citizens: %d loaded of %d.", mspt, citizens, all))
                .withStyle(ChatFormatting.GOLD), false);
        for (Perf.Average a : Perf.averages()) {
            s.sendSuccess(() -> Component.literal(String.format(" %s: %.2f ms/tick (worst tick %.1f ms), %.0f calls/tick", a.section(), a.msPerTick(),
                    a.maxMs(), a.callsPerTick())).withStyle(ChatFormatting.GRAY), false);
        }
        var llm = dev.eiriksb.theywilltalk.api.TheyWillTalkApi.get().llm();
        s.sendSuccess(() -> Component.literal(" Language model: " + (llm.ready() ? llm.backlog() + " waiting" : "not running")
                + "; " + dev.eiriksb.stad.agent.ThinkBudget.describe(server)).withStyle(ChatFormatting.GRAY), false);
        s.sendSuccess(() -> Component.literal(" " + dev.eiriksb.stad.colony.ColonyChunks.describe(server)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Adds {@code count} citizens to the town, with jobs, around its town hall. For load testing. */
    private static int crowd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        Optional<Colony> found = StadCommands.here(s);
        if (found.isEmpty()) {
            s.sendFailure(Component.literal("Stand in a town."));
            return 0;
        }
        Colony colony = found.get();
        ServerLevel level = s.getServer().getLevel(colony.dimension);
        int count = IntegerArgumentType.getInteger(ctx, "count");
        List<String> jobs = List.of(Jobs.FARMER, Jobs.LUMBERJACK, Jobs.COURIER, Jobs.GUARD, Jobs.FARMER, Jobs.LUMBERJACK);
        boolean builder = colony.citizens().stream().anyMatch(r -> Jobs.BUILDER.equals(r.job));
        int made = 0;
        for (int i = 0; i < count && level != null; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            int dist = 6 + level.random.nextInt(Math.max(1, colony.radius / 2));
            BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    colony.center.offset((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist)));
            CitizenEntity c = StadRegistries.CITIZEN.get().create(level);
            if (c == null || !level.isLoaded(at)) {
                continue;
            }
            c.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360, 0);
            c.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.COMMAND, null);
            c.addTag(CROWD);
            level.addFreshEntity(c);
            ColonyManager.get(s.getServer()).join(colony, c);
            String job = !builder ? Jobs.BUILDER : jobs.get(i % jobs.size());
            builder = true;
            colony.citizen(c.getUUID()).ifPresent(r -> r.job = job);
            made++;
        }
        colony.changed();
        int n = made;
        s.sendSuccess(() -> Component.literal("Added " + n + " citizens to " + colony.name() + " (" + colony.population() + " now)."), true);
        return n;
    }

    /** Takes away the citizens {@code crowd} added. */
    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        int n = 0;
        for (ServerLevel level : s.getServer().getAllLevels()) {
            for (var e : level.getEntities(StadRegistries.CITIZEN.get(), c -> c.getTags().contains(CROWD))) {
                e.colony().ifPresent(colony -> colony.removeCitizen(e.getUUID()));
                e.discard();
                n++;
            }
        }
        int removed = n;
        s.sendSuccess(() -> Component.literal("Took away " + removed + " test citizens."), true);
        return n;
    }
}
