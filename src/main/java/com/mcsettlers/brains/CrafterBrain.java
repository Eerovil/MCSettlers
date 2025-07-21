package com.mcsettlers.brains;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.AvailableRecipe;

import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;


public class CrafterBrain extends WorkerBrain {
    // Crafter crafts items from resources

    @Override
    protected void handleJob(
        VillagerEntity villager, ServerWorld world,
        String jobStatus, BlockPos workstation, BlockPos targetBlock) {

        Brain<?> brain = villager.getBrain();

        if (jobStatus == "refresh_crafting_recipe") {
            refreshCraftingRecipes(villager, world, workstation);
            startDepositingItems(villager, world, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(villager, "no_work_after_deposit");
            } else {
                // If inventory is not empty, we can craft
                craftItem(villager, world, workstation, getItemToCraft(villager, world, workstation));
                setJobStatus(villager, "crafting");
                pauseForMS(villager, world, 5000);
                startDepositingItems(villager, world, workstation);
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
            ChestBlockEntity chest, VillagerEntity villager) {
        // Instead of getting a tool, we want to get the ingredients for crafting

        List<Item> itemsInChest = new ArrayList<>(0);
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            for (int j = 0; j < stack.getCount(); j++) {
                itemsInChest.add(stack.getItem());
            }
        }
        MCSettlers.LOGGER.info("CrafterBrain: Items in chest: " + itemsInChest);

        for (AvailableRecipe availableRecipe : getAvailableRecipes(villager, world, villager.getBlockPos())) {
            List<Item> neededItems = availableRecipe.itemsMatchRecipe(itemsInChest);
            if (neededItems == null) {
                // If we don't have the needed items, we can't craft this recipe
                MCSettlers.LOGGER.info("CrafterBrain: No needed items for recipe: " + availableRecipe);
                continue;
            }
            // We have the needed items, so we can craft this recipe
            // Let's pick up all items in neededItems
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
        villager.getInventory().clear();
        villager.getInventory().addStack(new ItemStack(itemToCraft));
        return true; // Assume crafting is successful for now
    }

    protected Item getItemToCraft(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation) {

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

            AvailableRecipe availableRecipe = new AvailableRecipe(recipe);
            availableRecipes.add(availableRecipe);
        }

        MCSettlers.LOGGER.info("CrafterBrain: availableRecipes: " + availableRecipes);

        return availableRecipes;
    }

    private void refreshCraftingRecipes(
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        Brain<?> brain = villager.getBrain();
        Set<AvailableRecipe> newAvailableRecipes = getAvailableRecipes(villager, world, workstation);
        if (newAvailableRecipes != null) {
            brain.remember(ModMemoryModules.AVAILABLE_RECIPES, newAvailableRecipes);
            Set<Item> wantedItems = new HashSet<>();
            for (AvailableRecipe recipe : newAvailableRecipes) {
                for (Item item : recipe.getWantedItems()) {
                    wantedItems.add(item);
                }
            }
            brain.remember(ModMemoryModules.WANTED_ITEMS, wantedItems);
            MCSettlers.LOGGER.info("CrafterBrain: refreshed crafting recipes for villager: " + MCSettlers.workerToString(villager));
        } else {
            MCSettlers.LOGGER.warn("CrafterBrain: no available recipes found for villager: " + MCSettlers.workerToString(villager));
        }
    }
}
