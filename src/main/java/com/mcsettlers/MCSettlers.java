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
import net.minecraft.registry.RegistryKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mcsettlers.brains.ForesterBrain;
import com.mcsettlers.brains.WoodcutterBrain;
import com.mcsettlers.brains.WorkerBrain;
import com.mcsettlers.brains.CarrierBrain;

public class MCSettlers implements ModInitializer {
	public static final String MOD_ID = "mcsettlers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final WorkerBrain woodcutterBrain = new WoodcutterBrain();
	private static final WorkerBrain foresterBrain = new ForesterBrain();
	private static final WorkerBrain carrierBrain = new CarrierBrain();

	public static String workerToString(VillagerEntity villager) {
		VillagerData data = villager.getVillagerData();
		RegistryEntry<VillagerProfession> profession = data.profession();
		String professionName;
		if (profession != null) {
			professionName = profession.getKey().map(RegistryKey::getValue).map(Object::toString).orElse("unknown");
		} else {
			professionName = "no_profession";
		}
		String shortUuid = villager.getUuidAsString().toString().substring(0, 8);
		return professionName + " (" + shortUuid + ")";
	}

	public static WorkerBrain getBrainFor(RegistryEntry<VillagerProfession> profession) {
		if (profession.matchesKey(ModProfessions.WOODCUTTER)) {
			return woodcutterBrain;
		} else if (profession.matchesKey(ModProfessions.FORESTER)) {
			return foresterBrain;
		}
		return carrierBrain;
	}

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

					if (profession != null) {
						RegistryKey<VillagerProfession> vanillaKey = profession.getKey().orElse(null);
						RegistryKey<VillagerProfession> customKey = ModProfessions.VANILLA_TO_CUSTOM_PROFESSION_MAP
								.get(vanillaKey);
						if (customKey != null) {
							VillagerProfession customProfession = Registries.VILLAGER_PROFESSION.get(customKey);
							if (customProfession != null) {
								int rawId = Registries.VILLAGER_PROFESSION.getRawId(customProfession);
								RegistryEntry<VillagerProfession> customEntry = Registries.VILLAGER_PROFESSION
										.getEntry(rawId).orElse(null);
								if (customEntry != null) {
									villager.setVillagerData(new VillagerData(
											data.type(),
											customEntry,
											data.level()));
									LOGGER.info("Changed villager profession to custom: {} -> {}", MCSettlers.workerToString(villager),
											customKey.getValue());
								}
							}
						}
					}
					WorkerBrain brain = getBrainFor(profession);
					if (brain != null) {
						brain.tick(villager, world); // Calls the appropriate brain's tick method
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
							RegistryEntry<VillagerProfession> woodcutterEntry = Registries.VILLAGER_PROFESSION
									.getEntry(rawId).orElse(null);
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
