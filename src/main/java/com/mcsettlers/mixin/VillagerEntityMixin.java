// Example: src/main/java/com/mcsettlers/mixin/VillagerEntityMixin.java
package com.mcsettlers.mixin;

import com.mcsettlers.ModMemoryModules;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        modules.add(ModMemoryModules.DEPOSIT_CHEST);
        return modules;
    }

    @Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
    private void onSetVillagerData(VillagerData data, CallbackInfo ci) {
        if (data.profession() == VillagerProfession.FLETCHER) {
            int rawId = Registries.VILLAGER_PROFESSION.getRawId(data.profession().value());
            RegistryEntry<VillagerProfession> profession = Registries.VILLAGER_PROFESSION.getEntry(rawId).orElse(null);
            ((VillagerEntity)(Object)this).setVillagerData(new VillagerData(
                data.type(),
                profession,
                data.level()
            ));
            ci.cancel();
        }
    }
}