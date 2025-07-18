package com.mcsettlers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mcsettlers.brains.WoodcutterBrain;

public class MCSettlers implements ModInitializer {
	public static final String MOD_ID = "mcsettlers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModPOIs.register();
		ModMemoryModules.register();
		ModProfessions.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerWorld world : server.getWorlds()) {
				// Use getEntitiesByType for all loaded villagers
				for (VillagerEntity villager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER,
						v -> true)) {
					VillagerData data = villager.getVillagerData();
					RegistryEntry<VillagerProfession> profession = data.profession();
					if (profession != null && profession.matchesKey(VillagerProfession.FLETCHER)) {
						VillagerProfession woodcutter = Registries.VILLAGER_PROFESSION.get(ModProfessions.WOODCUTTER);
						RegistryEntry<VillagerProfession> woodcutterEntry = null;
						if (woodcutter != null) {
							int rawId = Registries.VILLAGER_PROFESSION.getRawId(woodcutter);
							woodcutterEntry = Registries.VILLAGER_PROFESSION.getEntry(rawId).orElse(null);
						}
						if (woodcutterEntry != null) {
							villager.setVillagerData(new VillagerData(
									data.type(),
									woodcutterEntry,
									data.level()));
							LOGGER.info("Changed villager profession to WOODCUTTER: {}", villager.getUuid());
						}
					}
					if (profession != null && profession.matchesKey(ModProfessions.WOODCUTTER)) {
						WoodcutterBrain.tick(villager, world);
					}
				}
			}
		});

		ServerWorldEvents.LOAD.register((server, world) -> {
			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof VillagerEntity villager) {
					VillagerData data = villager.getVillagerData();
					if (data.profession() != null && data.profession().matchesKey(VillagerProfession.FLETCHER)) {
						VillagerProfession woodcutter = Registries.VILLAGER_PROFESSION.get(ModProfessions.WOODCUTTER);
						if (woodcutter != null) {
							int rawId = Registries.VILLAGER_PROFESSION.getRawId(woodcutter);
							RegistryEntry<VillagerProfession> woodcutterEntry = Registries.VILLAGER_PROFESSION.getEntry(rawId).orElse(null);
							if (woodcutterEntry != null) {
								villager.setVillagerData(new VillagerData(
										data.type(),
										woodcutterEntry,
										data.level()));
							}
						}
					}
				}
			}
		});

		LOGGER.info("MCSettlers initialized.");
	}
}
