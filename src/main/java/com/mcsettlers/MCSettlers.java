package com.mcsettlers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCSettlers implements ModInitializer {
	public static final String MOD_ID = "mcsettlers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModBlocks.registerBlocks();
		ModPOIs.register();
		ModProfessions.register();

		ServerTickEvents.END_WORLD_TICK.register(world -> {
			BlockPos center = BlockPos.ORIGIN; // optionally use player pos, or iterate all villages
			Box area = new Box(center).expand(128); // set radius appropriately

			for (VillagerEntity villager : world.getEntitiesByClass(
					VillagerEntity.class,
					area,
					v -> v.getVillagerData().profession().value() == ModProfessions.WOODCUTTER)) {
				WoodcutterBrain.tick(villager, world);
			}
		});

		LOGGER.info("MCSettlers initialized.");
	}
}
