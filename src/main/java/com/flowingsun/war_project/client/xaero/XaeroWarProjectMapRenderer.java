package com.flowingsun.war_project.client.xaero;

import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.team.TeamClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.Optional;

public final class XaeroWarProjectMapRenderer {
    private static final int PIXELS_PER_CHUNK = 16;
    private static final int FRIENDLY_RGB = 0x20A4F3;
    private static final int ENEMY_RGB = 0xFF1F57;
    private static final int NEUTRAL_RGB = 0xFFFFFF;
    private static final DashPattern CHUNK_DASH_PATTERN = dashPattern(PIXELS_PER_CHUNK);
    public static final int WARZONE_FILL_ALPHA = 0x40;
    public static final int EDGE_ALPHA = 0xFF;
    private static int refreshNonce;
    private static int lastSeenMapVersion = -1;
    private static int lastSeenTeamVersion = -1;

    private XaeroWarProjectMapRenderer() {
    }

    public static int[] renderChunk(int chunkX, int chunkZ) {
        Optional<ClientMapState.ClientNode> node = ClientMapState.nodeAt(chunkX, chunkZ);
        Optional<ClientMapState.ClientWarzone> warzone = warzoneAt(chunkX, chunkZ);
        if (node.isEmpty() && warzone.isEmpty()) {
            return null;
        }

        int[] pixels = new int[PIXELS_PER_CHUNK * PIXELS_PER_CHUNK];
        if (warzone.isPresent()) {
            Arrays.fill(pixels, relationFillXaero(warzone.get().factionId()));
            drawWarzoneEdges(pixels, chunkX, chunkZ, warzone.get());
        }
        if (node.isPresent()) {
            drawNodeEdges(pixels, chunkX, chunkZ, node.get());
        }
        return pixels;
    }

    public static boolean chunkHasOverlay(int chunkX, int chunkZ) {
        return ClientMapState.nodeAt(chunkX, chunkZ).isPresent() || warzoneAt(chunkX, chunkZ).isPresent();
    }

    public static boolean regionHasOverlay(int regionX, int regionZ) {
        int minChunkX = regionX << 5;
        int minChunkZ = regionZ << 5;
        int maxChunkX = minChunkX + 31;
        int maxChunkZ = minChunkZ + 31;
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            if (intersectsRegion(node.chunks(), minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                return true;
            }
        }
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            if (intersectsRegion(warzone.chunks(), minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                return true;
            }
        }
        return false;
    }

