package com.mcsettlers.utils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock; // Needed for the type check
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket; // This is the correct packet
import net.minecraft.server.MinecraftServer; // For getting player manager
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ChestAnimationHelper {

    /**
     * Plays the chest open or close animation for all relevant players.
     * This method directly sends a BlockEventS2CPacket as world.updateListeners
     * does not directly support the BlockEventS2CPacket format in this version.
     *
     * @param world The world the chest is in. Must be a server world.
     * @param pos   The BlockPos of the chest.
     * @param open  True to open the chest, false to close.
     */
    public static void animateChest(World world, BlockPos pos, boolean open) {
        // Ensure it's a server-side world.
        if (world.isClient()) {
            System.out.println("animateChest called on client-side, returning.");
            return;
        }

        BlockState blockState = world.getBlockState(pos);
        Block block = blockState.getBlock(); // Get the actual Block instance

        // Ensure it's a chest block. If not, don't do anything.
        if (!(block instanceof ChestBlock)) {
            System.out.println("Not a chest at " + pos.toShortString() + ". Cannot animate.");
            return;
        }

        // The 'type' is the action ID, 'data' is the action parameter.
        // For chests: type = 1, data = 1 (open) or 0 (close).
        int actionType = 1; // Block event ID for chest animation
        int actionData = open ? 1 : 0; // 1 for open, 0 for close

        // Get the server instance to access the player manager
        MinecraftServer server = world.getServer();
        if (server == null) {
            System.out.println("Could not get server instance. Cannot send block event packet.");
            return;
        }

        // Create the BlockEventS2CPacket
        BlockEventS2CPacket packet = new BlockEventS2CPacket(pos, block, actionType, actionData);

        // Iterate through all players and send the packet to those in the same world
        // and within a reasonable distance
        // You can adjust the distance (e.g., 64 * 64 for 64 blocks squared distance)
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld() == world
                    && player.getPos().squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) < 64 * 64) {
                player.networkHandler.sendPacket(packet);
            }
        }

        // Optionally, play the chest open/close sound
        // This is separate from the visual animation and should still be done
        if (open) {
            world.playSound(null, pos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f,
                    world.random.nextFloat() * 0.1f + 0.9f);
        } else {
            world.playSound(null, pos, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5f,
                    world.random.nextFloat() * 0.1f + 0.9f);
        }
    }

    // Example usage (e.g., from a command or a block tick)
    // Make sure to call this method from a server-side context.
    /*
     * public static void exampleCall(ServerWorld serverWorld, BlockPos
     * chestLocation) {
     * // To open the chest
     * ChestAnimationHelper.animateChest(serverWorld, chestLocation, true);
     * 
     * // To close the chest after a delay (e.g., 5 seconds = 100 ticks)
     * serverWorld.getServer().getScheduler().scheduleDelayedTask(() -> {
     * ChestAnimationHelper.animateChest(serverWorld, chestLocation, false);
     * }, 100); // This is conceptual; your scheduler implementation might differ
     * }
     */
}