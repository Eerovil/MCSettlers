package com.mcsettlers;

import com.google.common.collect.ImmutableSet;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;

public class ModProfessions {
    public static final VillagerProfession WOODCUTTER = Registry.register(
        Registries.VILLAGER_PROFESSION,
        new Identifier("mcsettlers", "woodcutter"),
        new VillagerProfession(
            "woodcutter",
            holder -> holder.matchesKey(ModPOIs.AXE_WORKSTATION_POI.getKey()),
            holder -> holder.matchesKey(ModPOIs.AXE_WORKSTATION_POI.getKey()),
            ImmutableSet.of(), // gatherable items
            ImmutableSet.of(), // secondary job site
            SoundEvents.ENTITY_VILLAGER_WORK_TOOLSMITH
        )
    );

    public static void register() {
        // WOODCUTTER already registered statically
    }
}
