package dev.eiriksb.stad.block;

import com.mojang.serialization.MapCodec;
import dev.eiriksb.stad.architect.Tables;
import dev.eiriksb.stad.item.ScanWandItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The architect's table (PLAN.md §6.8): right-click it with a scan wand, or the building plans with an area marked out,
 * to make the corners you marked its site, or without to open it: a 3D view of the site where you design a building for it.
 */
public class ArchitectTableBlock extends BaseEntityBlock {
    public static final MapCodec<ArchitectTableBlock> CODEC = simpleCodec(ArchitectTableBlock::new);

    /** the legs and the board, roughly */
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE = net.minecraft.world.phys.shapes.Shapes.or(
            box(1, 0, 1, 15, 10, 15), box(0, 10, 0, 16, 16, 16));
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

    public ArchitectTableBlock(Properties props) {
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
        return new ArchitectTableBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        dev.eiriksb.stad.item.PlanArea area = stack.getItem() instanceof dev.eiriksb.stad.item.BuildingPlansItem
                ? dev.eiriksb.stad.item.BuildingPlansItem.area(stack) : null;
        if (!(stack.getItem() instanceof ScanWandItem) && (area == null || !area.complete())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player instanceof ServerPlayer sp) {
            Tables.use(sp, (ServerLevel) level, pos, area == null, area);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            Tables.use(sp, (ServerLevel) level, pos, false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
