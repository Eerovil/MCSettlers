package com.mcsettlers;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.AbstractBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {
    public static final Block AXE_WORKSTATION = new AxeWorkstationBlock(
        AbstractBlock.Settings.copy(Blocks.CRAFTING_TABLE)
    );

    public static void registerBlocks() {
        Registry.register(Registries.BLOCK, new Identifier("mcsettlers", "axe_workstation"), AXE_WORKSTATION);
    }
}
