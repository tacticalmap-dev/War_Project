package com.flowingsun.war_project.client.cef;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Downloads, verifies and unpacks the Chromium Embedded Framework binaries the CEF backend needs.
 *
 * <p>Nothing here depends on Minecraft or on the {@code org.cef} bindings, so the whole pipeline can
 * be exercised on its own. The archive is the public java-cef build for the pinned commit and is
 * roughly 119 MiB; it is never packaged with the mod, only fetched into the game directory on first
 * use. Everything is best effort: any failure throws and the caller falls back to the built in
 * renderer.
 */
public final class CefNatives {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Public java-cef build host; the mod never talks to anything else. */
    public static final String DEFAULT_MIRROR = "https://mcef-download.cinemamod.com";
    /** The java-cef commit the bundled {@code org.cef} sources and the binaries both come from. */
    public static final String JCEF_COMMIT = "d5e3cece98755ff1e5af39261e6a486a5d9adb5d";
    /** Expected size of the official windows_amd64 archive, used when the checksum file is unreadable. */
    public static final long WINDOWS_AMD64_BYTES = 124345275L;

    /** Files that must exist next to each other for the binaries to count as installed. */
    private static final String[] REQUIRED_FILES = {"libcef.dll", "jcef.dll", "jcef_helper.exe", "icudtl.dat"};
    private static final Pattern SHA256 = Pattern.compile("\b[0-9a-fA-F]{64}\b");
    private static final int BUFFER = 1 << 16;

    private CefNatives() {
    }

    /** Progress reporting for the HUD. Stages are short English phrases, fractions are 0..1. */
    public interface Progress {
        void onStage(String stage, float fraction);
    }

