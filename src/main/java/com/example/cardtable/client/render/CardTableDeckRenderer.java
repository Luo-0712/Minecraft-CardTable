package com.example.cardtable.client.render;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.client.item.DeckIconResolver;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;

import java.util.Random;

/**
 * World-side face-down deck pile for the card table: when the master block's
 * {@code TableGroupState#deckStack} is non-empty, a small stack of cards is
 * drawn on the table top so the table visibly "has cards loaded" without
 * opening the screen.
 *
 * <p>Section blocks never carry a deck (their group state is the default
 * empty one), so only the master block of a merged group shows the pile —
 * no extra "am I the master" check is needed. The deck's card back is picked
 * through the back-first fallback chain in {@link DeckIconResolver}.</p>
 *
 * <p>The renderer reads the block entity's synced state every frame, so the
 * existing {@code syncGroup} path (deck inserted/removed) updates the world
 * appearance with no new network protocol. Empty tables return before doing
 * any work.</p>
 */
public final class CardTableDeckRenderer implements BlockEntityRenderer<CardTableBlockEntity>
{
    /** Table top surface (the block model's tabletop spans y=10..12/16). */
    private static final float TABLE_TOP_Y = 12.0F / 16.0F;

    /** Card footprint in blocks: 6x8 pixels at the 1 pixel = 1/16 scale. */
    private static final float CARD_WIDTH = 6.0F / 16.0F;
    private static final float CARD_HEIGHT = 8.0F / 16.0F;

    /** One card's thickness; the pile is CARD_THICKNESS tall per layer. */
    private static final float CARD_THICKNESS = 1.0F / 64.0F;

    /** Lift above the tabletop so the bottom card never z-fights the table. */
    private static final float BASE_LIFT = 1.0F / 256.0F;

    /** Cards in the static pile. */
    private static final int PILE_LAYERS = 4;

    /** Per-card positional jitter (blocks) and rotational jitter (degrees). */
    private static final float MAX_OFFSET = 1.0F / 32.0F;
    private static final float MAX_ANGLE_JITTER = 8.0F;

    /** Side faces sample a tiny strip near the texture centre for a flat edge colour. */
    private static final float SIDE_UV_RADIUS = 1.0F / 64.0F;

    /** Standard provider shape: the renderer needs nothing from the context. */
    public CardTableDeckRenderer(BlockEntityRendererProvider.Context context)
    {
    }

