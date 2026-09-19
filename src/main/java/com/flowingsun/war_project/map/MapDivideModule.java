package com.flowingsun.war_project.map;

import com.flowingsun.war_project.module.WarProjectModule;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

public final class MapDivideModule implements WarProjectModule {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "mapdivide";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        MapData.get(server).setDirty();
        // Out-of-map enforcement is part of the map module: the area lives in MapData.
        MinecraftForge.EVENT_BUS.register(MapBoundaryService.active());
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        MinecraftForge.EVENT_BUS.unregister(MapBoundaryService.active());
        MapBoundaryService.clearActive();
    }

    @Override
    public void onClientSetup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("ftblibrary") && ModList.get().isLoaded("ftbchunks")) {
            LOGGER.info("FTB map editor support is available for War Project");
            event.enqueueWork(this::registerFtbMapEditor);
        } else {
            LOGGER.info("FTB map editor support disabled because optional FTB mods are missing");
        }
    }

    private void registerFtbMapEditor() {
        try {
            Class<?> editorClass = Class.forName("com.flowingsun.war_project.compat.ftb.FtbChunksMapDivideClient");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(editorClass);
            LOGGER.info("Registered War Project FTB map editor");
        } catch (ClassNotFoundException exception) {
            LOGGER.info("War Project FTB map editor class is not present in this build");
        }
    }
}
