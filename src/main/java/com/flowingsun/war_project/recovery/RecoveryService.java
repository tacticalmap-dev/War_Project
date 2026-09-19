package com.flowingsun.war_project.recovery;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Backs the block world up before a game starts and puts it back after the game ended.
 *
 * <p>Backup copies the region files of every dimension into {@code war_project_recovery/world}
 * right after the world has been flushed to disk. Restore flushes the world again, compares the
 * per-chunk timestamps of the live files with the backup, and then replays only the chunks that
 * actually changed: loaded chunks are rewritten block by block and synced to their players, while
 * unloaded chunks get their stored payload copied straight back into the region file.</p>
 */
public final class RecoveryService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final int MAX_CHUNKS_PER_TICK = 8;
    private static final long TICK_BUDGET_NANOS = 8_000_000L;
    private static RecoveryService active;

    private final Deque<ChunkTask> queue = new ArrayDeque<>();

    private long lastBackupAt;
    private int backupFiles;
    private long backupBytes;
    private long lastRestoreAt;
    private int restoredChunks;
    private int restoredBlocks;
    private int failedChunks;
    private int skippedChunks;
    private long restoreStartedAt;

    private RecoveryService() {
    }

    public static RecoveryService active() {
        if (active == null) {
            active = new RecoveryService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    public boolean isRestoring() {
        return !queue.isEmpty();
    }

    public int queuedChunks() {
        return queue.size();
    }

    public long lastBackupAt() {
        return lastBackupAt;
    }

    public long lastRestoreAt() {
        return lastRestoreAt;
    }

    /** Flushes the world and copies every region file into the snapshot directory. */
    public boolean capture(MinecraftServer server) {
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            flushWorld(server);
            WorldBackup.CopyReport report = WorldBackup.copy(worldRoot);
            lastBackupAt = System.currentTimeMillis();
            backupFiles = report.files();
            backupBytes = report.bytes();
            LOGGER.info("War Project recovery: world backup taken ({} region file(s), {} KiB) -> {}",
                    backupFiles, backupBytes / 1024L, WorldBackup.snapshotRoot(worldRoot));
            return true;
        } catch (IOException exception) {
            LOGGER.warn("War Project recovery: world backup failed", exception);
            return false;
        }
    }

    /** Queues every chunk the current round changed; the tick handler replays them in slices. */
    public boolean beginRestore(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (!WorldBackup.hasBackup(worldRoot)) {
            LOGGER.warn("War Project recovery: no world backup available, the map is left as it is");
            return false;
        }
        try {
            flushWorld(server);
            queue.clear();
            restoredChunks = 0;
            restoredBlocks = 0;
            failedChunks = 0;
            skippedChunks = 0;
            List<ChunkTask> tasks = new ArrayList<>();
            for (WorldBackup.RegionPair pair : WorldBackup.backupRegions(worldRoot)) {
                ServerLevel level = levelFor(server, pair.relative());
                WorldBackup.ChunkDelta[] deltas = WorldBackup.changedChunks(pair.liveFile(), pair.backupFile())
                        .toArray(new WorldBackup.ChunkDelta[0]);
                for (WorldBackup.ChunkDelta delta : deltas) {
                    tasks.add(new ChunkTask(level, pair.liveFile(), pair.backupFile(), delta.chunkX(), delta.chunkZ(), delta.index()));
                }
            }
            skippedChunks = 0;
            queue.addAll(tasks);
            restoreStartedAt = System.nanoTime();
            lastRestoreAt = System.currentTimeMillis();
            LOGGER.info("War Project recovery: world restore queued {} changed chunk(s) from the backup", tasks.size());
            return true;
        } catch (IOException exception) {
            LOGGER.warn("War Project recovery: world restore could not be prepared", exception);
            return false;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || queue.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int processed = 0;
        while (!queue.isEmpty() && processed < MAX_CHUNKS_PER_TICK && System.nanoTime() < deadline) {
            replay(server, queue.poll());
            processed++;
        }
        if (queue.isEmpty()) {
            finishRestore(server);
        }
    }

    private void replay(MinecraftServer server, ChunkTask task) {
        byte[] payload;
        try {
            payload = RegionFileStore.readPayload(task.backupFile(), task.index());
        } catch (IOException exception) {
            failedChunks++;
            return;
        }
        if (payload == null) {
            failedChunks++;
            return;
        }
        LevelChunk live = task.level() == null
                ? null
                : task.level().getChunkSource().getChunkNow(task.chunkX(), task.chunkZ());
        if (live == null) {
            // Not loaded: put the stored bytes straight back, the next load will read them.
            try {
                RegionFileStore.writePayload(task.liveFile(), task.index(), payload);
                restoredChunks++;
            } catch (IOException exception) {
                failedChunks++;
            }
            return;
        }
        try {
            CompoundTag tag = decode(payload);
            restoredBlocks += ChunkRewriter.rewrite(task.level(), live, tag,
                    new ChunkPos(task.chunkX(), task.chunkZ()));
            restoredChunks++;
        } catch (IOException | RuntimeException exception) {
            failedChunks++;
            LOGGER.warn("War Project recovery: chunk [{}, {}] could not be restored: {}",
                    task.chunkX(), task.chunkZ(), exception.toString());
        }
    }

    private void finishRestore(MinecraftServer server) {
        double seconds = (System.nanoTime() - restoreStartedAt) / 1_000_000_000.0D;
        LOGGER.info("War Project recovery: world restored - {} chunk(s), {} block(s), {} failed, {} skipped in {}s",
                restoredChunks, restoredBlocks, failedChunks, skippedChunks,
                String.format(Locale.ROOT, "%.2f", seconds));
        String message = "War Project game ended: the map was rolled back to the pre-game backup ("
                + restoredChunks + " chunk(s), " + restoredBlocks + " block(s)).";
        server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(message), false);
    }

    public String status(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        boolean hasBackup = WorldBackup.hasBackup(worldRoot);
        String backup = lastBackupAt == 0L
                ? (hasBackup ? "on disk" : "<none>")
                : format(lastBackupAt);
        return "Recovery backup=" + backup
                + " files=" + backupFiles
                + " size=" + (backupBytes / 1024L) + "KiB"
                + " dir=" + WorldBackup.snapshotRoot(worldRoot)
                + " restoring=" + isRestoring()
                + " queued=" + queuedChunks()
                + " lastRestore=" + (lastRestoreAt == 0L ? "<never>" : format(lastRestoreAt))
                + " chunks=" + restoredChunks
                + " blocks=" + restoredBlocks
                + " failed=" + failedChunks;
    }

    private static void flushWorld(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            level.save(null, true, false);
            level.getChunkSource().getDataStorage().save();
        }
    }

    private static CompoundTag decode(byte[] payload) throws IOException {
        int length = RegionFileStore.readInt(payload, 0);
        int compression = payload[4];
        byte[] data = new byte[length - 1];
        System.arraycopy(payload, 5, data, 0, data.length);
        byte[] raw;
        if (compression == 1) {
            raw = readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
        } else if (compression == 2) {
            raw = readAll(new InflaterInputStream(new ByteArrayInputStream(data)));
        } else if (compression == 3) {
            raw = data;
        } else {
            throw new IOException("unknown chunk compression " + compression);
        }
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return in.readAllBytes();
        }
    }

    /** Maps a backup-relative path such as {@code DIM-1/region/r.0.0.mca} onto its dimension. */
    private static ServerLevel levelFor(MinecraftServer server, String relative) {
        String path = relative.replace('\\', '/');
        if (path.startsWith("region/")) {
            return server.overworld();
        }
        if (path.startsWith("DIM-1/region/")) {
            return server.getLevel(Level.NETHER);
        }
        if (path.startsWith("DIM1/region/")) {
            return server.getLevel(Level.END);
        }
        if (path.startsWith("dimensions/")) {
            String rest = path.substring("dimensions/".length());
            int marker = rest.indexOf("/region/");
            int separator = marker < 0 ? -1 : rest.lastIndexOf('/', marker - 1);
            if (separator > 0) {
                String namespace = rest.substring(0, separator);
                String id = rest.substring(separator + 1, marker);
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                        new ResourceLocation(namespace, id));
                return server.getLevel(key);
            }
        }
        return null;
    }

    private static String format(long epochMillis) {
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    private record ChunkTask(ServerLevel level, Path liveFile, Path backupFile, int chunkX, int chunkZ, int index) {
    }
}
