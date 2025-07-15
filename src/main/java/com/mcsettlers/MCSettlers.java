package com.mcsettlers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCSettlers implements ModInitializer {
	public static final String MOD_ID = "mcsettlers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModPOIs.register();
		ModProfessions.register();
		ModMemoryModules.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerWorld world : server.getWorlds()) {
				// Use getEntitiesByType for all loaded villagers
				for (VillagerEntity villager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER, v -> true)) {
					if (villager.getVillagerData().profession().matchesKey(VillagerProfession.FLETCHER)) {
						WoodcutterBrain.tick(villager, world);
					}
				}
			}
		});

		LOGGER.info("MCSettlers initialized.");
	}
}
