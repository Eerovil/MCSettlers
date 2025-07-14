package com.mcsettlers;

import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.util.Identifier;
import net.minecraft.world.poi.PointOfInterestType;

public class ModPOIs {
    public static final PointOfInterestType AXE_WORKSTATION_POI = PointOfInterestHelper.register(
        Identifier.of("mcsettlers", "axe_workstation"),
        1, // ticket count
        1, // search distance
        ModBlocks.AXE_WORKSTATION
    );

    public static void register() {
        // Called from mod init to ensure class loading
    }
}
