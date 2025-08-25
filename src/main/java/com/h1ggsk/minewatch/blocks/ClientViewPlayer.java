package com.h1ggsk.minewatch.blocks;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.world.World;

import java.util.UUID;

public class ClientViewPlayer extends AbstractClientPlayer {

	public ClientViewPlayer(World world) {
		super(world, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]"));
	}

	@Override
	public void onUpdate() {
		return;
	}

	@Override
	public void onLivingUpdate() {
		return;
	}

	@Override
	public void onEntityUpdate() {
		return;
	}

	@Override
	public boolean isCreative() {
		return false;
	}

	@Override
	public boolean isSpectator() {
		return false;
	}

}