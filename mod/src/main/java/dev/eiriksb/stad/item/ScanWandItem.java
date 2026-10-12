package dev.eiriksb.stad.item;

import dev.eiriksb.stad.pack.PackEditor;
import dev.eiriksb.stad.pack.Scans;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * The scan wand (PLAN.md §6.5): left-click one corner of a building, right-click the opposite one, then sneak and
 * use it in the air to open the pack editor and save the building into My pack.
 */
public class ScanWandItem extends Item {
    public ScanWandItem(Properties props) {
        super(props);
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ScanWandItem::leftClick);
    }

    /** Left-click: the first corner (and never breaks the block). */
    private static void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof ScanWandItem)) {
            return;
        }
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START && event.getEntity() instanceof ServerPlayer player) {
            report(player, Scans.corner(player, event.getPos(), true), event.getPos(), "First");
        }
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    /** Right-click a block: the second corner. */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getPlayer() instanceof ServerPlayer player) {
            if (player.isShiftKeyDown()) {
                PackEditor.openScan(player);
            } else {
                report(player, Scans.corner(player, ctx.getClickedPos(), false), ctx.getClickedPos(), "Second");
            }
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    /** Sneak and use in the air: scan what's marked. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) {
            if (player.isShiftKeyDown()) {
                PackEditor.openScan(sp);
            } else {
                sp.displayClientMessage(Component.literal(Scans.get(sp).filter(Scans.Selection::complete)
                        .map(s -> "Marked " + Scans.size(s) + ". Sneak and use the wand to scan it.")
                        .orElse("Left-click one corner of a building, right-click the opposite corner.")).withStyle(ChatFormatting.GRAY), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    private static void report(ServerPlayer player, Scans.Selection s, BlockPos pos, String which) {
        String msg = which + " corner at " + pos.toShortString() + (s.complete() ? ": " + Scans.size(s) + ". Sneak and use the wand to scan it." : ".");
        String problem = s.complete() ? Scans.problem(s) : null;
        player.displayClientMessage(Component.literal(problem != null ? problem : msg)
                .withStyle(problem != null ? ChatFormatting.RED : ChatFormatting.AQUA), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Left-click and right-click opposite corners of a building,").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("then sneak and use it to save the building in My pack.").withStyle(ChatFormatting.GRAY));
    }
}
