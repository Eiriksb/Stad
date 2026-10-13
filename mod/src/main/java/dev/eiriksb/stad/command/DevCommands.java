package dev.eiriksb.stad.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.eiriksb.stad.net.DevUiPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Commands for trying screens out on a dev client from the server console (only with {@code -Dstad.devCommands=true}):
 * open an architect's table for a player, and drive their screen with clicks, keys and screenshots.
 */
final class DevCommands {
    private DevCommands() {}

    static boolean enabled() {
        return Boolean.getBoolean("stad.devCommands");
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dev").requires(s -> s.hasPermission(2))
                .then(Commands.literal("table").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                            dev.eiriksb.stad.architect.Tables.use(p, p.serverLevel(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), false);
                            return 1;
                        }))))
                .then(Commands.literal("ui").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("script", StringArgumentType.greedyString()).executes(ctx -> {
                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                            PacketDistributor.sendToPlayer(p, new DevUiPayload(StringArgumentType.getString(ctx, "script")));
                            ctx.getSource().sendSuccess(() -> Component.literal("Sent."), false);
                            return 1;
                        }))));
    }
}
