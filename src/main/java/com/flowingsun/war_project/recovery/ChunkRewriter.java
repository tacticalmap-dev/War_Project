package com.flowingsun.war_project.recovery;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * Puts one backed-up chunk back into a chunk that is currently loaded.
 *
 * <p>Only blocks that actually differ are written, so a mostly intact chunk costs one pass over its
 * sections and nothing else. The update flags sync the client but suppress neighbour cascades and
 * drops, because a rollback must not trigger gameplay of its own.</p>
 */
final class ChunkRewriter {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private ChunkRewriter() {
    }

    static int rewrite(ServerLevel level, LevelChunk live, CompoundTag backupTag, ChunkPos pos) {
        ProtoChunk backup = ChunkSerializer.read(level, level.getPoiManager(), pos, backupTag);
        LevelChunkSection[] wanted = backup.getSections();
        LevelChunkSection[] current = live.getSections();
        int minSection = level.getMinSection();
        int sections = Math.min(wanted.length, current.length);
        int changed = 0;
        for (int index = 0; index < sections; index++) {
            LevelChunkSection want = wanted[index];
            LevelChunkSection have = current[index];
            if (want == null || have == null || (want.hasOnlyAir() && have.hasOnlyAir())) {
                continue;
            }
            int baseY = (minSection + index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState target = want.getBlockState(x, y, z);
                        if (have.getBlockState(x, y, z) == target) {
                            continue;
                        }
                        level.setBlock(new BlockPos(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z),
                                target, UPDATE_FLAGS);
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) {
            live.setUnsaved(true);
        }
        return changed;
    }
}
