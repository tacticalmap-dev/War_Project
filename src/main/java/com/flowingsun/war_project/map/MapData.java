package com.flowingsun.war_project.map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MapData extends SavedData {
    private static final String NAME = "war_project_map";
    /** Sanity cap so a mistyped /warproject map set cannot create an unusable rectangle. */
    private static final int MAX_BOUNDS_CHUNKS = 8192;

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Warzone> warzones = new LinkedHashMap<>();
    /** The playable map rectangle; null until /warproject map set defines it. */
    private Bounds bounds;

    public static MapData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MapData::load, MapData::new, NAME);
    }

    public static MapData load(CompoundTag tag) {
        MapData data = new MapData();
        ListTag nodeTags = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (Tag raw : nodeTags) {
            Node node = Node.load((CompoundTag) raw);
            if (validId(node.id()) && !node.chunks().isEmpty()) {
                data.nodes.put(node.id(), node);
            }
        }
        ListTag warzoneTags = tag.getList("warzones", Tag.TAG_COMPOUND);
        for (Tag raw : warzoneTags) {
            Warzone warzone = Warzone.load((CompoundTag) raw);
            if (validId(warzone.id()) && validId(warzone.nodeId()) && !warzone.chunks().isEmpty()) {
                data.warzones.put(warzone.id(), warzone);
            }
        }
        data.bounds = tag.contains("bounds", Tag.TAG_COMPOUND) ? Bounds.load(tag.getCompound("bounds")) : null;
        data.normalize();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag nodeTags = new ListTag();
        nodes.values().forEach(node -> nodeTags.add(node.save()));
        tag.put("nodes", nodeTags);
        ListTag warzoneTags = new ListTag();
        warzones.values().forEach(warzone -> warzoneTags.add(warzone.save()));
        tag.put("warzones", warzoneTags);
        if (bounds != null) {
            tag.put("bounds", bounds.save());
        }
        return tag;
    }

    public Collection<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public Collection<Warzone> warzones() {
        return List.copyOf(warzones.values());
    }

    public Optional<Node> node(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Optional<Warzone> warzone(String warzoneId) {
        return Optional.ofNullable(warzones.get(warzoneId));
    }

    public Optional<Warzone> warzoneForNode(String nodeId) {
        return warzones.values().stream().filter(warzone -> warzone.nodeId().equals(nodeId)).findFirst();
    }

    public Optional<Node> findNodeAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        return nodes.values().stream().filter(node -> node.chunks().contains(key)).findFirst();
    }

    public Optional<Warzone> findWarzoneAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        return warzones.values().stream().filter(warzone -> warzone.chunks().contains(key)).findFirst();
    }

    /** The playable map rectangle, empty until /warproject map set has defined one. */
    public Optional<Bounds> bounds() {
        return Optional.ofNullable(bounds);
    }

    /**
     * Defines the playable map rectangle. Corners are normalised, so the two command arguments may be
     * given in any order.
     */
    public SaveResult setBounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        int lowX = Math.min(minChunkX, maxChunkX);
        int highX = Math.max(minChunkX, maxChunkX);
        int lowZ = Math.min(minChunkZ, maxChunkZ);
        int highZ = Math.max(minChunkZ, maxChunkZ);
        if (highX - lowX >= MAX_BOUNDS_CHUNKS || highZ - lowZ >= MAX_BOUNDS_CHUNKS) {
            return SaveResult.invalid("Map rectangle must be smaller than " + MAX_BOUNDS_CHUNKS + " chunks per axis.");
        }
        Bounds next = new Bounds(lowX, lowZ, highX, highZ);
        if (next.equals(bounds)) {
            return SaveResult.success();
        }
        bounds = next;
        setDirty();
        return SaveResult.success();
    }

    public SaveResult saveNodeWithWarzone(String nodeId, String nodeName, Set<Long> nodeChunks, Set<Long> warzoneChunks, int colorRgb, boolean vp) {
        String cleanNodeId = cleanId(nodeId);
        if (!validId(cleanNodeId)) {
            return SaveResult.invalid("Invalid node id.");
        }
        if (nodes.containsKey(cleanNodeId)) {
            return SaveResult.invalid("Node already exists: " + cleanNodeId);
        }
        if (nodeChunks.isEmpty()) {
            return SaveResult.invalid("Node requires at least one chunk.");
        }
        if (intersectsAnyNode(nodeChunks, "")) {
            return SaveResult.invalid("Node chunks overlap another node.");
        }
        Set<Long> cleanWarzoneChunks = new LinkedHashSet<>(warzoneChunks);
        cleanWarzoneChunks.addAll(nodeChunks);
        if (!cleanWarzoneChunks.containsAll(nodeChunks)) {
            return SaveResult.invalid("Warzone must contain all node chunks.");
        }
        Set<Long> extra = new LinkedHashSet<>(cleanWarzoneChunks);
        extra.removeAll(nodeChunks);
        if (extra.isEmpty()) {
            return SaveResult.invalid("Warzone requires at least one non-node chunk.");
        }
        if (intersectsAnyNode(extra, cleanNodeId)) {
            return SaveResult.invalid("Warzone extra chunks cannot overlap any node.");
        }
        if (intersectsAnyWarzone(cleanWarzoneChunks, "")) {
            return SaveResult.invalid("Warzone chunks overlap another warzone.");
        }

        String name = nodeName == null || nodeName.isBlank() ? cleanNodeId : nodeName.trim();
        // A fresh node starts with no output; a VP node never gains any, so the flag is the only difference.
        Node node = new Node(cleanNodeId, name, "neutral", normalizeRgb(colorRgb), System.currentTimeMillis(), 0.0D, 0.0D, Set.copyOf(nodeChunks), vp);
        Warzone warzone = new Warzone(cleanNodeId, cleanNodeId, "neutral", normalizeRgb(colorRgb), System.currentTimeMillis(), Set.copyOf(cleanWarzoneChunks));
        nodes.put(cleanNodeId, node);
        warzones.put(warzone.id(), warzone);
        setDirty();
        return SaveResult.success();
    }

    public SaveResult renameNode(String oldNodeId, String newNodeId, String newName) {
        Node existing = nodes.get(oldNodeId);
        String cleanNewId = cleanId(newNodeId);
        if (existing == null) {
            return SaveResult.invalid("Node not found: " + oldNodeId);
        }
        if (!validId(cleanNewId)) {
            return SaveResult.invalid("Invalid node id.");
        }
        if (!oldNodeId.equals(cleanNewId) && nodes.containsKey(cleanNewId)) {
            return SaveResult.invalid("Node already exists: " + cleanNewId);
        }
        nodes.remove(oldNodeId);
        Node renamed = existing.withIdentity(cleanNewId, newName == null || newName.isBlank() ? cleanNewId : newName.trim());
        nodes.put(cleanNewId, renamed);
        List<Warzone> updates = warzones.values().stream().filter(warzone -> warzone.nodeId().equals(oldNodeId)).toList();
        for (Warzone warzone : updates) {
            warzones.remove(warzone.id());
            String warzoneId = warzone.id().equals(oldNodeId) ? cleanNewId : warzone.id();
            warzones.put(warzoneId, warzone.withIdentity(warzoneId, cleanNewId));
        }
        setDirty();
        return SaveResult.success();
    }

    public boolean deleteNode(String nodeId) {
        Node removed = nodes.remove(nodeId);
        if (removed == null) {
            return false;
        }
        warzones.entrySet().removeIf(entry -> entry.getValue().nodeId().equals(nodeId));
        setDirty();
        return true;
    }

    public boolean setNodeFaction(String nodeId, String factionId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            return false;
        }
        String faction = normalizeFaction(factionId);
        nodes.put(nodeId, node.withFaction(faction));
        warzoneForNode(nodeId).ifPresent(warzone -> warzones.put(warzone.id(), warzone.withFaction(faction)));
        setDirty();
        return true;
    }

    /**
     * Sets the node's configured ammo and fuel output per 60 seconds. Settlement itself lives in the
     * resource module; the map module only stores the two numbers.
     */
    public boolean setNodeResourceOutputs(String nodeId, double ammoPerMinute, double fuelPerMinute) {
        Node node = nodes.get(cleanId(nodeId));
        if (node == null
                || node.vp()
                || !Double.isFinite(ammoPerMinute) || ammoPerMinute < 0.0D
                || !Double.isFinite(fuelPerMinute) || fuelPerMinute < 0.0D) {
            return false;
        }
        nodes.put(node.id(), node.withResourceOutputs(ammoPerMinute, fuelPerMinute));
        setDirty();
        return true;
    }

    /**
     * Flags or clears the VP marker. A VP node never produces anything, so turning the marker on zeroes
     * its output too; turning it off leaves the output at zero until it is set again on purpose. Returns
     * false when the node is missing or nothing actually changed.
     */
    public boolean setNodeVp(String nodeId, boolean vp) {
        Node node = nodes.get(cleanId(nodeId));
        if (node == null) {
            return false;
        }
        if (node.vp() == vp && (!vp || (node.ammoPerMinute() <= 0.0D && node.fuelPerMinute() <= 0.0D))) {
            return false;
        }
        nodes.put(node.id(), new Node(node.id(), node.name(), node.factionId(), node.colorRgb(), System.currentTimeMillis(),
                vp ? 0.0D : node.ammoPerMinute(), vp ? 0.0D : node.fuelPerMinute(), node.chunks(), vp));
        setDirty();
        return true;
    }

    public boolean isVpNode(String nodeId) {
        Node node = nodes.get(cleanId(nodeId));
        return node != null && node.vp();
    }

    /**
     * Resets every owned node back to neutral, mirroring the faction onto its warzone. Returns the
     * number of nodes that actually changed.
     */
    public int resetAllNodeFactions() {
        int changed = 0;
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            Node node = entry.getValue();
            if (!isFaction(node.factionId())) {
                continue;
            }
            entry.setValue(node.withFaction("neutral"));
            changed++;
        }
        if (changed == 0) {
            return 0;
        }
        warzones.replaceAll((id, warzone) -> isFaction(warzone.factionId()) ? warzone.withFaction("neutral") : warzone);
        setDirty();
        return changed;
    }

    public CompoundTag clientSnapshot() {
        CompoundTag tag = new CompoundTag();
        ListTag nodeTags = new ListTag();
        nodes.values().forEach(node -> nodeTags.add(node.save()));
        tag.put("nodes", nodeTags);
        ListTag warzoneTags = new ListTag();
        warzones.values().forEach(warzone -> warzoneTags.add(warzone.save()));
        tag.put("warzones", warzoneTags);
        if (bounds != null) {
            tag.put("bounds", bounds.save());
        }
        return tag;
    }

    private void normalize() {
        if (bounds != null && (bounds.minChunkX() > bounds.maxChunkX() || bounds.minChunkZ() > bounds.maxChunkZ())) {
            // A hand-edited or truncated file would otherwise project inside out on the maps.
            bounds = null;
        }
        nodes.entrySet().removeIf(entry -> !validId(entry.getKey()) || entry.getValue().chunks().isEmpty());
        warzones.entrySet().removeIf(entry -> !validId(entry.getKey())
                || !nodes.containsKey(entry.getValue().nodeId())
                || entry.getValue().chunks().isEmpty()
                || !entry.getValue().chunks().containsAll(nodes.get(entry.getValue().nodeId()).chunks()));
    }

    private boolean intersectsAnyNode(Set<Long> chunks, String ignoredNodeId) {
        for (Node node : nodes.values()) {
            if (node.id().equals(ignoredNodeId)) {
                continue;
            }
            if (intersects(chunks, node.chunks())) {
                return true;
            }
        }
        return false;
    }

    private boolean intersectsAnyWarzone(Set<Long> chunks, String ignoredWarzoneId) {
        for (Warzone warzone : warzones.values()) {
            if (warzone.id().equals(ignoredWarzoneId)) {
                continue;
            }
            if (intersects(chunks, warzone.chunks())) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Set<Long> left, Set<Long> right) {
        for (Long value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeFaction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "neutral";
        }
        String value = raw.trim();
        if ("none".equalsIgnoreCase(value)) {
            return "none";
        }
        if ("neutral".equalsIgnoreCase(value)) {
            return "neutral";
        }
        return value;
    }

    public static boolean isFaction(String raw) {
        String value = normalizeFaction(raw);
        return !"neutral".equalsIgnoreCase(value) && !"none".equalsIgnoreCase(value);
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_\\-.:]{1,64}");
    }

    public static String cleanId(String id) {
        return id == null ? "" : id.trim();
    }

    private static int normalizeRgb(int color) {
        return color == 0 ? 0x2E7DFF : color & 0xFFFFFF;
    }

    private static double normalizeResource(double value) {
        return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
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

    private static ListTag writeChunks(Collection<Long> chunks) {
        ListTag tags = new ListTag();
        for (Long key : chunks) {
            ChunkPos pos = new ChunkPos(key);
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("x", pos.x);
            chunk.putInt("z", pos.z);
            tags.add(chunk);
        }
        return tags;
    }

    /**
     * {@code vp} marks a "victory point" objective: it follows the normal capture rules but never produces
     * resources, and the maps draw a star under its name whose colour follows its current owner.
     */
    public record Node(String id, String name, String factionId, int colorRgb, long updatedAt,
                       double ammoPerMinute, double fuelPerMinute, Set<Long> chunks, boolean vp) {
        static Node load(CompoundTag tag) {
            String id = cleanId(tag.getString("id"));
            String name = tag.getString("name").isBlank() ? id : tag.getString("name");
            // "resource_per_minute" was the pre-split single-output key: migrate it into ammo.
            double ammo = normalizeResource(tag.contains("ammo_per_minute")
                    ? tag.getDouble("ammo_per_minute")
                    : tag.getDouble("resource_per_minute"));
            return new Node(id, name, normalizeFaction(tag.getString("faction_id")), tag.getInt("color_rgb"),
                    tag.getLong("updated_at"), ammo, normalizeResource(tag.getDouble("fuel_per_minute")), Set.copyOf(readChunks(tag)),
                    tag.getBoolean("vp"));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("name", name);
            tag.putString("faction_id", factionId);
            tag.putInt("color_rgb", colorRgb);
            tag.putLong("updated_at", updatedAt);
            tag.putDouble("ammo_per_minute", ammoPerMinute);
            tag.putDouble("fuel_per_minute", fuelPerMinute);
            tag.putBoolean("vp", vp);
            tag.put("chunks", writeChunks(chunks));
            return tag;
        }

        Node withIdentity(String newId, String newName) {
            return new Node(newId, newName, factionId, colorRgb, System.currentTimeMillis(), ammoPerMinute, fuelPerMinute, chunks, vp);
        }

        Node withFaction(String faction) {
            return new Node(id, name, faction, colorRgb, System.currentTimeMillis(), ammoPerMinute, fuelPerMinute, chunks, vp);
        }

        Node withResourceOutputs(double ammo, double fuel) {
            return new Node(id, name, factionId, colorRgb, System.currentTimeMillis(),
                    normalizeResource(ammo), normalizeResource(fuel), chunks, vp);
        }

        Node withVp(boolean newVp) {
            return new Node(id, name, factionId, colorRgb, System.currentTimeMillis(), ammoPerMinute, fuelPerMinute, chunks, newVp);
        }
    }

    public record Warzone(String id, String nodeId, String factionId, int colorRgb, long updatedAt, Set<Long> chunks) {
        static Warzone load(CompoundTag tag) {
            return new Warzone(cleanId(tag.getString("id")), cleanId(tag.getString("node_id")),
                    normalizeFaction(tag.getString("faction_id")), tag.getInt("color_rgb"),
                    tag.getLong("updated_at"), Set.copyOf(readChunks(tag)));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("node_id", nodeId);
            tag.putString("faction_id", factionId);
            tag.putInt("color_rgb", colorRgb);
            tag.putLong("updated_at", updatedAt);
            tag.put("chunks", writeChunks(chunks));
            return tag;
        }

        Warzone withIdentity(String newId, String newNodeId) {
            return new Warzone(newId, newNodeId, factionId, colorRgb, System.currentTimeMillis(), chunks);
        }

        Warzone withFaction(String faction) {
            return new Warzone(id, nodeId, faction, colorRgb, System.currentTimeMillis(), chunks);
        }
    }

    /**
     * The playable map rectangle in chunk coordinates, inclusive on both ends. The world-space test is
     * half-open, so the far edge counts as outside.
     */
    public record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        static Bounds load(CompoundTag tag) {
            return new Bounds(tag.getInt("min_chunk_x"), tag.getInt("min_chunk_z"),
                    tag.getInt("max_chunk_x"), tag.getInt("max_chunk_z"));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("min_chunk_x", minChunkX);
            tag.putInt("min_chunk_z", minChunkZ);
            tag.putInt("max_chunk_x", maxChunkX);
            tag.putInt("max_chunk_z", maxChunkZ);
            return tag;
        }

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

        public long chunkCount() {
            return (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }
    }

    public record SaveResult(boolean ok, String message) {
        static SaveResult success() {
            return new SaveResult(true, "");
        }

        static SaveResult invalid(String message) {
            return new SaveResult(false, message);
        }
    }
}