    @Override
    public void render(CardTableBlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay)
    {
        ItemStack deckStack = blockEntity.getGroupState().getDeckStack();
        if (deckStack.isEmpty())
        {
            return; // bare table: no pile, zero cost
        }
        DeckIconResolver.DeckIcon icon = DeckIconResolver.backForStack(deckStack);
        VertexConsumer consumer = bufferSource.getBuffer(icon.renderType());

        // Seeded per block position: every frame re-runs the same sequence, so
        // the pile is stable, yet neighbouring tables get different piles.
        Random random = new Random(((long) blockEntity.getBlockPos().hashCode()) * 341873128712L
                + 132897987541L);
        float baseRotation = random.nextFloat() * 360.0F;

        poseStack.pushPose();
        poseStack.translate(0.5D, TABLE_TOP_Y + BASE_LIFT, 0.5D);
        for (int layer = 0; layer < PILE_LAYERS; layer++)
        {
            float angle = baseRotation + (random.nextFloat() * 2.0F - 1.0F) * MAX_ANGLE_JITTER * layer;
            float offsetX = (random.nextFloat() * 2.0F - 1.0F) * MAX_OFFSET;
            float offsetZ = (random.nextFloat() * 2.0F - 1.0F) * MAX_OFFSET;

            poseStack.pushPose();
            poseStack.translate(offsetX, layer * CARD_THICKNESS, offsetZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(angle));
            emitCard(consumer, poseStack.last(), icon, packedLight, packedOverlay);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** Emits one card: the face-up top showing the back sprite, plus four thin sides. */
    private static void emitCard(VertexConsumer consumer, PoseStack.Pose pose,
            DeckIconResolver.DeckIcon icon, int packedLight, int packedOverlay)
    {
        // Letterbox the sprite into the card footprint without distortion,
        // mirroring DeckItemRenderer's aspect logic but on a 6:8 face.
        float textureAspect = icon.quadWidth() / icon.quadHeight();
        float faceAspect = CARD_WIDTH / CARD_HEIGHT;
        float halfWidth;
        float halfHeight;
        if (textureAspect >= faceAspect)
        {
            halfWidth = CARD_WIDTH / 2.0F;
            halfHeight = CARD_WIDTH / textureAspect / 2.0F;
        }
        else
        {
            halfHeight = CARD_HEIGHT / 2.0F;
            halfWidth = CARD_HEIGHT * textureAspect / 2.0F;
        }

        emitTopFace(consumer, pose, halfWidth, halfHeight, packedLight, packedOverlay);
        emitSideFaces(consumer, pose, halfWidth, halfHeight, packedLight, packedOverlay);
    }

    /**
     * The card's visible face, normal +Y, counter-clockwise seen from above.
     * The sprite's top edge (v=0) faces north so the back reads upright.
     */
    private static void emitTopFace(VertexConsumer consumer, PoseStack.Pose pose,
            float halfWidth, float halfHeight, int packedLight, int packedOverlay)
    {
        emitVertex(consumer, pose, -halfWidth, CARD_THICKNESS, -halfHeight, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, CARD_THICKNESS, halfHeight, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, CARD_THICKNESS, halfHeight, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, CARD_THICKNESS, -halfHeight, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, packedLight, packedOverlay);
    }

    /**
     * Four thin side quads sampling a centre strip of the back texture, so the
     * pile reads as stacked cards with an edge rather than floating paper.
     * Windings are counter-clockwise seen from the side each normal points at.
     */
    private static void emitSideFaces(VertexConsumer consumer, PoseStack.Pose pose,
            float halfWidth, float halfHeight, int packedLight, int packedOverlay)
    {
        float u0 = 0.5F - SIDE_UV_RADIUS;
        float u1 = 0.5F + SIDE_UV_RADIUS;
        float v0 = 0.5F - SIDE_UV_RADIUS;
        float v1 = 0.5F + SIDE_UV_RADIUS;
        float y0 = 0.0F;
        float y1 = CARD_THICKNESS;

        // north (-Z)
        emitVertex(consumer, pose, -halfWidth, y1, -halfHeight, u0, v0, 0.0F, 0.0F, -1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y1, -halfHeight, u1, v0, 0.0F, 0.0F, -1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y0, -halfHeight, u1, v1, 0.0F, 0.0F, -1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y0, -halfHeight, u0, v1, 0.0F, 0.0F, -1.0F, packedLight, packedOverlay);

        // south (+Z)
        emitVertex(consumer, pose, halfWidth, y1, halfHeight, u0, v0, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y1, halfHeight, u1, v0, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y0, halfHeight, u1, v1, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y0, halfHeight, u0, v1, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);

        // west (-X)
        emitVertex(consumer, pose, -halfWidth, y0, -halfHeight, u0, v0, -1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y0, halfHeight, u1, v0, -1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y1, halfHeight, u1, v1, -1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, -halfWidth, y1, -halfHeight, u0, v1, -1.0F, 0.0F, 0.0F, packedLight, packedOverlay);

        // east (+X)
        emitVertex(consumer, pose, halfWidth, y0, -halfHeight, u0, v0, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y1, -halfHeight, u0, v1, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y1, halfHeight, u1, v1, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
        emitVertex(consumer, pose, halfWidth, y0, halfHeight, u1, v0, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
    }

    /**
     * Writes one vertex in the order the entity shaders expect
     * (position, colour, uv, overlay, lightmap, normal); the normal comes in
     * card-local space and is rotated with the pose, like DeckItemRenderer.
     */
    private static void emitVertex(VertexConsumer consumer, PoseStack.Pose pose,
            float x, float y, float z, float u, float v,
            float normalX, float normalY, float normalZ, int packedLight, int packedOverlay)
    {
        consumer.vertex(pose.pose(), x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(pose.normal(), normalX, normalY, normalZ)
                .endVertex();
    }
}
