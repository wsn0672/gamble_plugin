package jp.wsn0672.gamblegate.model;

import org.bukkit.Location;

public record RegionBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public boolean contains(Location location) {
        return contains(location, 0);
    }

    public boolean contains(Location location, int margin) {
        return location.getWorld() != null
                && world.equals(location.getWorld().getName())
                && location.getBlockX() >= minX - margin && location.getBlockX() <= maxX + margin
                && location.getBlockY() >= minY - margin && location.getBlockY() <= maxY + margin
                && location.getBlockZ() >= minZ - margin && location.getBlockZ() <= maxZ + margin;
    }

    public boolean withinDistance(Location location, double radius) {
        if (location.getWorld() == null || !world.equals(location.getWorld().getName())) return false;
        double dx = axisDistance(location.getX(), minX, maxX + 1.0);
        double dy = axisDistance(location.getY(), minY, maxY + 1.0);
        double dz = axisDistance(location.getZ(), minZ, maxZ + 1.0);
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0;
    }
}