    /**
     * The java-cef platform directory name, or null when this platform has no build. Only the
     * Windows x86_64 build is wired up; anything else falls back to the built in renderer.
     */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            return null;
        }
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            return "windows_amd64";
        }
        return null;
    }

    public static boolean isSupported() {
        return platform() != null;
    }

    /** {@code <gameDir>/war_project-cef}, the single directory this backend writes to. */
    public static Path root(Path gameDirectory) {
        return gameDirectory.resolve("war_project-cef");
    }

    public static Path platformDirectory(Path gameDirectory) {
        String platform = platform();
        return platform == null ? null : root(gameDirectory).resolve(platform);
    }

    /** True when the unpacked binaries are already present. */
    public static boolean isInstalled(Path gameDirectory) {
        Path directory = platformDirectory(gameDirectory);
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        for (String name : REQUIRED_FILES) {
            if (!Files.isRegularFile(directory.resolve(name))) {
                return false;
            }
        }
        return true;
    }

    /** Archive path used both for the download and for offline pre-seeding by the user. */
    public static Path archivePath(Path gameDirectory) {
        String platform = platform();
        return platform == null ? null : root(gameDirectory).resolve(platform + ".tar.gz");
    }

    /**
     * Makes sure the binaries are unpacked and returns the platform directory.
     *
     * <p>An archive dropped at {@link #archivePath(Path)} is used as-is (offline install); otherwise
     * it is downloaded from {@code mirror}, verified and unpacked.
     */
    public static Path ensure(Path gameDirectory, String mirror, Progress progress) throws IOException {
        String platform = platform();
        if (platform == null) {
            throw new IOException("no Chromium Embedded Framework build for " + System.getProperty("os.name")
                    + "/" + System.getProperty("os.arch"));
        }
        Path root = root(gameDirectory);
        Path directory = root.resolve(platform);
        if (isInstalled(gameDirectory)) {
            report(progress, "Chromium already installed", 1.0F);
            return directory;
        }
        Files.createDirectories(root);
        Path archive = root.resolve(platform + ".tar.gz");
        if (!Files.isRegularFile(archive)) {
            download(mirror == null || mirror.isBlank() ? DEFAULT_MIRROR : mirror, platform, archive, progress);
        } else {
            report(progress, "Using pre-seeded archive", 0.55F);
        }
        verify(mirror == null || mirror.isBlank() ? DEFAULT_MIRROR : mirror, platform, archive, progress);
        report(progress, "Unpacking Chromium", 0.72F);
        unpack(archive, root, progress);
        if (!isInstalled(gameDirectory)) {
            throw new IOException("Chromium binaries are incomplete after unpacking into " + directory);
        }
        Files.writeString(root.resolve(platform + ".installed"),
                JCEF_COMMIT + System.lineSeparator() + System.currentTimeMillis() + System.lineSeparator());
        LOGGER.info("War Project CEF binaries ready at {}", directory);
        report(progress, "Chromium ready", 1.0F);
        return directory;
    }

    private static void download(String mirror, String platform, Path target, Progress progress) throws IOException {
        String url = mirror + "/java-cef-builds/" + JCEF_COMMIT + "/" + platform + ".tar.gz";
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        LOGGER.info("War Project is downloading the Chromium Embedded Framework from {}", url);
        report(progress, "Downloading Chromium", 0.01F);
        HttpURLConnection connection = open(url);
        long expected = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            long total = 0L;
            int read;
            float lastReported = -1.0F;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                long denominator = expected > 0 ? expected : WINDOWS_AMD64_BYTES;
                float fraction = Math.min(0.98F, (float) total / denominator);
                if (fraction - lastReported >= 0.01F) {
                    lastReported = fraction;
                    report(progress, "Downloading Chromium " + (total >> 20) + "/" + (denominator >> 20) + " MiB",
                            0.02F + fraction * 0.5F);
                }
            }
        } finally {
            connection.disconnect();
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("War Project downloaded {} bytes of Chromium binaries", Files.size(target));
    }

    /** Checksum first, size second: the published .sha256 is a PowerShell listing and may be absent. */
    private static void verify(String mirror, String platform, Path archive, Progress progress) throws IOException {
        report(progress, "Verifying download", 0.55F);
        long size = Files.size(archive);
        String url = mirror + "/java-cef-builds/" + JCEF_COMMIT + "/" + platform + ".tar.gz.sha256";
        String expected = null;
        try {
            expected = fetchText(url);
        } catch (IOException exception) {
            LOGGER.warn("War Project could not fetch the Chromium checksum file, falling back to a size check", exception);
        }
        if (expected != null) {
            Matcher matcher = SHA256.matcher(expected);
            if (matcher.find()) {
                String actual = sha256(archive);
                if (!actual.equalsIgnoreCase(matcher.group())) {
                    Files.deleteIfExists(archive);
                    throw new IOException("Chromium archive checksum mismatch (expected " + matcher.group()
                            + ", got " + actual + ")");
                }
                return;
            }
        }
        if (platform.equals("windows_amd64") && size != WINDOWS_AMD64_BYTES) {
            Files.deleteIfExists(archive);
            throw new IOException("Chromium archive has an unexpected size: " + size);
        }
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "war_project/1.0 (+minecraft)");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return connection;
    }

    private static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void report(Progress progress, String stage, float fraction) {
        if (progress != null) {
            progress.onStage(stage, Math.max(0.0F, Math.min(1.0F, fraction)));
        }
    }

    // ------------------------------------------------------------------ tar over gzip

    /**
     * Extracts a (possibly GNU long-name) tar stream. Entries are resolved inside {@code target} and
     * anything that would escape it is skipped, so a hostile archive cannot write outside the game
     * directory.
     */
    static void unpack(Path archive, Path target, Progress progress) throws IOException {
        long total = Files.size(archive);
        long consumed = 0L;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), BUFFER);
             CountingInputStream counting = new CountingInputStream(raw);
             GZIPInputStream gzip = new GZIPInputStream(counting, BUFFER)) {
            byte[] header = new byte[512];
            String pendingName = null;
            float lastReported = -1.0F;
            while (true) {
                if (!readFully(gzip, header, 512)) {
                    break;
                }
                if (isZeroBlock(header)) {
                    break;
                }
                String name = pendingName != null ? pendingName : readString(header, 0, 100);
                pendingName = null;
                long size = readOctal(header, 124, 12);
                char type = (char) (header[156] & 0xFF);
                if (type == 'L') {
                    byte[] longName = readBlock(gzip, size);
                    pendingName = readString(longName, 0, longName.length);
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    readBlock(gzip, size);
                    continue;
                }
                String prefix = readString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                consumed = counting.count();
                if (total > 0) {
                    float fraction = Math.min(1.0F, (float) consumed / total);
                    if (fraction - lastReported >= 0.02F) {
                        lastReported = fraction;
                        report(progress, "Unpacking Chromium " + (int) (fraction * 100) + "%",
                                0.72F + fraction * 0.26F);
                    }
                }
                Path destination = safeResolve(target, name);
                if (destination == null) {
                    skip(gzip, size);
                    continue;
                }
                if (type == '5') {
                    Files.createDirectories(destination);
                    continue;
                }
                if (type != '0' && type != '\0' && type != '7') {
                    skip(gzip, size);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination), BUFFER)) {
                    copy(gzip, out, size);
                }
                long padding = (512 - (size % 512)) % 512;
                skip(gzip, padding);
            }
        }
    }

    private static Path safeResolve(Path target, String name) {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains("..")) {
            return null;
        }
        Path resolved = target.resolve(cleaned).normalize();
        return resolved.startsWith(target) ? resolved : null;
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, data.length);
        while (end < limit && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readOctal(byte[] data, int offset, int length) {
        String value = readString(data, offset, length).trim();
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static byte[] readBlock(InputStream in, long size) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(in, out, size);
        long padding = (512 - (size % 512)) % 512;
        skip(in, padding);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("unexpected end of archive");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[BUFFER];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("unexpected end of archive");
            }
            remaining -= read;
        }
    }

    /** Counts compressed bytes so the unpack stage can report progress. */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }
}
