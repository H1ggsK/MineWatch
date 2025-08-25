package com.h1ggsk.minewatch.blocks;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber
public class BlockCamera extends Block {
    public static final PropertyDirection FACING = PropertyDirection.create("facing");
    private static boolean screenshotScheduled = false;
    private static BlockPos cameraPos;
    private static EnumFacing cameraFacing;
    private static float cameraYaw = 0f;
    private static float cameraPitch = 0f;
    private static ClientViewPlayer cameraView = null;

    // Executor for background saving
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "camera-screenshot-saver");
        t.setDaemon(true);
        return t;
    });

    // Resolution scale for screenshots: 1.0 = full size, 0.5 = half size, etc.
    // Lower this to reduce main-thread time for glReadPixels and to reduce memory/encoding time.
    private static final double RESOLUTION_SCALE = 1.0; // change to 0.5 or 0.33 for quicker captures

    public BlockCamera() {
        super(Material.IRON);
        setRegistryName("camera_block");
        setHardness(2.0F);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ,
                                            int meta, EntityLivingBase placer) {
        return this.getDefaultState().withProperty(FACING, facing);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            // Schedule screenshot for next tick
            screenshotScheduled = true;
            cameraPos = pos;
            cameraFacing = state.getValue(FACING);

            // Calculate camera rotation based on facing
            calculateCameraRotation();
        }
        return true;
    }

    private static void calculateCameraRotation() {
        Minecraft mc = Minecraft.getMinecraft();

        switch (cameraFacing) {
            case NORTH:
                cameraYaw = 180f;
                cameraPitch = 0f;
                break;
            case SOUTH:
                cameraYaw = 0f;
                cameraPitch = 0f;
                break;
            case WEST:
                cameraYaw = 90f;
                cameraPitch = 0f;
                break;
            case EAST:
                cameraYaw = 270f;
                cameraPitch = 0f;
                break;
            case UP:
                cameraYaw = mc.player.rotationYaw;
                cameraPitch = -90f;
                break;
            case DOWN:
                cameraYaw = mc.player.rotationYaw;
                cameraPitch = 90f;
                break;
        }
    }

    @SubscribeEvent
    public static void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        if (cameraView != null && Minecraft.getMinecraft().getRenderViewEntity() == cameraView) {
            // Force the exact camera rotation we want
            event.setYaw(cameraYaw);
            event.setPitch(cameraPitch);
            event.setRoll(0f); // Ensure no roll
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && screenshotScheduled) {
            screenshotScheduled = false;
            takeScreenshotFromCameraPosition();
        }
    }

    private static void takeScreenshotFromCameraPosition() {
        Minecraft mc = Minecraft.getMinecraft();

        // Store original settings
        Entity originalViewEntity = mc.getRenderViewEntity();
        boolean originalHideGUI = mc.gameSettings.hideGUI;
        Framebuffer framebuffer = null;

        try {
            // Create a temporary camera entity
            cameraView = new ClientViewPlayer(mc.world);

            // Calculate camera position
            double camX = cameraPos.getX() + 0.5;
            double camY = cameraPos.getY() + 0.5;
            double camZ = cameraPos.getZ() + 0.5;

            // Nudge camera slightly away from the block face
            double off = 0.3;
            switch (cameraFacing) {
                case NORTH: camZ += off; break;
                case SOUTH: camZ -= off; break;
                case WEST:  camX += off; break;
                case EAST:  camX -= off; break;
                case UP:    camY -= off; break;
                case DOWN:  camY += off; break;
            }

            // Position the camera (rotation will be handled by the CameraSetup event)
            cameraView.setLocationAndAngles(camX, camY, camZ, cameraYaw, cameraPitch);
            cameraView.world = mc.world;

            // Pick target framebuffer size (scaled)
            int fullW = mc.displayWidth;
            int fullH = mc.displayHeight;
            int targetW = Math.max(1, (int) Math.round(fullW * RESOLUTION_SCALE));
            int targetH = Math.max(1, (int) Math.round(fullH * RESOLUTION_SCALE));

            // Create a custom framebuffer at scaled resolution
            framebuffer = new Framebuffer(targetW, targetH, true);
            framebuffer.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
            framebuffer.bindFramebuffer(true);

            // Hide GUI for clean screenshot
            mc.gameSettings.hideGUI = true;

            // Set the camera view - this will trigger the CameraSetup event
            mc.setRenderViewEntity(cameraView);

            // Force a render update to ensure the camera is properly positioned and render into the framebuffer
            mc.entityRenderer.updateCameraAndRender(mc.getRenderPartialTicks(), System.nanoTime());

            // --- MAIN THREAD: read pixels into an int[] quickly (glReadPixels is required on main thread)
            // IMPORTANT: Ensure we're reading from our custom framebuffer, not the main one
            framebuffer.bindFramebuffer(true); // <-- CRITICAL FIX

            int width = framebuffer.framebufferWidth;
            int height = framebuffer.framebufferHeight;
            IntBuffer buf = BufferUtils.createIntBuffer(width * height);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            // Read RGBA (matching standard framebuffer order)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

            int[] pixels = new int[width * height];
            buf.get(pixels);

            // We no longer need the GL framebuffer resource: delete it now (main thread)
            framebuffer.deleteFramebuffer();
            framebuffer = null;

            // Restore view entity and GUI immediately
            mc.setRenderViewEntity(originalViewEntity);
            mc.gameSettings.hideGUI = originalHideGUI;
            cameraView = null;

            // Hand off actual encoding + disk IO to background thread.
            // Copy the pixels array (it's already a plain int[] so it's safe to pass)
            final int w = width;
            final int h = height;
            final int[] pixelsToSave = pixels;
            final File ssdir = new File(mc.gameDir, "screenshots");
            if (!ssdir.exists()) ssdir.mkdirs();
            final String filename = "camera_" + System.currentTimeMillis() + ".png";
            final File outFile = new File(ssdir, filename);

            saveExecutor.submit(() -> {
                try {
                    for (int i = 0; i < pixelsToSave.length; ++i) {
                        int bgra = pixelsToSave[i];
                        int r = (bgra >> 16) & 0xFF;  // Red
                        int g = (bgra >> 8) & 0xFF;   // Green
                        int b = bgra & 0xFF;           // Blue
                        int a = (bgra >> 24) & 0xFF;   // Alpha

                        // Recombine as ARGB
                        pixelsToSave[i] = (a << 24) | (b << 16) | (g << 8) | r;
                    }

                    // Flip rows because glReadPixels origin is bottom-left
                    int[] flipped = new int[w * h];
                    for (int y = 0; y < h; ++y) {
                        System.arraycopy(pixelsToSave, (h - y - 1) * w, flipped, y * w, w);
                    }

                    // Create BufferedImage and write
                    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, w, h, flipped, 0, w);
                    ImageIO.write(image, "png", outFile);

                    // Notify player on the main thread
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().player.sendMessage(new TextComponentString("Saved security camera screenshot: " + filename))
                    );
                } catch (Exception e) {
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().player.sendMessage(new TextComponentString("Failed to save camera screenshot: " + e.getMessage()))
                    );
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            // If anything went wrong, try to restore things
            try {
                mc.setRenderViewEntity(originalViewEntity);
                mc.gameSettings.hideGUI = originalHideGUI;
                cameraView = null;
                if (framebuffer != null) {
                    framebuffer.deleteFramebuffer();
                }
                mc.getFramebuffer().bindFramebuffer(true);
            } catch (Throwable t) { /* ignore */ }

            mc.player.sendMessage(new TextComponentString("Failed to take screenshot: " + e.getMessage()));
            e.printStackTrace();
        } finally {
            // Ensure main FB is bound (we already deleted our custom FB or tried to)
            try {
                mc.getFramebuffer().bindFramebuffer(true);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        return facing.getIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        EnumFacing facing = EnumFacing.byIndex(meta);
        return this.getDefaultState().withProperty(FACING, facing);
    }
}
