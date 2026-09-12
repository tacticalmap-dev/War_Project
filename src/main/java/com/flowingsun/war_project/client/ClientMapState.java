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
                    readChunks(tag)
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

    private static Set<Long> readChunks(CompoundTag tag) {
        Set<Long> chunks = new LinkedHashSet<>();
        ListTag chunkTags = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (Tag raw : chunkTags) {
            CompoundTag chunk = (CompoundTag) raw;
            chunks.add(ChunkPos.asLong(chunk.getInt("x"), chunk.getInt("z")));
        }
        return chunks;
    }

    public record ClientNode(String id, String name, String factionId, int colorRgb, Set<Long> chunks) {
    }

    public record ClientWarzone(String id, String nodeId, String factionId, int colorRgb, Set<Long> chunks) {
    }
}
