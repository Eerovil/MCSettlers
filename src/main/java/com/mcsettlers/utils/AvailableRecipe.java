package com.mcsettlers.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.IngredientPlacement;
import net.minecraft.registry.entry.RegistryEntry;


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

public class AvailableRecipe {
    private final Set<MyIngredient> neededIngredients = new HashSet<>();
    private CraftingRecipe recipe;
    private Item itemToCraft;

    public ItemStack getResult() {
        try {
            return recipe.craft(null, null);
        } catch (Exception e) {
            // Handle crafting exceptions
            return new ItemStack(this.itemToCraft);
        }
    }

    @SuppressWarnings("deprecation")
    public AvailableRecipe(CraftingRecipe recipe, Item itemToCraft) {
        this.recipe = recipe;
        this.itemToCraft = itemToCraft;
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

    public List<Item> itemsMatchRecipe(Iterable<Item> items) {
        // Can we craft this recipe with the given items?
        // Return list of items that are needed to craft the recipe
        List<Item> availableItemsSet = new ArrayList<>();
        List<Item> itemsToUse = new ArrayList<>();
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

    public Set<Item> getWantedItems() {
        Set<Item> wantedItems = new HashSet<>();
        for (MyIngredient ingredient : neededIngredients) {
            wantedItems.addAll(ingredient.getItems());
        }
        return wantedItems;
    }
}
