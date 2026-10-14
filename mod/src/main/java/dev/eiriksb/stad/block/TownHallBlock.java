package dev.eiriksb.stad.block;

import com.mojang.serialization.MapCodec;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.ColonyView;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/**
 * Placing a town hall founds a town around it; right-click shows the town, sneak + right-click opens its storage
 * (where citizens find food). Breaking it (only the founder can) ends the town.
 */
public class TownHallBlock extends BaseEntityBlock {
    public static final MapCodec<TownHallBlock> CODEC = simpleCodec(TownHallBlock::new);

    /** the desk and the book on it */
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE = net.minecraft.world.phys.shapes.Shapes.or(
            box(1, 0, 1, 15, 13, 15), box(0, 13, 0, 16, 15, 16));
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> b) {
        b.add(FACING);
    }

    /** It faces whoever places it. */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                                  net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    public TownHallBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TownHallBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof ServerPlayer player) {
            found(server, pos, player);
        }
    }

    /** Founds a town at a town hall that was just placed (or tells the player why not and pops the block off). */
    public static void found(ServerLevel level, BlockPos pos, ServerPlayer player) {
        ColonyManager.Founding f = ColonyManager.get(level.getServer()).found(level, pos, player.getUUID(), player.getGameProfile().getName());
        if (f.colony() == null) {
            player.sendSystemMessage(Component.literal(f.problem()).withStyle(ChatFormatting.RED));
            level.destroyBlock(pos, !player.isCreative());
            return;
        }
        player.sendSystemMessage(Component.literal("You founded " + f.colony().name() + "! ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Place a bed or two and newcomers will move in. The guide in the corner of the screen shows"
                        + " what to do next (U hides it), and the town hall has more: right-click it, or sneak and right-click it for its"
                        + " storage, where food and building materials go.").withStyle(ChatFormatting.GRAY)));
    }

    static Optional<Colony> colonyAt(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return Optional.empty();
        }
        return ColonyManager.get(server.getServer()).at(level.dimension(), pos).filter(c -> c.center.equals(pos));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        Optional<Colony> colony = colonyAt(level, pos);
        if (colony.isEmpty()) {
            sp.displayClientMessage(Component.literal("This town hall doesn't belong to a town.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }
        if (sp.isShiftKeyDown()) {
            openStorage(sp, (ServerLevel) level, pos);
        } else {
            ColonyView.send(sp, colony.get(), (ServerLevel) level);
        }
        return InteractionResult.CONSUME;
    }

    /** The town hall's storage, for the town's members. */
    public static void openStorage(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Optional<Colony> colony = colonyAt(level, pos);
        if (colony.isEmpty() || !(level.getBlockEntity(pos) instanceof TownHallBlockEntity hall)) {
            return;
        }
        if (!colony.get().rank(player.getUUID()).atLeast(Rank.MEMBER)) {
            player.displayClientMessage(Component.literal("Only members of " + colony.get().name() + " can open its storage.")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, hall.storage()),
                Component.literal(colony.get().name() + " town hall")));
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) {
            colonyAt(level, pos).ifPresent(c -> {
                ColonyManager.get(server.getServer()).dissolve(server.getServer(), c);
                server.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal(c.name() + " is no more: its town hall was taken down.").withStyle(ChatFormatting.GRAY), false);
            });
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof TownHallBlockEntity hall) {
            net.minecraft.world.Containers.dropContents(level, pos, hall.storage());
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
