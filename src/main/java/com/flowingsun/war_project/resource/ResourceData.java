package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.team.TeamData;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persisted per-player stockpile of both resources (war_project_resources):
 * players[] = {id, ammo, fuel}. Keys are scoreboard names, matching TeamData membership. The legacy
 * per-team section of older saves is intentionally dropped: resources are personal now.
 */
public final class ResourceData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "war_project_resources";

    /**
     * Hard ceiling for both resources. It applies to settlement, admin writes, transfers and NBT
     * loading, so a player stockpile can never exceed 999.
     */
    public static final double MAX_AMOUNT = 999.0D;

    private final Map<String, Stock> stocks = new LinkedHashMap<>();

    public static ResourceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ResourceData::load, ResourceData::new, NAME);
    }

    public static ResourceData load(CompoundTag tag) {
        ResourceData data = new ResourceData();
        ListTag playerTags = tag.getList("players", Tag.TAG_COMPOUND);
        for (Tag raw : playerTags) {
            CompoundTag entry = (CompoundTag) raw;
            String id = cleanPlayer(entry.getString("id"));
            if (!validPlayer(id)) {
                continue;
            }
            data.stocks.put(id, new Stock(normalize(entry.getDouble("ammo")), normalize(entry.getDouble("fuel"))));
        }
        if (!tag.getList("teams", Tag.TAG_COMPOUND).isEmpty()) {
            LOGGER.info("Ignoring the legacy team resource section: resources are per player now");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag playerTags = new ListTag();
        for (Map.Entry<String, Stock> entry : stocks.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey());
            value.putDouble("ammo", entry.getValue().ammo());
            value.putDouble("fuel", entry.getValue().fuel());
            playerTags.add(value);
        }
        tag.put("players", playerTags);
        return tag;
    }

    public Stock stock(String playerName) {
        Stock value = stocks.get(cleanPlayer(playerName));
        return value == null ? Stock.empty() : value;
    }

    public double amount(String playerName, ResourceKind kind) {
        return kind == null ? 0.0D : stock(playerName).get(kind);
    }

    public void setAmount(String playerName, ResourceKind kind, double value) {
        String clean = cleanPlayer(playerName);
        if (!validPlayer(clean) || kind == null) {
            return;
        }
        stocks.put(clean, stock(clean).with(kind, normalize(value)));
        setDirty();
    }

    public void addAmount(String playerName, ResourceKind kind, double delta) {
        String clean = cleanPlayer(playerName);
        if (!validPlayer(clean) || kind == null) {
            return;
        }
        stocks.put(clean, stock(clean).with(kind, normalize(stock(clean).get(kind) + delta)));
        setDirty();
    }

    /** Applies one settlement pass: per-player ammo/fuel deltas in a single dirty mark. */
    public void addStocksForPlayers(Map<String, Stock> gains) {
        if (gains.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Stock> gain : gains.entrySet()) {
            String clean = cleanPlayer(gain.getKey());
            if (!validPlayer(clean)) {
                continue;
            }
            Stock current = stock(clean);
            Stock delta = gain.getValue() == null ? Stock.empty() : gain.getValue();
            stocks.put(clean, new Stock(normalize(current.ammo() + delta.ammo()), normalize(current.fuel() + delta.fuel())));
        }
        setDirty();
    }

    public boolean spend(String playerName, ResourceKind kind, double cost) {
        String clean = cleanPlayer(playerName);
        double value = normalize(cost);
        if (!validPlayer(clean) || kind == null || value <= 0.0D) {
            return false;
        }
        double current = stock(clean).get(kind);
        if (current < value) {
            return false;
        }
        stocks.put(clean, stock(clean).with(kind, current - value));
        setDirty();
        return true;
    }

    public void clearAll() {
        if (stocks.isEmpty()) {
            return;
        }
        stocks.clear();
        setDirty();
    }

    public int size() {
        return stocks.size();
    }

    public Set<String> playerNames() {
        return Set.copyOf(stocks.keySet());
    }

    /** Clamps an amount into [0, MAX_AMOUNT]. */
    private static double normalize(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Math.min(value, MAX_AMOUNT);
    }

    private static String cleanPlayer(String playerName) {
        return TeamData.cleanPlayer(playerName);
    }

    private static boolean validPlayer(String playerName) {
        return playerName != null && !playerName.isBlank() && playerName.length() <= 64;
    }

    /** Immutable pair of resource amounts. */
    public record Stock(double ammo, double fuel) {
        public static Stock empty() {
            return new Stock(0.0D, 0.0D);
        }

        public double get(ResourceKind kind) {
            return kind == ResourceKind.FUEL ? fuel : ammo;
        }

        public Stock with(ResourceKind kind, double value) {
            return kind == ResourceKind.FUEL ? new Stock(ammo, value) : new Stock(value, fuel);
        }
    }
}
