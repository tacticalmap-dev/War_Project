package com.flowingsun.war_project.recovery;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal reader/writer for Minecraft region files ({@code r.x.z.mca}).
 *
 * <p>A region file is an 8 KiB header (1024 four-byte sector allocations followed by 1024
 * four-byte chunk timestamps) and then 4 KiB sectors holding chunk payloads. Each payload is
 * stored exactly as Minecraft wrote it: a four-byte length, one compression byte and the
 * compressed chunk NBT. Copying those bytes verbatim lets an unloaded chunk be put back without
 * ever parsing NBT.</p>
 */
final class RegionFileStore {
    static final int SECTOR_BYTES = 4096;
    static final int HEADER_BYTES = 8192;
    static final int ENTRIES = 1024;
    private static final int NO_ENTRY = 0;

    private RegionFileStore() {
    }

    static int[] readLocations(Path file) throws IOException {
        byte[] header = readHeader(file);
        int[] locations = new int[ENTRIES];
        if (header == null) {
            return locations;
        }
        for (int i = 0; i < ENTRIES; i++) {
            locations[i] = readInt(header, i * 4);
        }
        return locations;
    }

    static int[] readTimestamps(Path file) throws IOException {
        byte[] header = readHeader(file);
        int[] stamps = new int[ENTRIES];
        if (header == null) {
            return stamps;
        }
        for (int i = 0; i < ENTRIES; i++) {
            stamps[i] = readInt(header, SECTOR_BYTES + i * 4);
        }
        return stamps;
    }

    /** The stored payload (length prefix + compression byte + compressed NBT), or null. */
    static byte[] readPayload(Path file, int index) throws IOException {
        int entry = readLocations(file)[index];
        if (entry == NO_ENTRY) {
            return null;
        }
        int sectors = entry & 0xFF;
        if (sectors <= 0) {
            return null;
        }
        long offset = (long) (entry >>> 8) * SECTOR_BYTES;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < offset + (long) sectors * SECTOR_BYTES) {
                return null;
            }
            byte[] buffer = new byte[sectors * SECTOR_BYTES];
            raf.seek(offset);
            raf.readFully(buffer);
            int length = readInt(buffer, 0);
            if (length <= 0 || length + 4 > buffer.length) {
                return null;
            }
            byte[] payload = new byte[length + 4];
            System.arraycopy(buffer, 0, payload, 0, payload.length);
            return payload;
        }
    }

    /** Stores a payload verbatim, reusing a free sector run when one is big enough. */
    static void writePayload(Path file, int index, byte[] payload) throws IOException {
        int sectors = Math.max(1, (payload.length + SECTOR_BYTES - 1) / SECTOR_BYTES);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            if (raf.length() < HEADER_BYTES) {
                raf.setLength(HEADER_BYTES);
            }
            byte[] header = new byte[HEADER_BYTES];
            raf.seek(0);
            raf.readFully(header);
            int[] locations = new int[ENTRIES];
            for (int i = 0; i < ENTRIES; i++) {
                locations[i] = readInt(header, i * 4);
            }
            long start = freeRunStart(locations, sectors, raf.length());
            long end = start + (long) sectors * SECTOR_BYTES;
            if (raf.length() < end) {
                raf.setLength(end);
            }
            raf.seek(start);
            raf.write(payload);
            int padding = sectors * SECTOR_BYTES - payload.length;
            if (padding > 0) {
                raf.write(new byte[padding]);
            }
            raf.getFD().sync();
            byte[] entry = new byte[4];
            writeInt(entry, 0, ((int) (start / SECTOR_BYTES) << 8) | (sectors & 0xFF));
            raf.seek((long) index * 4);
            raf.write(entry);
            writeInt(entry, 0, (int) (System.currentTimeMillis() / 1000L));
            raf.seek(SECTOR_BYTES + (long) index * 4);
            raf.write(entry);
            raf.getFD().sync();
        }
    }

    static int index(int localX, int localZ) {
        return (localX & 31) + (localZ & 31) * 32;
    }

    static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static long freeRunStart(int[] locations, int sectors, long fileSize) {
        int total = (int) Math.max(2L, (fileSize + SECTOR_BYTES - 1) / SECTOR_BYTES);
        boolean[] used = new boolean[total];
        for (int entry : locations) {
            if (entry == NO_ENTRY) {
                continue;
            }
            int first = entry >>> 8;
            int count = entry & 0xFF;
            for (int i = first; i < first + count && i < used.length; i++) {
                used[i] = true;
            }
        }
        int run = 0;
        for (int i = 2; i < used.length; i++) {
            if (used[i]) {
                run = 0;
                continue;
            }
            run++;
            if (run >= sectors) {
                return (long) (i - sectors + 1) * SECTOR_BYTES;
            }
        }
        return (long) total * SECTOR_BYTES;
    }

    private static byte[] readHeader(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < HEADER_BYTES) {
                return null;
            }
            byte[] header = new byte[HEADER_BYTES];
            raf.readFully(header);
            return header;
        }
    }
}
