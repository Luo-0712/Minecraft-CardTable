package com.example.cardtable.block.custom;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

public class CardTableBlock extends BaseEntityBlock
{
    // Hitboxes in 1/16 blocks, must stay in sync with models/block/card_table.json
    private static final VoxelShape TABLE_TOP = Block.box(0.0D, 10.0D, 0.0D, 16.0D, 12.0D, 16.0D);
    private static final VoxelShape LEG_NORTH_WEST = Block.box(0.0D, 0.0D, 0.0D, 2.0D, 10.0D, 2.0D);
    private static final VoxelShape LEG_NORTH_EAST = Block.box(14.0D, 0.0D, 0.0D, 16.0D, 10.0D, 2.0D);
    private static final VoxelShape LEG_SOUTH_WEST = Block.box(0.0D, 0.0D, 14.0D, 2.0D, 10.0D, 16.0D);
    private static final VoxelShape LEG_SOUTH_EAST = Block.box(14.0D, 0.0D, 14.0D, 16.0D, 10.0D, 16.0D);

    private static final VoxelShape TABLE_SHAPE = Shapes.or(TABLE_TOP,
            LEG_NORTH_WEST, LEG_NORTH_EAST, LEG_SOUTH_WEST, LEG_SOUTH_EAST);

    public CardTableBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState blockState)
    {
        return new CardTableBlockEntity(position, blockState);
    }

    @Override
    public RenderShape getRenderShape(BlockState blockState)
    {
        return RenderShape.MODEL;
    }

    // Selection outline, raycast target and neighbour face culling (getOcclusionShape delegates here)
    @Override
    public VoxelShape getShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext)
    {
        return TABLE_SHAPE;
    }

    // Lets players walk into the gap between the legs instead of hitting a full cube
    @Override
    public VoxelShape getCollisionShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext)
    {
        return TABLE_SHAPE;
    }

    // Without this the light engine occludes light with a full cube and the underside turns black
    @Override
    public boolean useShapeForLightOcclusion(BlockState blockState)
    {
        return true;
    }

    // A freshly placed table mints its own group identity; merging with an
    // adjacent group (if any) is handled right after by the group service.
    @Override
    public void onPlace(BlockState state, Level level, BlockPos position, BlockState oldState, boolean isMoving)
    {
        super.onPlace(state, level, position, oldState, isMoving);
        TableGroupService.onTablePlaced(level, position);
    }

    // Adjacent survivors re-resolve their groups; splitting into independent
    // groups is safe because seats live per block.
    @Override
    public void onRemove(BlockState state, Level level, BlockPos position, BlockState newState, boolean isMoving)
    {
        TableGroupService.onTableRemoved(level, position);
        super.onRemove(state, level, position, newState, isMoving);
    }

    // Right-click seats the player at this specific table block (one seat per
    // table), then opens the fullscreen table view. Clicking an occupied
    // table still opens the view, as a spectator view.
    @Override
    public InteractionResult use(BlockState blockState, Level level, BlockPos position, Player player, InteractionHand hand, BlockHitResult hitResult)
    {
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer))
        {
            return InteractionResult.CONSUME;
        }

        TableGroupService.join(level, position, player);

        MenuProvider menuProvider = new SimpleMenuProvider(
                (containerId, inventory, openingPlayer) -> new CardTableMenu(containerId, inventory, position),
                Component.translatable("menu.cardtable.card_table"));
        NetworkHooks.openScreen(serverPlayer, menuProvider, buffer -> buffer.writeBlockPos(position));
        return InteractionResult.CONSUME;
    }
}
