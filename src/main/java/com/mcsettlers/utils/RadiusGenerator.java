package com.mcsettlers.utils;

import net.minecraft.util.math.BlockPos;

import java.util.*;

public class RadiusGenerator {

    public static Iterable<BlockPos> radiusCoordinates(BlockPos center, int radius) {
        return () -> new Iterator<BlockPos>() {
            private final PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
            private final Set<BlockPos> seen = new HashSet<>();
            private final int r = radius;

            {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            BlockPos pos = center.add(dx, dy, dz);
                            if (center.getSquaredDistance(pos) <= r * r) {
                                queue.add(pos);
                                seen.add(pos);
                            }
                        }
                    }
                }
            }

            @Override
            public boolean hasNext() {
                return !queue.isEmpty();
            }

            @Override
            public BlockPos next() {
                return queue.poll();
            }
        };
    }
}
