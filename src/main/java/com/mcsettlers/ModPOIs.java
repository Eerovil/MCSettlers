package com.mcsettlers;

import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.util.Identifier;
import net.minecraft.world.poi.PointOfInterestType;

public class ModPOIs {
	public static PointOfInterestType AXE_WORKSTATION_POI;

	public static void register() {
		AXE_WORKSTATION_POI = PointOfInterestHelper.register(
			Identifier.of("mcsettlers:axe_workstation_poi"),
			1, 1,
			ModBlocks.AXE_WORKSTATION
		);
	}
}
