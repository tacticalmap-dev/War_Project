package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Renders the map area as a yellow forcefield wall around the whole rectangle.
 *
 * <p>The texture, shader, vertex format and additive blend mirror
 * {@code LevelRenderer.renderWorldBorder} (checked against its bytecode: {@code FORCEFIELD_LOCATION},
 * {@code BorderStatus.getColor()} into {@code setShaderColor}, {@code getPositionTexShader},
 * {@code QUADS/POSITION_TEX}, {@code blendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO)}, {@code depthMask(false)},
 * {@code disableCull} … {@code enableCull}); only the colour and the geometry source differ, because the
 * vanilla border is deliberately not used as a hard boundary any more.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MapBoundaryRenderer {
    private static final ResourceLocation FORCEFIELD = ResourceLocation.withDefaultNamespace("textures/misc/forcefield.png");
    private static final float YELLOW_RED = 1.0F;
    private static final float YELLOW_GREEN = 0.82F;
    private static final float YELLOW_BLUE = 0.25F;
    private static final float YELLOW_ALPHA = 0.65F;
    /** The vanilla border repeats the forcefield texture every two blocks; the wall keeps that density. */
    private static final float UV_PER_BLOCK = 0.5F;
    private static final long SCROLL_PERIOD_MILLIS = 3000L;

    private MapBoundaryRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientMapState.ClientBounds bounds = ClientMapState.bounds();
        // The map area lives in the overworld, so other dimensions stay untouched.
        if (minecraft.level == null || bounds == null || !Level.OVERWORLD.equals(minecraft.level.dimension())) {
            return;
        }
        drawWall(event, minecraft, bounds);
    }

    private static void drawWall(RenderLevelStageEvent event, Minecraft minecraft, ClientMapState.ClientBounds bounds) {
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        float x1 = (float) bounds.minBlockX();
        float x2 = (float) bounds.maxBlockX();
        float z1 = (float) bounds.minBlockZ();
        float z2 = (float) bounds.maxBlockZ();
        float yBottom = minecraft.level.getMinBuildHeight();
        float yTop = minecraft.level.getMaxBuildHeight();
        float scroll = (float) (Util.getMillis() % SCROLL_PERIOD_MILLIS) / (float) SCROLL_PERIOD_MILLIS;
        // Render distance in blocks: the boundary behaves like the terrain, so nothing beyond it is drawn.
        double renderDistance = Math.max(1, minecraft.options.getEffectiveRenderDistance()) * 16.0D;
        double camX = camera.x;
        double camZ = camera.z;

        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, FORCEFIELD);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(YELLOW_RED, YELLOW_GREEN, YELLOW_BLUE, YELLOW_ALPHA);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // West (x = x1) then east (x = x2); both run along Z.
        clippedWall(buffer, matrix, true, x1, z1, z2, camX, camZ, renderDistance, yBottom, yTop, camera, scroll);
        clippedWall(buffer, matrix, true, x2, z2, z1, camX, camZ, renderDistance, yBottom, yTop, camera, scroll);
        // North (z = z1) then south (z = z2); both run along X.
        clippedWall(buffer, matrix, false, z1, x1, x2, camZ, camX, renderDistance, yBottom, yTop, camera, scroll);
        clippedWall(buffer, matrix, false, z2, x2, x1, camZ, camX, renderDistance, yBottom, yTop, camera, scroll);
        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draws one wall clipped to the render-distance sphere around the camera, so a boundary far outside
     * the loaded area is not drawn as a wall hanging in the fog. {@code fixed} is the wall's own
     * coordinate on its perpendicular axis, {@code from}/{@code to} its extent along the wall.
     */
    private static void clippedWall(BufferBuilder buffer, Matrix4f matrix, boolean alongZ, float fixed,
                                    float from, float to, double cameraFixed, double cameraAlong, double renderDistance,
                                    float yBottom, float yTop, Vec3 camera, float scroll) {
        double chord = chordHalfLength(renderDistance, Math.abs(fixed - cameraFixed));
        if (chord <= 0.0D) {
            return;
        }
        float low = (float) Math.max(Math.min(from, to), cameraAlong - chord);
        float high = (float) Math.min(Math.max(from, to), cameraAlong + chord);
        if (low >= high) {
            return;
        }
        // Keep the original winding, so the two walls of one axis keep their texture direction.
        float start = from <= to ? low : high;
        float end = from <= to ? high : low;
        if (alongZ) {
            wall(buffer, matrix, fixed, start, fixed, end, start * UV_PER_BLOCK, end * UV_PER_BLOCK, yBottom, yTop, camera, scroll);
        } else {
            wall(buffer, matrix, start, fixed, end, fixed, start * UV_PER_BLOCK, end * UV_PER_BLOCK, yBottom, yTop, camera, scroll);
        }
    }

    /** Half length of the chord the render-distance sphere cuts out of a wall {@code offset} blocks away. */
    private static double chordHalfLength(double renderDistance, double offset) {
        if (offset >= renderDistance) {
            return 0.0D;
        }
        return Math.sqrt(renderDistance * renderDistance - offset * offset);
    }

    /**
     * One vertical quad between two corners. Vertices are camera relative, exactly how
     * {@code renderWorldBorder} feeds its {@code POSITION_TEX} builder, and are wound both ways by
     * {@code disableCull} so the wall reads from inside and outside alike.
     */
    private static void wall(BufferBuilder buffer, Matrix4f matrix, float ax, float az, float bx, float bz,
                             float uA, float uB, float yBottom, float yTop, Vec3 camera, float scroll) {
        float vBottom = yBottom * UV_PER_BLOCK + scroll;
        float vTop = yTop * UV_PER_BLOCK + scroll;
        float camX = (float) camera.x;
        float camY = (float) camera.y;
        float camZ = (float) camera.z;
        buffer.vertex(matrix, ax - camX, yBottom - camY, az - camZ).uv(uA, vBottom).endVertex();
        buffer.vertex(matrix, ax - camX, yTop - camY, az - camZ).uv(uA, vTop).endVertex();
        buffer.vertex(matrix, bx - camX, yTop - camY, bz - camZ).uv(uB, vTop).endVertex();
        buffer.vertex(matrix, bx - camX, yBottom - camY, bz - camZ).uv(uB, vBottom).endVertex();
    }
}
