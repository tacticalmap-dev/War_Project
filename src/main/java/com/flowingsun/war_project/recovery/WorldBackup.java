package com.flowingsun.war_project.recovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies the region files that carry the block world into a backup directory and works out which
 * chunks changed since that copy.
 *
 * <p>Only {@code region/} directories are handled: they hold the block states, which is what "the
 * map" means here. Player data, mod saved data, entities and points of interest are deliberately
 * left alone.</p>
 */
final class WorldBackup {
    static final String ROOT_DIR = "war_project_recovery";
    static final String SNAPSHOT_DIR = "world";
    private static final String REGION_DIR = "region";
    private static final String SUFFIX = ".mca";
    private static final int MAX_DEPTH = 6;

    record RegionPair(Path liveFile, Path backupFile, String relative) {
    }

    record ChunkDelta(int chunkX, int chunkZ, int index) {
    }

    record CopyReport(int files, long bytes) {
    }

    private WorldBackup() {
    }

    static Path snapshotRoot(Path worldRoot) {
        return worldRoot.resolve(ROOT_DIR).resolve(SNAPSHOT_DIR);
    }

    static boolean hasBackup(Path worldRoot) {
        return Files.isDirectory(snapshotRoot(worldRoot));
    }

    /** Live region files paired with where their backup lives (live side of the pair). */
    static List<RegionPair> liveRegions(Path worldRoot) throws IOException {
        Path backupRoot = snapshotRoot(worldRoot);
        List<RegionPair> pairs = new ArrayList<>();
        for (Path liveDir : regionDirectories(worldRoot, backupRoot)) {
            Path backupDir = backupRoot.resolve(worldRoot.relativize(liveDir).toString());
            for (Path live : listRegionFiles(liveDir)) {
                pairs.add(new RegionPair(live, backupDir.resolve(live.getFileName()),
                        worldRoot.relativize(live).toString().replace('\\', '/')));
            }
        }
        pairs.sort(Comparator.comparing(RegionPair::relative));
        return pairs;
    }

    /** Backup region files paired with the live file they would replace (backup side of the pair). */
    static List<RegionPair> backupRegions(Path worldRoot) throws IOException {
        Path backupRoot = snapshotRoot(worldRoot);
        List<RegionPair> pairs = new ArrayList<>();
        for (Path backupDir : regionDirectories(backupRoot, null)) {
            String relativeDir = backupRoot.relativize(backupDir).toString().replace('\\', '/');
            Path liveDir = worldRoot.resolve(backupRoot.relativize(backupDir).toString());
            for (Path backup : listRegionFiles(backupDir)) {
                pairs.add(new RegionPair(liveDir.resolve(backup.getFileName()), backup,
                        relativeDir + "/" + backup.getFileName()));
            }
        }
        pairs.sort(Comparator.comparing(RegionPair::relative));
        return pairs;
    }

    /** Copies every region file of the world into the snapshot directory. */
    static CopyReport copy(Path worldRoot) throws IOException {
        Path backupRoot = snapshotRoot(worldRoot);
        deleteRecursively(backupRoot);
        Files.createDirectories(backupRoot);
        int files = 0;
        long bytes = 0L;
        for (Path liveDir : regionDirectories(worldRoot, backupRoot)) {
            Path targetDir = backupRoot.resolve(worldRoot.relativize(liveDir).toString());
            Files.createDirectories(targetDir);
            for (Path live : listRegionFiles(liveDir)) {
                Path target = targetDir.resolve(live.getFileName());
                Files.copy(live, target, StandardCopyOption.REPLACE_EXISTING);
                bytes += Files.size(target);
                files++;
            }
        }
        return new CopyReport(files, bytes);
    }

    /** Chunks whose stored timestamp differs from the backup, i.e. what the round changed. */
    static List<ChunkDelta> changedChunks(Path liveFile, Path backupFile) throws IOException {
        int[] backupStamps = RegionFileStore.readTimestamps(backupFile);
        int[] liveStamps = RegionFileStore.readTimestamps(liveFile);
        int[] coordinates = regionCoordinates(backupFile.getFileName().toString());
        List<ChunkDelta> deltas = new ArrayList<>();
        for (int index = 0; index < RegionFileStore.ENTRIES; index++) {
            int backupStamp = backupStamps[index];
            if (backupStamp == 0 || backupStamp == liveStamps[index]) {
                continue;
            }
            int localX = index & 31;
            int localZ = index >> 5;
            deltas.add(new ChunkDelta(coordinates[0] * 32 + localX, coordinates[1] * 32 + localZ, index));
        }
        return deltas;
    }

    static int[] regionCoordinates(String fileName) {
        String name = fileName.endsWith(SUFFIX) ? fileName.substring(0, fileName.length() - SUFFIX.length()) : fileName;
        String[] parts = name.split("\\.");
        if (parts.length < 3) {
            return new int[] {0, 0};
        }
        try {
            return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException exception) {
            return new int[] {0, 0};
        }
    }

    private static List<Path> regionDirectories(Path root, Path excluded) throws IOException {
        List<Path> directories = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return directories;
        }
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isDirectory)
                    .filter(path -> REGION_DIR.equals(path.getFileName().toString()))
                    .filter(path -> excluded == null || !path.startsWith(excluded))
                    .forEach(directories::add);
        }
        return directories;
    }

    private static List<Path> listRegionFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
