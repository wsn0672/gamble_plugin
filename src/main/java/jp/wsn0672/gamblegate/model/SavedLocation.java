package jp.wsn0672.gamblegate.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record SavedLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public static SavedLocation from(Location location) {
        return new SavedLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location toBukkit() {
        World bukkitWorld = Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld, x, y, z, yaw, pitch);
    }
}
