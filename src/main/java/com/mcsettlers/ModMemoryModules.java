package com.mcsettlers;

import java.util.Optional;

import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ModMemoryModules {
    public static final MemoryModuleType<BlockPos> TARGET_LOG =
        new MemoryModuleType<>(Optional.empty()); // no codec needed

    public static void register() {
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "target_log"),
            TARGET_LOG);
    }
}
