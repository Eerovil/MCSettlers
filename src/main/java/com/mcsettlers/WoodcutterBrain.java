package com.mcsettlers;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
public class WoodcutterBrain {
    private static int tickCounter = 0;

    public static void tick(VillagerEntity villager, ServerWorld world) {
        Brain<?> brain = villager.getBrain();

        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
            .map(GlobalPos::pos)
            .orElse(null);
        if (workstation == null) {
            System.out.println("[WoodcutterBrain] No workstation found for villager " + villager.getUuid());
            return;
        }

        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_LOG);
        BlockPos targetLog = optionalTarget != null ? optionalTarget.orElse(null) : null;

        // Log the current state of the TARGET_LOG memory
        if (optionalTarget == null) {
            System.out.println("[WoodcutterBrain] TARGET_LOG memory: not present for villager " + villager.getUuid());
        } else if (targetLog == null) {
            System.out.println("[WoodcutterBrain] TARGET_LOG memory: present but empty for villager " + villager.getUuid());
        } else {
            System.out.println("[WoodcutterBrain] TARGET_LOG memory: present and set to " + targetLog + " for villager " + villager.getUuid());
        }

        boolean needsNewTarget = targetLog == null || !world.getBlockState(targetLog).isIn(BlockTags.LOGS);
        // Only search for new logs every 10 ticks
        if (needsNewTarget) {
            if (tickCounter % 10 == 0) {
                System.out.println("[WoodcutterBrain] Searching for new log for villager " + villager.getUuid());
                targetLog = findNearbyLog(world, workstation, 10);
                if (targetLog != null) {
                    System.out.println("[WoodcutterBrain] Found log at " + targetLog + " for villager " + villager.getUuid());
                    brain.remember(ModMemoryModules.TARGET_LOG, targetLog);
                } else {
                    System.out.println("[WoodcutterBrain] No logs found near workstation for villager " + villager.getUuid());
                    brain.forget(ModMemoryModules.TARGET_LOG);
                    tickCounter++;
                    return;
                }
            } else {
                tickCounter++;
                return;
            }
        }

        tickCounter++;

        if (targetLog != null) {
            if (villager.squaredDistanceTo(Vec3d.ofCenter(targetLog)) > 2) {
                System.out.println("[WoodcutterBrain] Villager " + villager.getUuid() + " moving to log at " + targetLog);
                villager.getNavigation().startMovingTo(targetLog.getX(), targetLog.getY(), targetLog.getZ(), 1.0);
            } else {
                System.out.println("[WoodcutterBrain] Villager " + villager.getUuid() + " chopping log at " + targetLog);
                world.breakBlock(targetLog, true, villager);
                brain.forget(ModMemoryModules.TARGET_LOG); // clear after chopping
            }
        }
    }

    private static BlockPos findNearbyLog(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isIn(BlockTags.LOGS)) {
                        return mutable.toImmutable();
                    }
                }
            }
        }
        return null;
    }
}
