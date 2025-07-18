package com.mcsettlers.brains;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.ChestAnimationHelper;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

class DepositChestValues {
    public BlockPos pos;
    public Set<Item> wantedItems;
    public Set<Item> containedItems;
}

public class CarrierBrain extends WorkerBrain {
    // Carrier carries items from chest to another
    
    @Override
    protected void handleJob(
        VillagerEntity villager, ServerWorld world, Brain<?> brain,
        String jobStatus, BlockPos workstation, BlockPos targetBlock, BlockPos walkTarget) {

        if (jobStatus == "walking_to_pick_up") {
            if (walkTarget != null) {
                return; // Already walking, nothing to do
            }
            startPickingUpItem(villager, world, brain, targetBlock);
        } else if (jobStatus == "stop_picking_up_item") {
            stopPickingUpItem(villager, world, brain, targetBlock);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, brain, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, brain, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(brain, villager, "no_work");
            }
        } else if (jobStatus == "no_work") {
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                setJobStatus(brain, villager, "idle");
            }
        } else if (jobStatus == "idle") {
            // If inventory is empty, deposit items
            findNewCarryJob(villager, world, brain);
        }
    }

    @Override
    protected Optional<BlockPos> findDepositChest(ServerWorld world, BlockPos workstation) {
        return Optional.empty();
    }

    protected void findNewCarryJob(
        VillagerEntity villager, ServerWorld world, Brain<?> brain) {
        // Logic to find a new carry job, e.g., looking for items to pick up or chests to deposit into
        // This is a placeholder; actual implementation would depend on game logic
        setJobStatus(brain, villager, "deposit_items");
        // Deposit chest is where the villager will deposit items
        // Target break block is the chest to get item from

        BlockPos villagerPos = villager.getBlockPos();

        PriorityQueue<DepositChestValues> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.pos.getSquaredDistance(villagerPos)));

        // Get a list of all deposit chests for all villagers
        for (VillagerEntity otherVillager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER, v -> true)) {
            Brain<?> otherVillagerBrain = otherVillager.getBrain();
            Optional<BlockPos> depositChest = otherVillagerBrain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
            if (depositChest.isPresent()) {
                DepositChestValues depositChestValues = new DepositChestValues();
                depositChestValues.pos = depositChest.get();
                WorkerBrain workerBrain = MCSettlers.getBrainFor(otherVillager.getVillagerData().profession());
                depositChestValues.wantedItems = new HashSet<>();
                depositChestValues.containedItems = new HashSet<>();

                BlockEntity chest = world.getBlockEntity(depositChestValues.pos);
                if (chest instanceof ChestBlockEntity) {
                    ChestBlockEntity chestEntity = (ChestBlockEntity) chest;
                    for (int i = 0; i < chestEntity.size(); i++) {
                        ItemStack stack = chestEntity.getStack(i);
                        if (stack.isEmpty()) {
                            // Add all items in WANTED_ITEMS to wantedItems, since there is emty slot
                            for (TagKey<Item> wantedTag : workerBrain.WANTED_ITEMS) {
                                for (RegistryEntry<Item> wantedItem : Registries.ITEM.iterateEntries(wantedTag)) {
                                    depositChestValues.wantedItems.add(wantedItem.value());
                                }
                            }
                            continue; // Skip empty slots
                        }

                        Item item = stack.getItem();
                        depositChestValues.containedItems.add(item);

                        // Check if the item is wanted by the worker brain
                        for (TagKey<Item> wantedTag : workerBrain.WANTED_ITEMS) {
                            if (stack.isIn(wantedTag)) {
                                // If stack is not full, add to wanted items
                                if (stack.getCount() < stack.getMaxCount()) {
                                    depositChestValues.wantedItems.add(item);
                                }
                                break;
                            }
                        }
                    }
                }
                queue.add(depositChestValues);
            }
        }

        MCSettlers.LOGGER.info("Found {} deposit chests for villager: {}", queue.size(), villager.getUuid());
        // Print values for each deposit chest
        for (DepositChestValues chestValuesFrom : queue) {
            for (DepositChestValues chestValuesTo : queue) {
                // If same chest, skip
                if (chestValuesFrom.pos.equals(chestValuesTo.pos)) {
                    continue;
                }
                // If chest from has items that the chest to wants, set it as target break block
                if (!chestValuesFrom.containedItems.isEmpty() &&
                    !chestValuesTo.wantedItems.isEmpty()) {
                        Item itemToCarry = null;
                        for (Item item : chestValuesFrom.containedItems) {
                            if (chestValuesTo.wantedItems.contains(item)) {
                                itemToCarry = item;
                                MCSettlers.LOGGER.info("Found item to carry: {} for villager: {}", itemToCarry, villager.getUuid());
                                break; // Found an item to carry, no need to check further
                            }
                        }
                        // If no item to carry found, continue
                        if (itemToCarry == null) {
                            MCSettlers.LOGGER.info("No item to carry found for villager: {}", villager.getUuid());
                            continue;
                        }
                    MCSettlers.LOGGER.info("Found suitable deposit chest for villager: {} -> {}", villager.getUuid(), chestValuesTo.pos);
                    brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, chestValuesFrom.pos);
                    brain.remember(ModMemoryModules.DEPOSIT_CHEST, chestValuesTo.pos);
                    brain.remember(ModMemoryModules.ITEM_TO_CARRY, itemToCarry);
                    // Set walk target with reasonable completion range and duration
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(chestValuesFrom.pos),
                                    0.6F,
                                    1 // completion range
                            ));
                    setJobStatus(brain, villager, "walking_to_pick_up");
                    return;
                }
            }
        }
    }

    protected void startPickingUpItem(
        VillagerEntity villager, ServerWorld world, Brain<?> brain,
        BlockPos targetBlock) {
            BlockEntity blockEntity = world.getBlockEntity(targetBlock);
            if (!(blockEntity instanceof ChestBlockEntity)) {
                MCSettlers.LOGGER.warn("Target block is not a chest: {}", targetBlock);
                return;
            }
            ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(targetBlock);
            if (chest == null || chest.isEmpty()) {
                MCSettlers.LOGGER.warn("Chest at {} is empty or does not exist.", targetBlock);
                return;
            }

            // Find itemstack to carry
            Item itemToCarry = brain.getOptionalMemory(ModMemoryModules.ITEM_TO_CARRY)
                .orElse(null);
            if (itemToCarry == null) {
                MCSettlers.LOGGER.warn("No item to carry found for villager: {}", villager.getUuid());
                return;
            }

            // Check if the chest contains the item to carry
            lookAtBlock(villager, targetBlock);
            ChestAnimationHelper.animateChest(world, targetBlock, true);

            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.getStack(i);
                if (stack.getItem() == itemToCarry && stack.getCount() > 0) {
                    // Add one item to villager's inventory
                    ItemStack carriedStack = stack.copy();
                    carriedStack.setCount(1); // Take only one item
                    villager.getInventory().addStack(carriedStack);
                    // Show item in hand
                    villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, carriedStack);
                    chest.removeStack(i, 1); // Remove one item from chest
                    break;
                }
            }

            setJobStatus(brain, villager, "stop_picking_up_item");
            pauseForMS(world, brain, 1000);
    }

    protected void stopPickingUpItem(
        VillagerEntity villager, ServerWorld world, Brain<?> brain,
        BlockPos targetBlock) {
            // Logic to stop picking up item, e.g., resetting hand and chest animation
            ChestAnimationHelper.animateChest(world, targetBlock, false);
            setJobStatus(brain, villager, "deposit_items");
            // Set walk target to deposit chest
            BlockPos depositChest = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST)
                .orElse(null);
            if (depositChest != null) {
                brain.remember(MemoryModuleType.WALK_TARGET,
                        new net.minecraft.entity.ai.brain.WalkTarget(
                                new net.minecraft.entity.ai.brain.BlockPosLookTarget(depositChest),
                                0.6F,
                                1 // completion range
                        ));
            } else {
                MCSettlers.LOGGER.warn("No deposit chest found for villager: {}", villager.getUuid());
            }
    }
}