    public static int regionHash(int regionX, int regionZ) {
        updateRefreshNonce();
        int hash = 17;
        hash = hash * 31 + regionX;
        hash = hash * 31 + regionZ;
        hash = hash * 31 + ClientMapState.version();
        hash = hash * 31 + TeamClientState.version();
        hash = hash * 31 + refreshNonce;
        hash = hash * 31 + localTeam().orElse("").hashCode();
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            if (!intersectsRegion(node.chunks(), (regionX - 1) << 5, (regionZ - 1) << 5, ((regionX + 1) << 5) + 31, ((regionZ + 1) << 5) + 31)) {
                continue;
            }
            hash = hash * 31 + node.id().hashCode();
            hash = hash * 31 + node.factionId().hashCode();
            hash = hash * 31 + node.colorRgb();
            hash = hash * 31 + node.chunks().hashCode();
            hash = hash * 31 + relationEdgeXaero(node.factionId());
        }
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            if (!intersectsRegion(warzone.chunks(), (regionX - 1) << 5, (regionZ - 1) << 5, ((regionX + 1) << 5) + 31, ((regionZ + 1) << 5) + 31)) {
                continue;
            }
            hash = hash * 31 + warzone.id().hashCode();
            hash = hash * 31 + warzone.nodeId().hashCode();
            hash = hash * 31 + warzone.factionId().hashCode();
            hash = hash * 31 + warzone.colorRgb();
            hash = hash * 31 + warzone.chunks().hashCode();
            hash = hash * 31 + relationEdgeXaero(warzone.factionId());
        }
        return hash;
    }

    public static Optional<ClientMapState.ClientWarzone> warzoneAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        return ClientMapState.warzones().values().stream().filter(warzone -> warzone.chunks().contains(key)).findFirst();
    }

    private static void drawNodeEdges(int[] pixels, int chunkX, int chunkZ, ClientMapState.ClientNode node) {
        int color = relationEdgeXaero(node.factionId());
        drawChunkEdges(pixels, chunkX, chunkZ, color, true,
                !sameNode(chunkX - 1, chunkZ, node.id()),
                !sameNode(chunkX + 1, chunkZ, node.id()),
                !sameNode(chunkX, chunkZ - 1, node.id()),
                !sameNode(chunkX, chunkZ + 1, node.id()));
    }

    private static void drawWarzoneEdges(int[] pixels, int chunkX, int chunkZ, ClientMapState.ClientWarzone warzone) {
        int color = relationEdgeXaero(warzone.factionId());
        drawChunkEdges(pixels, chunkX, chunkZ, color, false,
                shouldDrawWarzoneEdge(warzone, chunkX - 1, chunkZ),
                shouldDrawWarzoneEdge(warzone, chunkX + 1, chunkZ),
                shouldDrawWarzoneEdge(warzone, chunkX, chunkZ - 1),
                shouldDrawWarzoneEdge(warzone, chunkX, chunkZ + 1));
    }

    private static boolean sameNode(int chunkX, int chunkZ, String nodeId) {
        return ClientMapState.nodeAt(chunkX, chunkZ).map(node -> node.id().equals(nodeId)).orElse(false);
    }

    public static boolean shouldDrawWarzoneEdge(ClientMapState.ClientWarzone current, int adjacentChunkX, int adjacentChunkZ) {
        Optional<ClientMapState.ClientWarzone> adjacent = warzoneAt(adjacentChunkX, adjacentChunkZ);
        if (adjacent.isEmpty()) {
            return true;
        }
        if (adjacent.get().id().equals(current.id())) {
            return false;
        }
        return !sameSide(current.factionId(), adjacent.get().factionId());
    }

    private static boolean sameSide(String leftFaction, String rightFaction) {
        if (!isFaction(leftFaction) || !isFaction(rightFaction)) {
            return false;
        }
        return leftFaction.equals(rightFaction) || TeamClientState.alliesOf(leftFaction).contains(rightFaction);
    }

    private static void drawChunkEdges(int[] pixels, int chunkX, int chunkZ, int color, boolean dashed, boolean west, boolean east, boolean north, boolean south) {
        if (west) {
            drawVerticalEdge(pixels, 0, chunkZ, color, dashed);
        }
        if (east) {
            drawVerticalEdge(pixels, PIXELS_PER_CHUNK - 1, chunkZ, color, dashed);
        }
        if (north) {
            drawHorizontalEdge(pixels, 0, chunkX, color, dashed);
        }
        if (south) {
            drawHorizontalEdge(pixels, PIXELS_PER_CHUNK - 1, chunkX, color, dashed);
        }
    }

    private static void drawVerticalEdge(int[] pixels, int x, int chunkZ, int color, boolean dashed) {
        for (int y = 0; y < PIXELS_PER_CHUNK; y++) {
            if (!dashed || CHUNK_DASH_PATTERN.on(chunkZ * PIXELS_PER_CHUNK + y)) {
                pixels[y * PIXELS_PER_CHUNK + x] = color;
            }
        }
    }

    private static void drawHorizontalEdge(int[] pixels, int y, int chunkX, int color, boolean dashed) {
        for (int x = 0; x < PIXELS_PER_CHUNK; x++) {
            if (!dashed || CHUNK_DASH_PATTERN.on(chunkX * PIXELS_PER_CHUNK + x)) {
                pixels[y * PIXELS_PER_CHUNK + x] = color;
            }
        }
    }

    private static DashPattern dashPattern(int chunkPixels) {
        int dash = clamp((int) Math.round(chunkPixels * 0.25D), 3, 18);
        int gap = clamp((int) Math.round(chunkPixels * 0.15D), 2, 12);
        return new DashPattern(dash, gap);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean intersectsRegion(Iterable<Long> chunks, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        for (Long key : chunks) {
            ChunkPos pos = new ChunkPos(key);
            if (pos.x >= minChunkX && pos.x <= maxChunkX && pos.z >= minChunkZ && pos.z <= maxChunkZ) {
                return true;
            }
        }
        return false;
    }

    private static void updateRefreshNonce() {
        int mapVersion = ClientMapState.version();
        int teamVersion = TeamClientState.version();
        if (mapVersion != lastSeenMapVersion || teamVersion != lastSeenTeamVersion) {
            lastSeenMapVersion = mapVersion;
            lastSeenTeamVersion = teamVersion;
            refreshNonce++;
        }
    }

    private static int xaeroColor(int rgb, int alpha) {
        return ((rgb & 0xFF) << 24)
                | (((rgb >> 8) & 0xFF) << 16)
                | (((rgb >> 16) & 0xFF) << 8)
                | (alpha & 0xFF);
    }

    public static int relationFillArgb(String factionId) {
        return relationColor(factionId, WARZONE_FILL_ALPHA, false);
    }

    public static int relationEdgeArgb(String factionId) {
        return relationColor(factionId, EDGE_ALPHA, false);
    }

    public static int relationFillXaero(String factionId) {
        return relationColor(factionId, WARZONE_FILL_ALPHA, true);
    }

    public static int relationEdgeXaero(String factionId) {
        return relationColor(factionId, EDGE_ALPHA, true);
    }

    private static int relationColor(String factionId, int alpha, boolean xaero) {
        int rgb;
        if (!isFaction(factionId)) {
            rgb = NEUTRAL_RGB;
        } else {
            Optional<String> localTeam = localTeam();
            if (localTeam.filter(factionId::equals).isPresent()) {
                rgb = FRIENDLY_RGB;
            } else if (localTeam.map(team -> TeamClientState.alliesOf(team).contains(factionId)).orElse(false)) {
                rgb = FRIENDLY_RGB;
            } else {
                rgb = ENEMY_RGB;
            }
        }
        return xaero ? xaeroColor(rgb, alpha) : argb(rgb, alpha);
    }

    private static int argb(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    private static Optional<String> localTeam() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return Optional.empty();
        }
        return TeamClientState.teamOf(minecraft.player.getScoreboardName());
    }

    private static boolean isFaction(String factionId) {
        return factionId != null
                && !factionId.isBlank()
                && !"neutral".equalsIgnoreCase(factionId)
                && !"none".equalsIgnoreCase(factionId);
    }

    private record DashPattern(int dash, int gap) {
        boolean on(int coordinate) {
            return Math.floorMod(coordinate, dash + gap) < dash;
        }
    }
}
