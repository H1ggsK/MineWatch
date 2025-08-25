package com.h1ggsk.minewatch.blocks.lore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class CameraHelper {
    private static Field timerField;
    private static Method setupCameraTransformMethod;

    static {
        try {
            // dev names
            try {
                timerField = Minecraft.class.getDeclaredField("timer");
            } catch (NoSuchFieldException e) {
                // obf fallback - try different field names
                try {
                    timerField = Minecraft.class.getDeclaredField("timer"); // obf name for timer
                } catch (NoSuchFieldException ex) {
                    timerField = Minecraft.class.getDeclaredField("timer");
                }
            }
            timerField.setAccessible(true);

            try {
                setupCameraTransformMethod = net.minecraft.client.renderer.EntityRenderer.class
                        .getDeclaredMethod("setupCameraTransform", float.class, int.class);
            } catch (NoSuchMethodException e) {
                // obf fallback
                setupCameraTransformMethod = net.minecraft.client.renderer.EntityRenderer.class
                        .getDeclaredMethod("setupCameraTransform", float.class, int.class); // obf name for setupCameraTransform
            }
            setupCameraTransformMethod.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void captureFromBlock(World world, BlockPos pos, EnumFacing facing) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!world.isRemote) return;

        Entity oldView = mc.getRenderViewEntity();
        boolean oldHideGUI = mc.gameSettings.hideGUI;
        Framebuffer mainFBO = mc.getFramebuffer();

        try {
            // ---- temporary camera ----
            ASMMEntity cam = new ASMMEntity(world);

            double camX = pos.getX() + 0.5;
            double camY = pos.getY() + 0.5;
            double camZ = pos.getZ() + 0.5;

            float yaw = 0f, pitch = 0f;
            switch (facing) {
                case NORTH: yaw = 180f; break;
                case SOUTH: yaw = 0f;   break;
                case WEST:  yaw = 90f;  break;
                case EAST:  yaw = 270f; break;
                case UP:    pitch = -90f; break;
                case DOWN:  pitch = 90f;  break;
            }

            // nudge camera half a pixel away from the block face to avoid clipping
            double off = 0.01;
            switch (facing) {
                case NORTH: camZ += off; break;
                case SOUTH: camZ -= off; break;
                case WEST:  camX += off; break;
                case EAST:  camX -= off; break;
                case UP:    camY -= off; break;
                case DOWN:  camY += off; break;
            }

            cam.setPosition(camX, camY, camZ);
            cam.rotationYaw = yaw;
            cam.rotationPitch = pitch;

            mc.setRenderViewEntity(cam);
            mc.gameSettings.hideGUI = true;

            // ---- render to our own FBO ----
            int w = mc.displayWidth;
            int h = mc.displayHeight;

            // Create FBO with proper settings
            Framebuffer fbo = new Framebuffer(w, h, true);
            fbo.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
            fbo.framebufferClear();

            // Bind our FBO
            fbo.bindFramebuffer(true);

            // Setup viewport and clear
            GlStateManager.viewport(0, 0, w, h);
            GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Get partial ticks via reflection
            net.minecraft.util.Timer timer = (net.minecraft.util.Timer) timerField.get(mc);
            float pt = timer != null ? timer.renderPartialTicks : 0f;

            try {
                // Set up camera transform
                setupCameraTransformMethod.invoke(mc.entityRenderer, pt, 0);

                // Render the world
                mc.entityRenderer.renderWorld(pt, System.nanoTime());

                // Update the FBO texture
                fbo.bindFramebufferTexture();

            } catch (Exception e) {
                e.printStackTrace();
            }

            // Restore main FBO first
            mainFBO.bindFramebuffer(true);

            // Save the screenshot
            File ssdir = new File(mc.gameDir, "screenshots");
            if (!ssdir.exists()) {
                ssdir.mkdirs();
            }
            String screenshotName = "camera_" + System.currentTimeMillis() + ".png";

            // Use the correct method for saving FBO content
            try {
                // Bind our FBO for reading
                fbo.bindFramebuffer(false);

                // Save the screenshot
                ScreenShotHelper.saveScreenshot(
                        ssdir,
                        screenshotName,
                        w,
                        h,
                        fbo
                );

                // Notify the player
                mc.player.sendMessage(new TextComponentString("Saved screenshot: " + screenshotName));

            } catch (Exception e) {
                e.printStackTrace();
                mc.player.sendMessage(new TextComponentString("Failed to save screenshot: " + e.getMessage()));
            }

        } catch (Exception e) {
            e.printStackTrace();
            mc.player.sendMessage(new TextComponentString("Failed to capture screenshot: " + e.getMessage()));
        } finally {
            // Always restore the main FBO
            mainFBO.bindFramebuffer(true);

            // Restore original settings
            mc.setRenderViewEntity(oldView);
            mc.gameSettings.hideGUI = oldHideGUI;

            // Force a redraw to fix the display
            mc.entityRenderer.updateCameraAndRender(mc.getRenderPartialTicks(), System.nanoTime());
        }
    }
}