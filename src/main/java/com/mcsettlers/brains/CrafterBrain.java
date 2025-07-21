package com.mcsettlers.brains;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import com.mcsettlers.MCSettlers;
import net.minecraft.entity.ai.brain.Brain;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.IngredientPlacement;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

class MyIngredient {
    private final Set<Item> items = new HashSet<>();

    public MyIngredient(Item item) {
        this.items.add(item);
    }
    public MyIngredient(Iterable<Item> items) {
        for (Item item : items) {
            this.items.add(item);
        }
    }
    public Set<Item> getItems() {
        return items;
    }
}

class AvailableRecipe {
    private final Set<MyIngredient> neededIngredients = new HashSet<>();

    public AvailableRecipe(CraftingRecipe recipe) {
        // Get the ingredients from the recipe
        IngredientPlacement ingredients = recipe.getIngredientPlacement();
        for (Ingredient ingredient : ingredients.getIngredients()) {
            Set<Item> matchingItems = new HashSet<>();
            for (RegistryEntry<Item> matchingItemEntry : ingredient.getMatchingItems().toList()) {
                Item matchingItem = matchingItemEntry.value();
                matchingItems.add(matchingItem);
            }
            MyIngredient myIngredient = new MyIngredient(matchingItems);
            this.neededIngredients.add(myIngredient);
        }
    }

    public Set<Item> itemsMatchRecipe(Iterable<Item> items) {
        // Can we craft this recipe with the given items?
        // Return list of items that are needed to craft the recipe
        Set<Item> availableItemsSet = new HashSet<>();
        Set<Item> itemsToUse = new HashSet<>();
        for (Item item : items) {
            availableItemsSet.add(item);
        }
        for (MyIngredient ingredient : neededIngredients) {
            boolean found = false;
            for (Item ingredientItem : ingredient.getItems()) {
                if (availableItemsSet.contains(ingredientItem)) {
                    found = true;
                    // Remove the item from the set to prevent double counting
                    availableItemsSet.remove(ingredientItem);
                    itemsToUse.add(ingredientItem); // Add to needed items
                    break;
                }
            }
            if (!found) {
                return null; // If any ingredient is not matched, return null
            }
        }
        return itemsToUse; // All ingredients matched
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AvailableRecipe: ");
        for (MyIngredient ingredient : neededIngredients) {
            sb.append("[");
            for (Item item : ingredient.getItems()) {
                sb.append(item.getName().getString()).append(", ");
            }
            sb.append("] ");
        }
        return sb.toString();
    }
}

public class CrafterBrain extends WorkerBrain {
    // Crafter crafts items from resources

    @Override
    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetBlock) {

                getNeededItemStacks(villager, world, workstation);

    }

    protected Item getItemToCraft(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation) {
        Brain<?> brain = villager.getBrain();
        return Items.STICK;
    }

    protected Iterable<AvailableRecipe> getNeededItemStacks(
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
}
