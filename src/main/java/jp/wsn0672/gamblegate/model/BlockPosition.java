package jp.wsn0672.gamblegate.model;

import org.bukkit.Location;

public record BlockPosition(String world, int x, int y, int z) {
    public static BlockPosition standingOn(Location location) {
        Location below = location.clone().subtract(0, 0.01, 0);
        return new BlockPosition(below.getWorld().getName(), below.getBlockX(), below.getBlockY(), below.getBlockZ());
    }
}
