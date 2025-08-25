package com.h1ggsk.minewatch.blocks.lore;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

public class CameraEntity extends Entity {

    public CameraEntity(World worldIn) {
        super(worldIn);
        this.setSize(0.1F, 0.1F);
        this.noClip = true;
        this.ignoreFrustumCheck = true;
    }

    @Override
    public void onEntityUpdate() {
        // Don't do normal entity updates
    }

    @Override
    public void onUpdate() {
        // Don't do normal entity updates
        // But we need to update the position for rendering
        this.lastTickPosX = this.posX;
        this.lastTickPosY = this.posY;
        this.lastTickPosZ = this.posZ;
        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return false; // Don't render the entity itself
    }

    @Override
    protected void entityInit() {
        // No special initialization needed
    }

    @Override
    protected void readEntityFromNBT(net.minecraft.nbt.NBTTagCompound compound) {
        // Not needed for client-side only entity
    }

    @Override
    protected void writeEntityToNBT(net.minecraft.nbt.NBTTagCompound compound) {
        // Not needed for client-side only entity
    }
}