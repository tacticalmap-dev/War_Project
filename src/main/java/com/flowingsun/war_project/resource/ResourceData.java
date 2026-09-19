package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.team.TeamData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persisted per-team stockpile of both resources (war_project_resources):
 * teams[] = {id, ammo, fuel}. Amounts are plain scalars; the game phase is not stored here.
 */
public final class ResourceData extends SavedData {
    private static final String NAME = "war_project_resources";

    /**
     * Hard ceiling for both resources. It applies to settlement, admin writes and NBT loading, so a
     * team stockpile can never exceed 999 even if an older save or a datapack said otherwise.
     */
    public static final double MAX_AMOUNT = 999.0D;

    private final Map<String, Stock> stocks = new LinkedHashMap<>();

    public static ResourceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ResourceData::load, ResourceData::new, NAME);
    }

    public static ResourceData load(CompoundTag tag) {
        ResourceData data = new ResourceData();
        ListTag teamTags = tag.getList("teams", Tag.TAG_COMPOUND);
        for (Tag raw : teamTags) {
            CompoundTag entry = (CompoundTag) raw;
            String id = TeamData.cleanId(entry.getString("id"));
            if (!TeamData.validId(id)) {
                continue;
            }
            // "amount" was the pre-split single-resource key: keep old saves usable as ammo.
            double ammo = normalize(entry.contains("ammo") ? entry.getDouble("ammo") : entry.getDouble("amount"));
            data.stocks.put(id, new Stock(ammo, normalize(entry.getDouble("fuel"))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag teamTags = new ListTag();
        for (Map.Entry<String, Stock> entry : stocks.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.getKey());
            value.putDouble("ammo", entry.getValue().ammo());
            value.putDouble("fuel", entry.getValue().fuel());
            teamTags.add(value);
        }
        tag.put("teams", teamTags);
        return tag;
    }

    public Stock stock(String teamId) {
        Stock value = stocks.get(TeamData.cleanId(teamId));
        return value == null ? Stock.empty() : value;
    }

    public double amount(String teamId, ResourceKind kind) {
        return kind == null ? 0.0D : stock(teamId).get(kind);
    }

    public void setAmount(String teamId, ResourceKind kind, double value) {
        String clean = TeamData.cleanId(teamId);
        if (!TeamData.validId(clean) || kind == null) {
            return;
        }
        stocks.put(clean, stock(clean).with(kind, normalize(value)));
        setDirty();
    }

    public void addAmount(String teamId, ResourceKind kind, double delta) {
        String clean = TeamData.cleanId(teamId);
        if (!TeamData.validId(clean) || kind == null) {
            return;
        }
        stocks.put(clean, stock(clean).with(kind, normalize(stock(clean).get(kind) + delta)));
        setDirty();
    }

    /** Applies one settlement pass: per-team ammo/fuel deltas in a single dirty mark. */
    public void addStocks(Map<String, Stock> gains) {
        if (gains.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Stock> gain : gains.entrySet()) {
            String clean = TeamData.cleanId(gain.getKey());
            if (!TeamData.validId(clean)) {
                continue;
            }
            Stock current = stock(clean);
            Stock delta = gain.getValue() == null ? Stock.empty() : gain.getValue();
            stocks.put(clean, new Stock(normalize(current.ammo() + delta.ammo()), normalize(current.fuel() + delta.fuel())));
        }
        setDirty();
    }

    public boolean spend(String teamId, ResourceKind kind, double cost) {
        String clean = TeamData.cleanId(teamId);
        double value = normalize(cost);
        if (!TeamData.validId(clean) || kind == null || value <= 0.0D) {
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

    public Set<String> teamIds() {
        return Set.copyOf(stocks.keySet());
    }

    /** Clamps an amount into [0, MAX_AMOUNT]; null-safe replacement for a plain bounds check. */
    private static double normalize(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Math.min(value, MAX_AMOUNT);
    }

    /** Immutable per-team pair of resource amounts. */
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
