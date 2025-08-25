package com.h1ggsk.minewatch.blocks.lore;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

public class ASMMEntity extends Entity {
    public ASMMEntity(World worldIn) {
        super(worldIn);
        this.noClip = true;
        this.setSize(0.1F, 0.1F);
        this.ignoreFrustumCheck = true;
    }

    @Override
    protected void entityInit() {
    }

    @Override
    public void onUpdate() {
        this.motionX = 0;
        this.motionY = 0;
        this.motionZ = 0;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return false;
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
    }
}