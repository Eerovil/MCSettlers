package com.mcsettlers;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestTypes;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import java.util.HashSet;

public class ModProfessions {
    public static final RegistryKey<VillagerProfession> WOODCUTTER = RegistryKey.of(RegistryKeys.VILLAGER_PROFESSION, Identifier.ofVanilla("woodcutter"));

    public static void register() {

        // Create list of all items to gather
        Set<Item> gatherableItems = new HashSet<>();
        // Add log blocks, one by one from Items.
        gatherableItems.add(Items.OAK_LOG);
        gatherableItems.add(Items.SPRUCE_LOG);
        gatherableItems.add(Items.BIRCH_LOG);
        gatherableItems.add(Items.JUNGLE_LOG);
        gatherableItems.add(Items.ACACIA_LOG);
        gatherableItems.add(Items.DARK_OAK_LOG);
        gatherableItems.add(Items.CHERRY_LOG);

        // Add sapling blocks, one by one from Items.
        gatherableItems.add(Items.OAK_SAPLING);
        gatherableItems.add(Items.SPRUCE_SAPLING);
        gatherableItems.add(Items.BIRCH_SAPLING);
        gatherableItems.add(Items.JUNGLE_SAPLING);
        gatherableItems.add(Items.ACACIA_SAPLING);
        gatherableItems.add(Items.DARK_OAK_SAPLING);
        gatherableItems.add(Items.CHERRY_SAPLING);

        // Add apple item
        gatherableItems.add(Items.APPLE);

        Set<Block> secondaryJobSites = new HashSet<>();
        // Add all log blocks as secondary job sites
        secondaryJobSites.add(Blocks.OAK_LOG);
        secondaryJobSites.add(Blocks.SPRUCE_LOG);
        secondaryJobSites.add(Blocks.BIRCH_LOG);
        secondaryJobSites.add(Blocks.JUNGLE_LOG);
        secondaryJobSites.add(Blocks.ACACIA_LOG);
        secondaryJobSites.add(Blocks.DARK_OAK_LOG);
        secondaryJobSites.add(Blocks.CHERRY_LOG);

        VillagerProfession woodcutter = new VillagerProfession(
            Text.translatable("entity." + WOODCUTTER.getValue().getNamespace() + ".villager." + WOODCUTTER.getValue().getPath()),
            entry -> entry.matchesKey(PointOfInterestTypes.FLETCHER),
            entry -> entry.matchesKey(PointOfInterestTypes.FLETCHER),
            ImmutableSet.copyOf(gatherableItems),
            ImmutableSet.copyOf(secondaryJobSites),
            SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER
        );

        Registry.register(
            Registries.VILLAGER_PROFESSION,
            WOODCUTTER,
            woodcutter
        );
    }
}
