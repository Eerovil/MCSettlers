package com.mcsettlers;

import java.util.List;
import java.util.Optional;

import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ModMemoryModules {
    public static final MemoryModuleType<String> JOB_STATUS =
        new MemoryModuleType<>(Optional.empty()); // no codec needed

    public static final MemoryModuleType<BlockPos> TARGET_BREAK_BLOCK =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<Float> BREAK_PROGRESS =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<Long> NO_WORK_UNTIL_TICK =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<List<BlockPos>> PILLAR_BLOCKS =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<Boolean> KEEP_PILLARING =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<BlockPos> DEPOSIT_CHEST =
        new MemoryModuleType<>(Optional.empty());

    public static final MemoryModuleType<Long> PAUSE_EVERYTHING_UNTIL =
        new MemoryModuleType<>(Optional.empty());

    public static void register() {
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "job_status"),
            JOB_STATUS);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "target_break_block"),
            TARGET_BREAK_BLOCK);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "break_progress"),
            BREAK_PROGRESS);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "no_work_until_tick"),
            NO_WORK_UNTIL_TICK);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "pillar_blocks"),
            PILLAR_BLOCKS);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "keep_pillaring"),
            KEEP_PILLARING);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "deposit_chest"),
            DEPOSIT_CHEST);
        Registry.register(Registries.MEMORY_MODULE_TYPE,
            Identifier.of("mcsettlers", "pause_everything_until"),
            PAUSE_EVERYTHING_UNTIL);
    }
}
