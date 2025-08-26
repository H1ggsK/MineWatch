package com.h1ggsk.minewatch.blocks;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private static CameraEntity cameraView = null;

    // Track all camera blocks and their update timers
    private static final Map<BlockPos, Integer> cameraTimers = new ConcurrentHashMap<>();
    private static final Map<BlockPos, IBlockState> cameraStates = new ConcurrentHashMap<>();
    private static final int CAPTURE_INTERVAL = 5; // 5 ticks = 4 times/second

    // Executor for background saving
    private static final ExecutorService saveExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "camera-screenshot-saver");
        t.setDaemon(true);
        return t;
    });

    // Base directory for saving images
    private static final File WEB_DIR = new File("C:/xampp/htdocs/minewatch");

    // Resolution scale for screenshots
    private static final double RESOLUTION_SCALE = 1.0; // Reduced for performance

    // Reflection field for accessing loaded chunks
    private static Field loadedChunksField = null;

    static {
        try {
            loadedChunksField = ChunkProviderClient.class.getDeclaredField("loadedChunks");
            loadedChunksField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

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
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) return;
        cameraTimers.put(pos, 0);
        cameraStates.put(pos, state);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) return;
        cameraTimers.remove(pos);
        cameraStates.remove(pos);
        super.breakBlock(world, pos, state);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            // Manual capture
            scheduleScreenshot(pos, state.getValue(FACING));
        }
        return true;
    }

    private static void scheduleScreenshot(BlockPos pos, EnumFacing facing) {
        screenshotScheduled = true;
        cameraPos = pos;
        cameraFacing = facing;
        calculateCameraRotation();
    }

    private static Long2ObjectMap<Chunk> getLoadedChunks(World world) {
        if (world.isRemote && world.getChunkProvider() instanceof ChunkProviderClient) {
            ChunkProviderClient chunkProvider = (ChunkProviderClient) world.getChunkProvider();
            try {
                return (Long2ObjectMap<Chunk>) loadedChunksField.get(chunkProvider);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private static List<Chunk> getLoadedChunksList(World world) {
        List<Chunk> loadedChunks = new ArrayList<>();
        Long2ObjectMap<Chunk> map = getLoadedChunks(world);

        if (map != null) {
            for (Chunk chunk : map.values()) {
                if (chunk != null && chunk.isLoaded()) {
                    loadedChunks.add(chunk);
                }
            }
        }

        return loadedChunks;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;

        if (world == null) return;

        // Scan for camera blocks in loaded chunks on client side
        if (world.getTotalWorldTime() % 20 == 0) { // Check every second to reduce load
            for (Chunk chunk : getLoadedChunksList(world)) {
                ChunkPos chunkPos = chunk.getPos();

                // Scan all block positions in the chunk for camera blocks
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < world.getHeight(); y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos pos = new BlockPos(chunkPos.x * 16 + x, y, chunkPos.z * 16 + z);
                            IBlockState state = chunk.getBlockState(x, y, z);

                            if (state.getBlock() instanceof BlockCamera) {
                                cameraTimers.putIfAbsent(pos, 0);
                                cameraStates.put(pos, state);
                            }
                        }
                    }
                }
            }
        }

        // Update timers and schedule captures for all known cameras
        for (Iterator<Map.Entry<BlockPos, Integer>> it = cameraTimers.entrySet().iterator(); it.hasNext();) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            int timer = entry.getValue() + 1;

            // Check if chunk is loaded and block still exists
            if (!world.isBlockLoaded(pos) || !(world.getBlockState(pos).getBlock() instanceof BlockCamera)) {
                it.remove();
                cameraStates.remove(pos);
                continue;
            }

            if (timer >= CAPTURE_INTERVAL) {
                IBlockState state = cameraStates.get(pos);
                if (state != null) {
                    scheduleScreenshot(pos, state.getValue(FACING));
                }
                timer = 0;
            }

            entry.setValue(timer);
        }

        // Process scheduled screenshot
        if (screenshotScheduled) {
            screenshotScheduled = false;
            takeScreenshotFromCameraPosition();
        }
    }

    private static void calculateCameraRotation() {
        switch (cameraFacing) {
            case NORTH:
                cameraYaw = 0f;
                cameraPitch = 0f;
                break;
            case SOUTH:
                cameraYaw = 180f;
                cameraPitch = 0f;
                break;
            case WEST:
                cameraYaw = 270f;
                cameraPitch = 0f;
                break;
            case EAST:
                cameraYaw = 90f;
                cameraPitch = 0f;
                break;
            case UP:
                cameraYaw = 0f;
                cameraPitch = -90f;
                break;
            case DOWN:
                cameraYaw = 0f;
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

    private static void takeScreenshotFromCameraPosition() {
        Minecraft mc = Minecraft.getMinecraft();

        // Store original settings
        Entity originalViewEntity = mc.getRenderViewEntity();
        boolean originalHideGUI = mc.gameSettings.hideGUI;
        Framebuffer framebuffer = null;

        try {
            // Create a temporary camera entity
            cameraView = new CameraEntity(mc.world);

            // Calculate camera position - center of the block
            double camX = cameraPos.getX() + 0.5;
            double camY = cameraPos.getY();
            double camZ = cameraPos.getZ() + 0.5;

            // Nudge camera slightly away from the block face in the correct direction
            double off = 0.1;
            switch (cameraFacing) {
                case NORTH: camZ -= off; break;
                case SOUTH: camZ += off; break;
                case WEST:  camX -= off; break;
                case EAST:  camX += off; break;
                case UP:    camY += off; break;
                case DOWN:  camY -= off; break;
            }

            // Position the camera
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

            // Force a render update
            mc.entityRenderer.updateCameraAndRender(mc.getRenderPartialTicks(), System.nanoTime());

            // Read pixels from framebuffer
            framebuffer.bindFramebuffer(true);
            int width = framebuffer.framebufferWidth;
            int height = framebuffer.framebufferHeight;
            IntBuffer buf = BufferUtils.createIntBuffer(width * height);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

            int[] pixels = new int[width * height];
            buf.get(pixels);

            // Clean up GL resources
            framebuffer.deleteFramebuffer();
            framebuffer = null;

            // Restore view entity and GUI
            mc.setRenderViewEntity(originalViewEntity);
            mc.gameSettings.hideGUI = originalHideGUI;
            cameraView = null;

            // Save the screenshot in background thread
            final int w = width;
            final int h = height;
            final int[] pixelsToSave = pixels;
            final String filename = System.currentTimeMillis() + ".png";

            // Generate folder structure based on camera position and facing
            final String folderPath = String.format("x%d_y%d_z%d_%s",
                    cameraPos.getX(), cameraPos.getY(), cameraPos.getZ(),
                    cameraFacing.getName().toLowerCase());
            final File cameraDir = new File(WEB_DIR, folderPath);
            if (!cameraDir.exists()) cameraDir.mkdirs();

            final File outFile = new File(cameraDir, "latest.png");
            final File timestampedFile = new File(cameraDir, filename);

            saveExecutor.submit(() -> {
                try {
                    // Convert BGRA to ARGB
                    for (int i = 0; i < pixelsToSave.length; ++i) {
                        int bgra = pixelsToSave[i];
                        int r = (bgra >> 16) & 0xFF;
                        int g = (bgra >> 8) & 0xFF;
                        int b = bgra & 0xFF;
                        int a = (bgra >> 24) & 0xFF;
                        pixelsToSave[i] = (a << 24) | (b << 16) | (g << 8) | r;
                    }

                    // Flip image vertically
                    int[] flipped = new int[w * h];
                    for (int y = 0; y < h; ++y) {
                        System.arraycopy(pixelsToSave, (h - y - 1) * w, flipped, y * w, w);
                    }

                    // Save image
                    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, w, h, flipped, 0, w);
                    ImageIO.write(image, "png", outFile);
                    ImageIO.write(image, "png", timestampedFile);

                    // Notify player
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().player.sendMessage(
                                    new TextComponentString("Camera saved: " + folderPath + "/" + filename))
                    );
                } catch (Exception e) {
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().player.sendMessage(new TextComponentString("Failed to save camera screenshot: " + e.getMessage()))
                    );
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            // Restore on error
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