package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads HTML from the mod's assets with an in-memory cache; any failure falls back to the built-in
 * string so a broken resource pack can never blank out the UI.
 */
public final class HtmlResources {
    private static final Map<String, String> CACHE = new HashMap<>();

    private HtmlResources() {
    }

    public static String load(String path, String fallback) {
        String cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        String text = fallback;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getResourceManager() != null) {
                ResourceLocation location = new ResourceLocation(WarProject.MODID, path);
                Resource resource = minecraft.getResourceManager().getResource(location).orElse(null);
                if (resource != null) {
                    try (InputStream stream = resource.open()) {
                        text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception exception) {
            text = fallback;
        }
        CACHE.put(path, text);
        return text;
    }

    /** Drops the cache (resource pack reload). */
    public static void invalidate() {
        CACHE.clear();
    }
}
