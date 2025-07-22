package com.mcsettlers.brains;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.AvailableRecipe;
import com.mcsettlers.utils.SharedMemories;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;


public class CrafterBrain extends WorkerBrain {
    // Crafter crafts items from resources

    @Override
    protected void handleJob(
        VillagerEntity villager, ServerWorld world,
        String jobStatus, BlockPos workstation, BlockPos targetBlock, SharedMemories sharedMemories) {

        Brain<?> brain = villager.getBrain();

        if (jobStatus.equals("refresh_crafting_recipe")) {
            refreshCraftingRecipes(villager, world, workstation);
            startDepositingItems(villager, world, workstation, sharedMemories);
        } else if (jobStatus.equals("deposit_items")) {
            keepDepositingItems(villager, world, workstation, sharedMemories);
        } else if (jobStatus.equals("stop_deposit_items")) {
            stopDepositingItems(villager, world, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(villager, "no_work_after_deposit");
            } else {
                // If inventory is not empty, we can craft
                craftItem(villager, world, workstation, getItemToCraft(villager, world, workstation));
                setJobStatus(villager, "crafting");
                pauseForMS(villager, world, 5000);
                startDepositingItems(villager, world, workstation, sharedMemories);
            }
        } else if (jobStatus.startsWith("no_work")) {
            startHoldingItem(villager, ItemStack.EMPTY);
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                setJobStatus(villager, "refresh_crafting_recipe");
            }
        } else {
            MCSettlers.LOGGER.warn("CrafterBrain: Unknown job status: " + jobStatus + " for villager: " + MCSettlers.workerToString(villager));
            setJobStatus(villager, "no_work");
        }
    }

    @Override
    protected void getBestToolFromChest(
        ServerWorld world,
            ChestBlockEntity chest, VillagerEntity villager, BlockPos workstation) {
        // Instead of getting a tool, we want to get the ingredients for crafting
        Brain<?> brain = villager.getBrain();
        List<Item> itemsInChest = new ArrayList<>(0);
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            for (int j = 0; j < stack.getCount(); j++) {
                itemsInChest.add(stack.getItem());
            }
        }
        brain.forget(ModMemoryModules.SELECTED_RECIPE);

        Set<AvailableRecipe> availableRecipes = brain.getOptionalMemory(ModMemoryModules.AVAILABLE_RECIPES).orElse(new HashSet<>());

        for (AvailableRecipe availableRecipe : availableRecipes) {
            List<Item> neededItems = availableRecipe.itemsMatchRecipe(itemsInChest);
            if (neededItems == null) {
                // If we don't have the needed items, we can't craft this recipe
                MCSettlers.LOGGER.info("CrafterBrain: No needed items for recipe: " + availableRecipe);
                continue;
            }
            // We have the needed items, so we can craft this recipe
            // Let's pick up all items in neededItems
            brain.remember(ModMemoryModules.SELECTED_RECIPE, availableRecipe);
            for (Item item : neededItems) {
                takeItemFromChest(chest, villager, item);
            }
        }
    }

    protected boolean craftItem(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation, Item itemToCraft) {
        MCSettlers.LOGGER.info("CrafterBrain: Crafting item: " + itemToCraft + " from items in inventory: " + villager.getInventory());
        // Clear inventory and add the item to craft
        AvailableRecipe selectedRecipe = villager.getBrain().getOptionalMemory(ModMemoryModules.SELECTED_RECIPE).orElse(null);
        if (selectedRecipe == null) {
            MCSettlers.LOGGER.warn("CrafterBrain: No selected recipe found for villager: " + MCSettlers.workerToString(villager));
            return false; // No recipe
        }
        ItemStack result = selectedRecipe.getResult();
        if (result.isEmpty()) {
            MCSettlers.LOGGER.warn("CrafterBrain: Result is empty for item: " + itemToCraft);
            return false; // No result
        }
        // Clear the inventory
        villager.getInventory().clear();
        // Add the result to the inventory
        villager.getInventory().addStack(result);
        return true; // Assume crafting is successful for now
    }

    protected Item getItemToCraft(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation) {
        // Check for item frame above workstation
        BlockPos aboveWorkstation = workstation.up();
        Iterable<ItemFrameEntity> entityCandidates = world.getEntitiesByClass(
            ItemFrameEntity.class, new Box(aboveWorkstation), e -> true
        );
        for (ItemFrameEntity itemFrame : entityCandidates) {
            ItemStack itemStack = itemFrame.getHeldItemStack();
            if (!itemStack.isEmpty()) {
                Item item = itemStack.getItem();
                MCSettlers.LOGGER.info("CrafterBrain: Found item in frame: " + item);
                return item; // Return the item found in the frame
            }
        }

        return Items.OAK_PLANKS;
    }

    protected Set<AvailableRecipe> getAvailableRecipes(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation) {
        Item itemToCraft = getItemToCraft(villager, world, workstation);
        if (itemToCraft == null) {
            return new HashSet<>();
        }

        Set<AvailableRecipe> availableRecipes = new HashSet<>();

        // Find recipe for the item
        ServerRecipeManager recipeManager = world.getRecipeManager();
        Collection<RecipeEntry<?>> allRecipes = recipeManager.values();

        Iterable<RecipeEntry<CraftingRecipe>> foundRecipeEntries = allRecipes.stream()
            // Filter for only CraftingRecipe instances
            .filter(recipeEntry -> recipeEntry.value() instanceof CraftingRecipe)
            // Cast to CraftingRecipe for further processing
            .map(recipeEntry -> (RecipeEntry<CraftingRecipe>) recipeEntry)
            // Check if the recipe matches the item to craft
            .filter(recipeEntry -> {
                String itemId = recipeEntry.id().toString().replace("ResourceKey[minecraft:recipe / ", "").replace("]", "");
                return itemId.equals(itemToCraft.toString());
            }).toList();

        for (RecipeEntry<CraftingRecipe> recipeEntry : foundRecipeEntries) {
            
            CraftingRecipe recipe = recipeEntry.value();

            AvailableRecipe availableRecipe = new AvailableRecipe(recipe, itemToCraft);
            availableRecipes.add(availableRecipe);
        }

        MCSettlers.LOGGER.info("CrafterBrain: availableRecipes: " + availableRecipes);

        return availableRecipes;
    }

    private Map<Item, Integer> getItemCountInDepositChest(
            ServerWorld world, VillagerEntity villager) {
        Map<Item, Integer> itemCountMap = new HashMap<>();
        BlockPos depositChestPos = villager.getBrain().getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST)
            .orElse(null);

        if (depositChestPos == null) {
            MCSettlers.LOGGER.warn("CrafterBrain: No deposit chest found for villager: " + MCSettlers.workerToString(villager));
            return itemCountMap; // No deposit chest, return empty map
        }

        BlockEntity blockEntity = world.getBlockEntity(depositChestPos);
        if (blockEntity instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.getStack(i);
                if (!stack.isEmpty()) {
                    itemCountMap.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return itemCountMap;
    }

    private void refreshCraftingRecipes(
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        Brain<?> brain = villager.getBrain();
        Set<AvailableRecipe> newAvailableRecipes = getAvailableRecipes(villager, world, workstation);
        if (newAvailableRecipes != null) {
            brain.remember(ModMemoryModules.AVAILABLE_RECIPES, newAvailableRecipes);
            // Count how many items are already in chest
            Map<Item, Integer> itemCountInDepositChest = getItemCountInDepositChest(world, villager);
            Set<RegistryEntry<Item>> wantedItems = new HashSet<>();
            for (AvailableRecipe recipe : newAvailableRecipes) {
                for (Item item : recipe.getWantedItems()) {
                    // If we already have 10 of this item in the deposit chest, we don't want it
                    // Unless the wanted items will be empty
                    if (itemCountInDepositChest.getOrDefault(item, 0) >= 10) {
                        continue; // Skip this item
                    }
                    wantedItems.add(Registries.ITEM.getEntry(item));
                }
            }
            if (wantedItems.isEmpty()) {
                for (AvailableRecipe recipe : newAvailableRecipes) {
                    for (Item item : recipe.getWantedItems()) {
                        wantedItems.add(Registries.ITEM.getEntry(item));
                    }
                }
            }
            brain.remember(ModMemoryModules.WANTED_ITEMS, wantedItems);
            MCSettlers.LOGGER.info("CrafterBrain: refreshed crafting recipes for villager: " + MCSettlers.workerToString(villager));
        } else {
            MCSettlers.LOGGER.warn("CrafterBrain: no available recipes found for villager: " + MCSettlers.workerToString(villager));
        }
    }
}
