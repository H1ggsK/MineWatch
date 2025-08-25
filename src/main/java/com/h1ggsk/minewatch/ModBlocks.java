package com.h1ggsk.minewatch;

import com.h1ggsk.minewatch.blocks.BlockCamera;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = MineWatch.MODID)
public class ModBlocks {
    public static final BlockCamera CAMERA_BLOCK = new BlockCamera();

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(CAMERA_BLOCK);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new ItemBlock(CAMERA_BLOCK).setRegistryName(CAMERA_BLOCK.getRegistryName()));
    }
}
