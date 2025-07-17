// Example: src/main/java/com/mcsettlers/mixin/VillagerEntityMixin.java
package com.mcsettlers.mixin;

import com.mcsettlers.ModMemoryModules;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collection;
import java.util.HashSet;

@Mixin(VillagerEntity.class)
public class VillagerEntityMixin {
    @ModifyArg(method = "createBrainProfile", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ai/brain/Brain;createProfile(Ljava/util/Collection;Ljava/util/Collection;)Lnet/minecraft/entity/ai/brain/Brain$Profile;"), index = 0)
    private static Collection<MemoryModuleType<?>> settlers$addCustomMemory(Collection<MemoryModuleType<?>> original) {
        HashSet<MemoryModuleType<?>> modules = new HashSet<>(original);
        modules.add(ModMemoryModules.JOB_STATUS);
        modules.add(ModMemoryModules.TARGET_BREAK_BLOCK);
        modules.add(ModMemoryModules.BREAK_PROGRESS);
        modules.add(ModMemoryModules.NO_WORK_UNTIL_TICK);
        modules.add(ModMemoryModules.PILLAR_BLOCKS);
        modules.add(ModMemoryModules.KEEP_PILLARING);
        System.out.println("[VillagerEntityMixin] Added TARGET_LOG to villager brain profile");
        return modules;
    }
}