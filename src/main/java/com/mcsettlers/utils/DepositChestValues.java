package com.mcsettlers.utils;

import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class DepositChestValues {
    public RegistryKey<World> dimension;
    public BlockPos pos;
    public Set<Item> wantedItems;
    public Set<Item> containedItems;
}