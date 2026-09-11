package com.example.cardtable.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a deck item using the sprite {@link DeckIconResolver} picks for its
 * {@code DeckId}, so every deck looks like the set it actually holds.
 *
 * <p>A custom renderer is required rather than a plain model: the sprite varies
 * per item stack (it is read from that set's {@code pack.json}) and most of the
 * pack textures live outside the block atlas, so no static {@code layer0} can
 * express them.</p>
 *
 * <p>The vanilla pipeline hands this method a matrix that already has the
 * model's {@code display} transform for the current {@link ItemDisplayContext}
 * applied and has been shifted so the item's 0..1 cube is centred on the
 * origin, which is exactly the space {@code item/generated} draws in. The icon
 * is therefore emitted straight into 0..1 and needs no transform of its own.</p>
 */
public final class DeckItemRenderer extends BlockEntityWithoutLevelRenderer
{
    /** Half the thickness of a vanilla generated item (1/16 block), in model units. */
    private static final float HALF_THICKNESS = 1.0F / 32.0F;

    public DeckItemRenderer()
    {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay)
    {
        DeckIconResolver.DeckIcon icon = DeckIconResolver.forStack(stack);
        VertexConsumer consumer = bufferSource.getBuffer(icon.renderType());
        PoseStack.Pose pose = poseStack.last();

        // Letterbox within the item quad: a portrait card back keeps its aspect
        // ratio and gets pillarboxed rather than stretched into a square.
        float minX = (1.0F - icon.quadWidth()) / 2.0F;
        float maxX = minX + icon.quadWidth();
        float minY = (1.0F - icon.quadHeight()) / 2.0F;
        float maxY = minY + icon.quadHeight();

        // Two faces, each with its own upright mapping, keep the sprite readable
        // from either side in hand, in frames and on the ground.
        emitQuad(consumer, pose, minX, minY, maxX, maxY, HALF_THICKNESS, 1.0F, false, packedLight, packedOverlay);
        emitQuad(consumer, pose, minX, minY, maxX, maxY, -HALF_THICKNESS, -1.0F, true, packedLight, packedOverlay);
    }

    private static void emitQuad(VertexConsumer consumer, PoseStack.Pose pose,
            float minX, float minY, float maxX, float maxY,
            float z, float normalZ, boolean mirrored, int packedLight, int packedOverlay)
    {
        // Mirroring swaps which model-space edge is the texture's left edge, so
        // the sprite reads the right way round when seen from the far side.
        float leftX = mirrored ? maxX : minX;
        float rightX = mirrored ? minX : maxX;

        // Bottom-left, bottom-right, top-right, top-left: counter-clockwise as
        // seen from the side the normal points at, with v = 0 at the PNG's top.
        emitVertex(consumer, pose, leftX, maxY, z, 0.0F, 0.0F, normalZ, packedLight, packedOverlay);
        emitVertex(consumer, pose, rightX, maxY, z, 1.0F, 0.0F, normalZ, packedLight, packedOverlay);
        emitVertex(consumer, pose, rightX, minY, z, 1.0F, 1.0F, normalZ, packedLight, packedOverlay);
        emitVertex(consumer, pose, leftX, minY, z, 0.0F, 1.0F, normalZ, packedLight, packedOverlay);
    }

    /**
     * Writes one vertex in the order the entity shaders expect
     * (position, colour, uv, overlay, lightmap, normal); see vanilla's own
     * {@code ItemRenderer.renderQuadList}.
     */
    private static void emitVertex(VertexConsumer consumer, PoseStack.Pose pose,
            float x, float y, float z, float u, float v, float normalZ, int packedLight, int packedOverlay)
    {
        consumer.vertex(pose.pose(), x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(pose.normal(), 0.0F, 0.0F, normalZ)
                .endVertex();
    }
}
