package dev.rosewood.rosestacker.manager;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.manager.Manager;
import dev.rosewood.rosestacker.nms.NMSAdapter;
import dev.rosewood.rosestacker.nms.NMSHandler;
import dev.rosewood.rosestacker.stack.StackingThread;
import dev.rosewood.rosestacker.utils.VersionUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

public class EntityCacheManager extends Manager {

    private final Map<ChunkLocation, Collection<Entity>> entityCache;
    private BukkitTask refreshTask;

    public EntityCacheManager(RosePlugin rosePlugin) {
        super(rosePlugin);
        this.entityCache = new ConcurrentHashMap<>();
    }

    @Override
    public void reload() {
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(this.rosePlugin, this::refresh, 5L, 60L);
    }

    @Override
    public void disable() {
        this.entityCache.clear();

        if (this.refreshTask != null) {
            this.refreshTask.cancel();
            this.refreshTask = null;
        }
    }

    /**
     * Gets nearby entities from cache
     *
     * @param center The center of the area to check
     * @param radius The radius to check around
     * @param predicate Conditions to be met
     * @return A Set of nearby entities
     */
    public Collection<Entity> getNearbyEntities(Location center, double radius, Predicate<Entity> predicate) {
        Set<Entity> nearbyEntities = new HashSet<>();
        World world = center.getWorld();
        if (world == null)
            return nearbyEntities;

        BoundingBox boundingBox = new BoundingBox(
                center.getX() - radius,
                center.getY() - radius,
                center.getZ() - radius,
                center.getX() + radius,
                center.getY() + radius,
                center.getZ() + radius
        );

        int minX = (int) boundingBox.getMinX() >> 4;
        int maxX = (int) boundingBox.getMaxX() >> 4;
        int minY = (int) boundingBox.getMinY() >> 4;
        int maxY = (int) boundingBox.getMaxY() >> 4;
        int minZ = (int) boundingBox.getMinZ() >> 4;
        int maxZ = (int) boundingBox.getMaxZ() >> 4;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Collection<Entity> entities = this.entityCache.get(new ChunkLocation(world.getName(), x, y, z));
                    if (entities == null)
                        continue;

                    for (Entity entity : entities) {
                        if (boundingBox.contains(entity.getX(), entity.getY(), entity.getZ())
                                && predicate.test(entity)
                                && entity.isValid())
                            nearbyEntities.add(entity);
                    }
                }
            }
        }

        return nearbyEntities;
    }

    /**
     * Gets entities in the Chunk of a Location
     *
     * @param location The Location of the Chunk
     * @param predicate Conditions to be met
     * @return A Set of entities in the chunk
     */
    public Collection<Entity> getEntitiesInChunk(Location location, Predicate<Entity> predicate) {
        World world = location.getWorld();
        if (world == null)
            return new ArrayList<>();

        int x = location.getBlockX() >> 4;
        int z = location.getBlockZ() >> 4;
        int minY = world.getMinHeight() >> 4;
        int maxY = world.getMaxHeight() >> 4;

        Set<Entity> entities = new HashSet<>();
        for (int y = minY; y <= maxY; y++) {
            Collection<Entity> chunkEntities = this.entityCache.get(new ChunkLocation(world.getName(), x, y, z));
            if (chunkEntities != null)
                entities.addAll(chunkEntities);
        }

        Set<Entity> nearbyEntities = new HashSet<>();
        for (Entity entity : entities)
            if (predicate.test(entity) && entity.isValid())
                nearbyEntities.add(entity);

        return nearbyEntities;
    }

    /**
     * Forces an entry into the cache, used for newly spawned entities
     *
     * @param entity The entity to cache
     */
    public void preCacheEntity(Entity entity) {
        ChunkLocation chunkLocation = new ChunkLocation(entity.getWorld().getName(), (int) entity.getX() >> 4, (int) entity.getY() >> 4, (int) entity.getZ() >> 4);
        Collection<Entity> entities = this.entityCache.computeIfAbsent(chunkLocation, k -> this.createCollection());
        entities.add(entity);
    }

    private void refresh() {
        synchronized (this.entityCache) {
            this.entityCache.clear();
            NMSHandler nmsHandler = NMSAdapter.getHandler();
            for (StackingThread stackingThread : this.rosePlugin.getManager(StackManager.class).getStackingThreads().values()) {
                World world = stackingThread.getTargetWorld();
                for (Entity entity : nmsHandler.getEntities(world)) {
                    EntityType type = entity.getType();
                    if (type != VersionUtils.ITEM && (!type.isAlive() || type == EntityType.PLAYER || type == EntityType.ARMOR_STAND))
                        continue;

                    ChunkLocation chunkLocation = new ChunkLocation(world.getName(), (int) entity.getX() >> 4, (int) entity.getY() >> 4, (int) entity.getZ() >> 4);
                    Collection<Entity> entities = this.entityCache.computeIfAbsent(chunkLocation, k -> this.createCollection());
                    entities.add(entity);
                }
            }
        }
    }

    private Collection<Entity> createCollection() {
        return new LinkedBlockingDeque<>();
    }

    /**
     * Represents a 16x16x16 chunk in the world
     */
    private record ChunkLocation(String world, int x, int y, int z) { }

}
