package com.mcsettlers.utils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.brains.WorkerBrain;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;

public class SharedMemories {
    // This class contains shared "memories". Stuff that other villagers must know about.
    // For example, reserved deposit chests, reserved items to move around, etc.
    Set<DepositChestValues> depositChestValuesList = new HashSet<>();
    Map<GlobalPos, VillagerEntity> targetedBlocks = new HashMap<>();
    Map<GlobalPos, VillagerEntity> depositChests = new HashMap<>();

    public boolean reservableMapValueIsAvailable(Map<GlobalPos, VillagerEntity> reservableMap, ServerWorld world, BlockPos pos) {
        // Check if the deposit chest is available in the world
        GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), pos);
        VillagerEntity villager = reservableMap.get(globalPos);
        if (villager == null) {
            // If no villager is using this chest, it's available
            return true;
        }
        // Check if the villager is still in the world
        if (!villager.isAlive()) {
            // remove the villager from the map if they are not alive
            reservableMap.remove(globalPos);
            return true;
        }
        return false; // Chest is reserved by another villager
    }

    public boolean reserveReservableMapValue(Map<GlobalPos, VillagerEntity> reservableMap, ServerWorld world, VillagerEntity villager, BlockPos pos) {
        // Reserve a deposit chest for the villager
        if (reservableMapValueIsAvailable(reservableMap, world, pos)) {
            GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), pos);
            // Unreserve any existing deposit chest for this villager
            reservableMap.values().removeIf(v -> v.equals(villager));
            // Reserve the new deposit chest
            reservableMap.put(globalPos, villager);
            return true;
        }
        return false;
    }

    public void unReserveReservableMapValue(Map<GlobalPos, VillagerEntity> reservableMap, ServerWorld world, BlockPos pos) {
        // Unreserve a deposit chest
        GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), pos);
        reservableMap.remove(globalPos);
    }

    public BlockPos getReservableMapValue(Map<GlobalPos, VillagerEntity> reservableMap, ServerWorld world, VillagerEntity villager) {
        // Get the reserved deposit chest for the villager
        for (Map.Entry<GlobalPos, VillagerEntity> entry : reservableMap.entrySet()) {
            GlobalPos globalPos = entry.getKey();
            VillagerEntity targetVillager = entry.getValue();
            if (targetVillager.equals(villager) && targetVillager.isAlive() && globalPos.dimension().equals(world.getRegistryKey())) {
                return globalPos.pos();
            }
        }
        return null; // No deposit chest reserved
    }

    public boolean depositChestIsAvailable(ServerWorld world, BlockPos pos) {
        return reservableMapValueIsAvailable(depositChests, world, pos);
    }

    public boolean reserveDepositChest(ServerWorld world, VillagerEntity villager, BlockPos pos) {
        return reserveReservableMapValue(depositChests, world, villager, pos);
    }

    public void unReserveDepositChest(ServerWorld world, BlockPos pos) {
        // Unreserve a target block
        GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), pos);
        depositChests.remove(globalPos);
    }

    public BlockPos getDepositChest(ServerWorld world, VillagerEntity villager, BlockPos workstation) {
        return getReservableMapValue(depositChests, world, villager);
    }

    public boolean targetBlockIsAvailable(ServerWorld world, BlockPos pos) {
        return reservableMapValueIsAvailable(targetedBlocks, world, pos);
    }

    public boolean reserveTargetBlock(ServerWorld world, VillagerEntity villager, BlockPos pos) {
        return reserveReservableMapValue(targetedBlocks, world, villager, pos);
    }

    public void unReserveTargetBlock(ServerWorld world, BlockPos pos) {
        // Unreserve a target block
        GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), pos);
        targetedBlocks.remove(globalPos);
    }

    public BlockPos getTargetBlock(ServerWorld world, VillagerEntity villager) {
        return getReservableMapValue(targetedBlocks, world, villager);
    }

    private Map<BlockPos, VillagerEntity> depositChestsInWorld(ServerWorld world) {
        Map<BlockPos, VillagerEntity> depositChestsMap = new HashMap<>();
        for (Map.Entry<GlobalPos, VillagerEntity> entry : depositChests.entrySet()) {
            GlobalPos chestPos = entry.getKey();
            VillagerEntity villager = entry.getValue();
            if (chestPos.dimension().equals(world.getRegistryKey())) {
                BlockPos pos = chestPos.pos();
                depositChestsMap.put(pos, villager);
            }
        }
        return depositChestsMap;
    }

    public void refreshDepositChestValues(MinecraftServer server, SharedMemories sharedMemories) {
        // This method should refresh the deposit chest values from the world
        // For now, it's a placeholder
        // In a real implementation, you would query the world for all deposit chests and their contents
        depositChestValuesList.clear();

        for (ServerWorld world : server.getWorlds()) {
            // Get a list of all deposit chests for all villagers
            for (Map.Entry<BlockPos, VillagerEntity> entry : depositChestsInWorld(world).entrySet()) {
                BlockPos chestPos = entry.getKey();
                VillagerEntity otherVillager = entry.getValue();
                if (otherVillager == null || !otherVillager.isAlive()) {
                    continue; // Skip if the villager is not alive
                }

                DepositChestValues depositChestValues = new DepositChestValues();
                depositChestValues.dimension = world.getRegistryKey();
                depositChestValues.pos = chestPos;
                WorkerBrain workerBrain = MCSettlers.getBrainFor(otherVillager.getVillagerData().profession());
                depositChestValues.wantedItems = new HashSet<>();
                depositChestValues.containedItems = new HashSet<>();
                Set<Item> otherVillagerWantedItems = workerBrain.getWantedItems(
                    world, otherVillager, sharedMemories
                ); // Get wanted items from the worker brain

                BlockEntity chest = world.getBlockEntity(depositChestValues.pos);
                if (chest instanceof ChestBlockEntity) {
                    ChestBlockEntity chestEntity = (ChestBlockEntity) chest;
                    for (int i = 0; i < chestEntity.size(); i++) {
                        ItemStack stack = chestEntity.getStack(i);
                        if (stack.isEmpty()) {
                            // Add all items in WANTED_ITEMS to wantedItems, since there is emty slot
                            for (Item wantedItem : otherVillagerWantedItems) {
                                depositChestValues.wantedItems.add(wantedItem);
                            }
                            continue; // Skip empty slots
                        }

                        Item item = stack.getItem();
                        depositChestValues.containedItems.add(item);

                        // Check if the item is wanted by the worker brain
                        for (Item wantedItem : otherVillagerWantedItems) {
                            if (stack.getItem() == wantedItem) {
                                depositChestValues.wantedItemsCount += stack.getCount();
                                // If stack is not full, add to wanted items
                                if (stack.getCount() < stack.getMaxCount()) {
                                    depositChestValues.wantedItems.add(item);
                                }
                                break;
                            }
                        }
                    }
                } else {
                    MCSettlers.LOGGER.warn("Deposit chest at {} is not a ChestBlockEntity", depositChestValues.pos);
                    unReserveDepositChest(world, chestPos);
                    continue;
                }
                // Delete all wanted items from contained items
                depositChestValues.containedItems.removeAll(depositChestValues.wantedItems);
                depositChestValuesList.add(depositChestValues);
            }
        }

    }

    public PriorityQueue<DepositChestValues> getDepositChestValuesNear(ServerWorld world, BlockPos pos, Comparator<DepositChestValues> sortFunc) {
        PriorityQueue<DepositChestValues> queue = new PriorityQueue<>(sortFunc);
        for (DepositChestValues depositChestValues : depositChestValuesList) {
            queue.add(depositChestValues);
        }
        return queue;
    }

}
