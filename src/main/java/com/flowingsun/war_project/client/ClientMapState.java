package com.flowingsun.war_project.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ClientMapState {
    private static final Map<String, ClientNode> nodes = new LinkedHashMap<>();
    private static final Map<String, ClientWarzone> warzones = new LinkedHashMap<>();
    /** The map area from the last snapshot; null while the server has none defined. */
    private static ClientBounds bounds;
    private static int version;

    private ClientMapState() {
    }

    public static void replace(CompoundTag snapshot) {
        nodes.clear();
        warzones.clear();
        ListTag nodeTags = snapshot.getList("nodes", Tag.TAG_COMPOUND);
        for (Tag raw : nodeTags) {
            CompoundTag tag = (CompoundTag) raw;
            nodes.put(tag.getString("id"), new ClientNode(
                    tag.getString("id"),
                    tag.getString("name"),
                    tag.getString("faction_id"),
                    tag.getInt("color_rgb"),
                    tag.getDouble("ammo_per_minute"),
                    tag.getDouble("fuel_per_minute"),
                    readChunks(tag),
                    tag.getBoolean("vp")
            ));
        }
        ListTag warzoneTags = snapshot.getList("warzones", Tag.TAG_COMPOUND);
        for (Tag raw : warzoneTags) {
            CompoundTag tag = (CompoundTag) raw;
            warzones.put(tag.getString("id"), new ClientWarzone(
                    tag.getString("id"),
                    tag.getString("node_id"),
                    tag.getString("faction_id"),
                    tag.getInt("color_rgb"),
                    readChunks(tag)
            ));
        }
        bounds = snapshot.contains("bounds", Tag.TAG_COMPOUND) ? readBounds(snapshot.getCompound("bounds")) : null;
        version++;
    }

    public static Optional<ClientNode> nodeAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        return nodes.values().stream().filter(node -> node.chunks().contains(key)).findFirst();
    }

    public static Map<String, ClientNode> nodes() {
        return Map.copyOf(nodes);
    }

    public static Map<String, ClientWarzone> warzones() {
        return Map.copyOf(warzones);
    }

    public static int version() {
        return version;
    }

    /** The map area of the last snapshot, or null while none has been defined. */
    public static ClientBounds bounds() {
        return bounds;
    }

    private static ClientBounds readBounds(CompoundTag tag) {
        return new ClientBounds(tag.getInt("min_chunk_x"), tag.getInt("min_chunk_z"),
                tag.getInt("max_chunk_x"), tag.getInt("max_chunk_z"));
    }

    private static Set<Long> readChunks(CompoundTag tag) {
        Set<Long> chunks = new LinkedHashSet<>();
        ListTag chunkTags = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (Tag raw : chunkTags) {
            CompoundTag chunk = (CompoundTag) raw;
            chunks.add(ChunkPos.asLong(chunk.getInt("x"), chunk.getInt("z")));
        }
        return chunks;
    }

    public record ClientNode(String id, String name, String factionId, int colorRgb,
                             double ammoPerMinute, double fuelPerMinute, Set<Long> chunks, boolean vp) {
    }

    public record ClientWarzone(String id, String nodeId, String factionId, int colorRgb, Set<Long> chunks) {
    }

    /**
     * The playable map rectangle in chunk coordinates, inclusive on both ends; the block-space test is
     * half-open so the far edge belongs to the outside.
     */
    public record ClientBounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        public double minBlockX() {
            return minChunkX * 16.0D;
        }

        public double minBlockZ() {
            return minChunkZ * 16.0D;
        }

        public double maxBlockX() {
            return (maxChunkX + 1) * 16.0D;
        }

        public double maxBlockZ() {
            return (maxChunkZ + 1) * 16.0D;
        }

        public boolean containsBlock(double x, double z) {
            return x >= minBlockX() && x < maxBlockX() && z >= minBlockZ() && z < maxBlockZ();
        }
    }
}
