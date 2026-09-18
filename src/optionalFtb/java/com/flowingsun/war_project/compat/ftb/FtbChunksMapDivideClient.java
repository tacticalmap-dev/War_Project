package com.flowingsun.war_project.compat.ftb;

import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.net.WarProjectNetwork;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.MapTileWidget;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.GuiHelper;
import dev.ftb.mods.ftblibrary.ui.IScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.ModalPanel;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.ScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.TextBox;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftblibrary.ui.WidgetType;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class FtbChunksMapDivideClient {
    private enum Mode {
        NONE,
        NODE,
        WARZONE
    }

    private static final int MAP_OVERLAY_Z = 350;
    private static final int TOOLBAR_Z = 360;
    private static final int INPUT_MODAL_Z = 1200;
    private static final int SELECT_FILL = 0x6043D17B;
    private static final int SELECT_EDGE = 0xFF43D17B;
    private static final int WARZONE_SELECT_FILL = 0x50FFD84A;
    private static final int WARZONE_SELECT_EDGE = 0xFFFFD84A;
    private static final int TOOLBAR_BG = 0xAA202225;
    private static final int TOOLBAR_ACTIVE = 0x803F88FF;
    private static final int TOOLBAR_LEFT = 20;
    private static final int TOOLBAR_MARGIN = 1;
    private static final int TOOLBAR_BUTTON_HEIGHT = 16;
    private static final int TOOLBAR_GAP = 2;
    private static final int[] DEFAULT_COLORS = {0x2E7DFF, 0x43D17B, 0xFFD84A, 0xFF7A45, 0xFF5FA2, 0x4FD6C8};

    private static final ToolbarOverlay TOOLBAR = new ToolbarOverlay();
    private static final Set<Long> nodeSelection = new LinkedHashSet<>();
    private static final Set<Long> warzoneSelection = new LinkedHashSet<>();
    private static Mode mode = Mode.NONE;
    private static PendingNode pendingNode;
    private static Runnable pendingPrompt;
    private static int pendingPromptDelayTicks;
    private static boolean draggingSelection;
    private static boolean dragAdding;
    private static boolean dragMoved;
    private static long dragLastChunk;

    private FtbChunksMapDivideClient() {
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearState();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingPrompt == null) {
            return;
        }
        if (pendingPromptDelayTicks > 0) {
            pendingPromptDelayTicks--;
            return;
        }
        Runnable prompt = pendingPrompt;
        pendingPrompt = null;
        prompt.run();
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Optional<LargeMapScreen> largeMap = largeMapScreen(event.getScreen());
        if (largeMap.isEmpty()) {
            return;
        }
        Optional<MapContext> context = MapContext.from(largeMap.get());
        context.ifPresent(mapContext -> {
            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().translate(0.0F, 0.0F, MAP_OVERLAY_Z);
            GuiHelper.setupDrawing();
            drawExisting(event.getGuiGraphics(), mapContext);
            drawSelection(event.getGuiGraphics(), mapContext);
            event.getGuiGraphics().pose().popPose();
        });
        TOOLBAR.renderOverlay(event.getGuiGraphics(), largeMap.get(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Optional<LargeMapScreen> largeMap = largeMapScreen(event.getScreen());
        if (largeMap.isEmpty()) {
            return;
        }
        if (TOOLBAR.mousePressedOnOverlay(largeMap.get(), event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }
        if (largeMap.get().getGui().anyModalPanelOpen()) {
            return;
        }
        Optional<MapContext> context = MapContext.from(largeMap.get());
        if (context.isEmpty() || !context.get().contains(event.getMouseX(), event.getMouseY())) {
            return;
        }
        if (event.getButton() == MouseButton.RIGHT.id) {
            if (mode == Mode.NONE) {
                // Not editing: leave right-click entirely to FTB Chunks.
                return;
            }
            ChunkPos chunk = context.get().chunkAt(event.getMouseX(), event.getMouseY());
            if (openEditMenu(largeMap.get(), chunk)) {
                event.setCanceled(true);
            }
            return;
        }
        if (event.getButton() != MouseButton.LEFT.id) {
            return;
        }
        if (mode != Mode.NODE && mode != Mode.WARZONE) {
            return;
        }
        if (Screen.hasShiftDown()) {
            // Shift + left drag pans the map; leave the event for FTB Chunks.
            return;
        }
        ChunkPos chunk = context.get().chunkAt(event.getMouseX(), event.getMouseY());
        beginDrag(chunk.toLong());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!draggingSelection || event.getMouseButton() != MouseButton.LEFT.id) {
            return;
        }
        Optional<LargeMapScreen> largeMap = largeMapScreen(event.getScreen());
        if (largeMap.isEmpty()) {
            return;
        }
        Optional<MapContext> context = MapContext.from(largeMap.get());
        if (context.isEmpty() || !context.get().contains(event.getMouseX(), event.getMouseY())) {
            return;
        }
        ChunkPos chunk = context.get().chunkAt(event.getMouseX(), event.getMouseY());
        updateDrag(chunk.toLong());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!draggingSelection || event.getButton() != MouseButton.LEFT.id) {
            return;
        }
        draggingSelection = false;
        Optional<LargeMapScreen> largeMap = largeMapScreen(event.getScreen());
        if (largeMap.isPresent() && dragMoved && !largeMap.get().getGui().anyModalPanelOpen()) {
            openSelectionMenu(largeMap.get());
        }
        event.setCanceled(true);
    }

    private static void beginDrag(long chunk) {
        Set<Long> selection = activeSelection();
        dragAdding = !selection.contains(chunk) || isProtectedWarzoneChunk(chunk);
        dragLastChunk = chunk;
        dragMoved = false;
        draggingSelection = true;
        setChunkSelected(chunk, dragAdding);
    }

    private static void updateDrag(long chunk) {
        if (chunk == dragLastChunk) {
            return;
        }
        for (long key : chunksOnLine(dragLastChunk, chunk)) {
            setChunkSelected(key, dragAdding);
        }
        dragLastChunk = chunk;
        dragMoved = true;
    }

    private static Set<Long> activeSelection() {
        return mode == Mode.WARZONE ? warzoneSelection : nodeSelection;
    }

    private static void setChunkSelected(long chunk, boolean selected) {
        if (!selected && isProtectedWarzoneChunk(chunk)) {
            return;
        }
        Set<Long> selection = activeSelection();
        if (selected) {
            selection.add(chunk);
        } else {
            selection.remove(chunk);
        }
    }

    private static boolean isProtectedWarzoneChunk(long chunk) {
        return mode == Mode.WARZONE && pendingNode != null && pendingNode.nodeChunks().contains(chunk);
    }

    /**
     * The Node button toggles node editing: pressing it while editing exits and clears the selection.
     */
    private static void toggleNodeEditing() {
        if (mode == Mode.NONE) {
            mode = Mode.NODE;
            pendingNode = null;
            warzoneSelection.clear();
            showClientMessage("Node editing on - drag to select chunks, Shift+drag to pan, right-click a node to edit it.");
            return;
        }
        clearState();
        showClientMessage("Node editing off.");
    }

    /**
     * Right-click menu for an existing node. Right-clicking a warzone resolves to the node it belongs
     * to, so both are the same action. Returns false when nothing is there, so the event is left for
     * FTB Chunks.
     */
    private static boolean openEditMenu(LargeMapScreen largeMap, ChunkPos chunk) {
        long chunkKey = chunk.toLong();
        Optional<ClientMapState.ClientNode> node = nodeAtChunk(chunkKey)
                .or(() -> warzoneAtChunk(chunkKey).flatMap(warzone -> nodeById(warzone.nodeId())));
        if (node.isEmpty()) {
            return false;
        }
        ClientMapState.ClientNode target = node.get();
        largeMap.openContextMenu(java.util.List.of(
                dev.ftb.mods.ftblibrary.ui.ContextMenuItem.title(Component.literal("Node: " + displayName(target.name(), target.id()))),
                dev.ftb.mods.ftblibrary.ui.ContextMenuItem.title(Component.literal("ID: " + target.id() + "  chunks: " + target.chunks().size())),
                new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Edit node"), Icons.ACCEPT, button -> openRenameNodePrompt(largeMap, target)),
                new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Delete node"), Icons.REMOVE, button -> WarProjectNetwork.sendDeleteNode(target.id())),
                new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Cancel"), Icons.CANCEL, button -> {
                })
        ));
        return true;
    }

    /**
     * Reopens the name/id prompt prefilled with the current values; confirming renames the node.
     */
    private static void openRenameNodePrompt(LargeMapScreen screen, ClientMapState.ClientNode node) {
        schedulePrompt(() -> {
            NameIdPromptOverlay overlay = new NameIdPromptOverlay(screen.getGui(), Component.literal("Edit Node"),
                    "Node Name", displayName(node.name(), node.id()),
                    "Node ID", node.id(), (accepted, name, id) -> {
                        if (!accepted) {
                            return;
                        }
                        WarProjectNetwork.sendRenameNode(node.id(), id, name);
                    }).atMousePosition();
            overlay.setExtraZlevel(INPUT_MODAL_Z);
            screen.getGui().pushModalPanel(overlay);
        });
    }

    private static Optional<ClientMapState.ClientNode> nodeAtChunk(long chunkKey) {
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            if (node.chunks().contains(chunkKey)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private static Optional<ClientMapState.ClientWarzone> warzoneAtChunk(long chunkKey) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            if (warzone.chunks().contains(chunkKey)) {
                return Optional.of(warzone);
            }
        }
        return Optional.empty();
    }

    private static Optional<ClientMapState.ClientNode> nodeById(String nodeId) {
        return Optional.ofNullable(ClientMapState.nodes().get(nodeId));
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private static void beginCreateNode(LargeMapScreen screen) {
        if (nodeSelection.isEmpty()) {
            showClientMessage("Select at least one node chunk first.");
            return;
        }
        String fallback = defaultNodeId();
        schedulePrompt(() -> openNodePrompt(screen, fallback));
    }

    private static void openNodePrompt(LargeMapScreen screen, String fallbackId) {
        NameIdPromptOverlay overlay = new NameIdPromptOverlay(screen.getGui(), Component.literal("Create Node"),
                "Node Name", fallbackId, "Node ID", fallbackId, (accepted, name, id) -> {
                    if (!accepted) {
                        return;
                    }
                    pendingNode = new PendingNode(id, name, new LinkedHashSet<>(nodeSelection), defaultColor());
                    warzoneSelection.clear();
                    warzoneSelection.addAll(nodeSelection);
                    mode = Mode.WARZONE;
                    showClientMessage("Select warzone chunks, then confirm warzone.");
                }).atMousePosition();
        overlay.setExtraZlevel(INPUT_MODAL_Z);
        screen.getGui().pushModalPanel(overlay);
    }

    private static void confirmWarzone() {
        if (pendingNode == null) {
            showClientMessage("Create a node before confirming warzone.");
            return;
        }
        if (!warzoneSelection.containsAll(pendingNode.nodeChunks())) {
            showClientMessage("Warzone must include all selected node chunks.");
            return;
        }
        Set<Long> extra = new LinkedHashSet<>(warzoneSelection);
        extra.removeAll(pendingNode.nodeChunks());
        if (extra.isEmpty()) {
            showClientMessage("Warzone needs at least one non-node chunk.");
            return;
        }
        WarProjectNetwork.sendCreateNodeWarzone(pendingNode.id(), pendingNode.name(), pendingNode.nodeChunks(), warzoneSelection, pendingNode.colorRgb());
        clearState();
    }

    private static void clearState() {
        mode = Mode.NONE;
        pendingNode = null;
        nodeSelection.clear();
        warzoneSelection.clear();
        pendingPrompt = null;
        pendingPromptDelayTicks = 0;
        draggingSelection = false;
        dragAdding = false;
        dragMoved = false;
        dragLastChunk = 0L;
    }

    private static void schedulePrompt(Runnable prompt) {
        pendingPrompt = prompt;
        pendingPromptDelayTicks = 1;
    }

    private static void drawExisting(GuiGraphics graphics, MapContext context) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            drawChunks(graphics, context, warzone.chunks(), rgba(warzone.colorRgb(), 0x38), rgba(warzone.colorRgb(), 0xA8), false);
        }
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            drawChunks(graphics, context, node.chunks(), rgba(node.colorRgb(), 0x78), rgba(node.colorRgb(), 0xFF), false);
        }
    }

    private static void drawSelection(GuiGraphics graphics, MapContext context) {
        drawChunks(graphics, context, nodeSelection, SELECT_FILL, SELECT_EDGE, true);
        drawChunks(graphics, context, warzoneSelection, WARZONE_SELECT_FILL, WARZONE_SELECT_EDGE, true);
    }

    private static void drawChunks(GuiGraphics graphics, MapContext context, Set<Long> chunks, int fill, int edge, boolean forceEdge) {
        for (long key : chunks) {
            ChunkPos chunk = new ChunkPos(key);
            Rect rect = context.rectForChunk(chunk.x, chunk.z);
            if (!rect.intersects(context.panelX, context.panelY, context.panelWidth, context.panelHeight)) {
                continue;
            }
            graphics.fill(rect.x1, rect.y1, rect.x2, rect.y2, fill);
            boolean west = forceEdge || !chunks.contains(ChunkPos.asLong(chunk.x - 1, chunk.z));
            boolean east = forceEdge || !chunks.contains(ChunkPos.asLong(chunk.x + 1, chunk.z));
            boolean north = forceEdge || !chunks.contains(ChunkPos.asLong(chunk.x, chunk.z - 1));
            boolean south = forceEdge || !chunks.contains(ChunkPos.asLong(chunk.x, chunk.z + 1));
            drawSolidEdges(graphics, rect, edge, west, east, north, south);
        }
    }

    private static void drawSolidEdges(GuiGraphics graphics, Rect rect, int color, boolean west, boolean east, boolean north, boolean south) {
        if (west) {
            graphics.fill(rect.x1, rect.y1, rect.x1 + 1, rect.y2, color);
        }
        if (east) {
            graphics.fill(rect.x2 - 1, rect.y1, rect.x2, rect.y2, color);
        }
        if (north) {
            graphics.fill(rect.x1, rect.y1, rect.x2, rect.y1 + 1, color);
        }
        if (south) {
            graphics.fill(rect.x1, rect.y2 - 1, rect.x2, rect.y2, color);
        }
    }

    private static void openSelectionMenu(LargeMapScreen largeMap) {
        if (mode == Mode.NODE) {
            largeMap.openContextMenu(java.util.List.of(
                    dev.ftb.mods.ftblibrary.ui.ContextMenuItem.title(Component.literal("Node selection: " + nodeSelection.size() + " chunks")),
                    new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Create node"), Icons.ACCEPT, button -> beginCreateNode(largeMap)),
                    new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Cancel"), Icons.CANCEL, button -> nodeSelection.clear())
            ));
        } else if (mode == Mode.WARZONE && pendingNode != null) {
            largeMap.openContextMenu(java.util.List.of(
                    dev.ftb.mods.ftblibrary.ui.ContextMenuItem.title(Component.literal("Warzone selection: " + warzoneSelection.size() + " chunks")),
                    dev.ftb.mods.ftblibrary.ui.ContextMenuItem.title(Component.literal("Node: " + pendingNode.id())),
                    new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Confirm warzone"), Icons.ACCEPT, button -> confirmWarzone()),
                    new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Reset to node chunks"), Icons.REMOVE, button -> {
                        warzoneSelection.clear();
                        warzoneSelection.addAll(pendingNode.nodeChunks());
                    }),
                    new dev.ftb.mods.ftblibrary.ui.ContextMenuItem(Component.literal("Cancel node"), Icons.CANCEL, button -> clearState())
            ));
        }
    }

    private static Optional<LargeMapScreen> largeMapScreen(Screen screen) {
        if (screen instanceof ScreenWrapper wrapper && wrapper.getGui() instanceof LargeMapScreen largeMap) {
            return Optional.of(largeMap);
        }
        if (screen instanceof IScreenWrapper wrapper && wrapper.getGui() instanceof LargeMapScreen largeMap) {
            return Optional.of(largeMap);
        }
        return Optional.empty();
    }

    private static String defaultNodeId() {
        int next = ClientMapState.nodes().size() + 1;
        while (ClientMapState.nodes().containsKey("node_" + next)) {
            next++;
        }
        return "node_" + next;
    }

    private static int defaultColor() {
        return DEFAULT_COLORS[ClientMapState.nodes().size() % DEFAULT_COLORS.length];
    }

    private static int rgba(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    private static Set<Long> chunksOnLine(long first, long second) {
        ChunkPos a = new ChunkPos(first);
        ChunkPos b = new ChunkPos(second);
        int dx = b.x - a.x;
        int dz = b.z - a.z;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        Set<Long> chunks = new LinkedHashSet<>();
        if (steps == 0) {
            chunks.add(first);
            return chunks;
        }
        for (int i = 0; i <= steps; i++) {
            int x = a.x + Math.round(dx * (i / (float) steps));
            int z = a.z + Math.round(dz * (i / (float) steps));
            chunks.add(ChunkPos.asLong(x, z));
        }
        return chunks;
    }

    private static void showClientMessage(String message) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
    }

    @FunctionalInterface
    private interface NameIdCallback {
        void accept(boolean accepted, String name, String id);
    }

    private record PendingNode(String id, String name, Set<Long> nodeChunks, int colorRgb) {
    }

    private record Rect(int x1, int y1, int x2, int y2) {
        boolean intersects(int x, int y, int width, int height) {
            return x2 >= x && x1 <= x + width && y2 >= y && y1 <= y + height;
        }
    }

    private static final class MapContext {
        private final int panelX;
        private final int panelY;
        private final int panelWidth;
        private final int panelHeight;
        private final int regionMinX;
        private final int regionMinZ;
        private final int tileSize;
        private final double scrollX;
        private final double scrollY;

        private MapContext(int panelX, int panelY, int panelWidth, int panelHeight, int regionMinX, int regionMinZ, int tileSize, double scrollX, double scrollY) {
            this.panelX = panelX;
            this.panelY = panelY;
            this.panelWidth = panelWidth;
            this.panelHeight = panelHeight;
            this.regionMinX = regionMinX;
            this.regionMinZ = regionMinZ;
            this.tileSize = tileSize;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
        }

        static Optional<MapContext> from(LargeMapScreen screen) {
            Optional<RegionMapPanel> panelOpt = findRegionPanel(screen);
            if (panelOpt.isEmpty()) {
                return Optional.empty();
            }
            RegionMapPanel panel = panelOpt.get();
            int regionMinX = readIntField(panel, "regionMinX").orElseGet(() -> minTileRegion(panel, true));
            int regionMinZ = readIntField(panel, "regionMinZ").orElseGet(() -> minTileRegion(panel, false));
            return Optional.of(new MapContext(panel.getX(), panel.getY(), panel.width, panel.height, regionMinX, regionMinZ,
                    screen.getRegionTileSize(), panel.getScrollX(), panel.getScrollY()));
        }

        private static Optional<RegionMapPanel> findRegionPanel(Panel panel) {
            for (Widget widget : panel.getWidgets()) {
                if (widget instanceof RegionMapPanel regionPanel) {
                    return Optional.of(regionPanel);
                }
                if (widget instanceof Panel child) {
                    Optional<RegionMapPanel> nested = findRegionPanel(child);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            }
            return Optional.empty();
        }

        private static Optional<Integer> readIntField(Object target, String name) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return Optional.of(field.getInt(target));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static int minTileRegion(RegionMapPanel panel, boolean xAxis) {
            int min = Integer.MAX_VALUE;
            for (Widget widget : panel.getWidgets()) {
                if (widget instanceof MapTileWidget tile) {
                    min = Math.min(min, xAxis ? tile.region.pos.x() : tile.region.pos.z());
                }
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= panelX && mouseX < panelX + panelWidth && mouseY >= panelY && mouseY < panelY + panelHeight;
        }

        ChunkPos chunkAt(double mouseX, double mouseY) {
            double regionX = (mouseX + scrollX - panelX) / tileSize + regionMinX;
            double regionZ = (mouseY + scrollY - panelY) / tileSize + regionMinZ;
            int blockX = (int) Math.floor(regionX * 512.0D);
            int blockZ = (int) Math.floor(regionZ * 512.0D);
            return new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        }

        Rect rectForChunk(int chunkX, int chunkZ) {
            double regionX = chunkX / 32.0D;
            double regionZ = chunkZ / 32.0D;
            double px = panelX + (regionX - regionMinX) * tileSize - scrollX;
            double py = panelY + (regionZ - regionMinZ) * tileSize - scrollY;
            int size = Math.max(2, (int) Math.ceil(tileSize / 32.0D));
            return new Rect((int) Math.floor(px), (int) Math.floor(py), (int) Math.ceil(px) + size, (int) Math.ceil(py) + size);
        }
    }

    private static final class NameIdPromptOverlay extends ModalPanel {
        private static final int WIDTH = 190;
        private static final int HEIGHT = 104;
        private final Component title;
        private final String nameLabel;
        private final String idLabel;
        private final NameIdCallback callback;
        private final TextBox nameBox;
        private final TextBox idBox;
        private SimpleTextButton acceptButton;
        private SimpleTextButton cancelButton;
        private boolean closed;

        private NameIdPromptOverlay(Panel parent, Component title, String nameLabel, String nameFallback, String idLabel, String idFallback, NameIdCallback callback) {
            super(parent);
            this.title = title;
            this.nameLabel = nameLabel;
            this.idLabel = idLabel;
            this.callback = callback;
            setSize(WIDTH, HEIGHT);
            nameBox = new TextBox(this);
            nameBox.setMaxLength(64);
            nameBox.setText(nameFallback);
            idBox = new TextBox(this);
            idBox.setMaxLength(64);
            idBox.setText(idFallback);
        }

        private NameIdPromptOverlay atMousePosition() {
            setPos(getMouseX(), getMouseY());
            return this;
        }

        @Override
        public void addWidgets() {
            add(nameBox);
            add(idBox);
            acceptButton = SimpleTextButton.accept(this, button -> submit(true));
            cancelButton = SimpleTextButton.cancel(this, button -> submit(false));
            add(acceptButton);
            add(cancelButton);
        }

        @Override
        public void alignWidgets() {
            nameBox.setPosAndSize(8, 25, WIDTH - 16, 14);
            idBox.setPosAndSize(8, 55, WIDTH - 16, 14);
            acceptButton.setPosAndSize(8, 80, 84, 16);
            cancelButton.setPosAndSize(WIDTH - 92, 80, 84, 16);
        }

        @Override
        public boolean keyPressed(Key key) {
            if (key.enter()) {
                submit(true);
                return true;
            }
            if (key.esc()) {
                submit(false);
                return true;
            }
            return super.keyPressed(key);
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            theme.drawPanelBackground(graphics, x, y, w, h);
            theme.drawString(graphics, title, x + 8, y + 6, Color4I.WHITE, Theme.SHADOW);
            theme.drawString(graphics, nameLabel, x + 8, y + 17, Color4I.WHITE.withAlpha(220), Theme.SHADOW);
            theme.drawString(graphics, idLabel, x + 8, y + 47, Color4I.WHITE.withAlpha(220), Theme.SHADOW);
        }

        private void submit(boolean accepted) {
            if (closed) {
                return;
            }
            closed = true;
            getGui().closeModalPanel(this);
            callback.accept(accepted, nameBox.getText(), idBox.getText());
        }
    }

    private static final class ToolbarOverlay extends BaseScreen {
        private LargeMapScreen largeMap;
        private int layoutX;

        @Override
        public int getX() {
            return TOOLBAR_LEFT;
        }

        @Override
        public int getY() {
            return TOOLBAR_MARGIN;
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        public boolean usePreviousScreenOnBack() {
            return false;
        }

        @Override
        public boolean drawDefaultBackground(GuiGraphics graphics) {
            return false;
        }

        void renderOverlay(GuiGraphics graphics, LargeMapScreen screen, int mouseX, int mouseY, float partialTicks) {
            configure(screen);
            updateGui(mouseX, mouseY, partialTicks);
            Theme theme = getTheme();
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, TOOLBAR_Z);
            GuiHelper.setupDrawing();
            draw(graphics, theme, getX(), getY(), width, height);
            drawForeground(graphics, theme, getX(), getY(), width, height);
            graphics.pose().popPose();
        }

        boolean mousePressedOnOverlay(LargeMapScreen screen, double mouseX, double mouseY) {
            configure(screen);
            int x = (int) mouseX;
            int y = (int) mouseY;
            if (x < getX() || y < getY() || x >= getX() + width || y >= getY() + height) {
                return false;
            }
            for (Widget widget : getWidgets()) {
                if (x >= widget.getX() && x < widget.getX() + widget.width && y >= widget.getY() && y < widget.getY() + widget.height) {
                    if (widget instanceof ToolButton toolButton) {
                        toolButton.runAction();
                        return true;
                    }
                }
            }
            return false;
        }

        private void configure(LargeMapScreen screen) {
            largeMap = screen;
            setSize(toolbarWidth(), TOOLBAR_BUTTON_HEIGHT + 2);
            refreshWidgets();
        }

        private int toolbarWidth() {
            int width = buttonWidth("Node", 72);
            if (mode == Mode.NODE && !nodeSelection.isEmpty()) {
                width += TOOLBAR_GAP + buttonWidth("Create Node", 92);
            }
            if (mode == Mode.WARZONE && pendingNode != null) {
                width += TOOLBAR_GAP + buttonWidth("Confirm Warzone", 120);
            }
            return width + 4;
        }

        @Override
        public void addWidgets() {
            layoutX = 2;
            addToolButton("Node", 72, Icons.MAP, () -> mode != Mode.NONE, FtbChunksMapDivideClient::toggleNodeEditing);
            if (mode == Mode.NODE && !nodeSelection.isEmpty()) {
                addToolButton("Create Node", 92, Icons.ACCEPT, () -> false, () -> beginCreateNode(largeMap));
            }
            if (mode == Mode.WARZONE && pendingNode != null) {
                addToolButton("Confirm Warzone", 120, Icons.ACCEPT, () -> false, FtbChunksMapDivideClient::confirmWarzone);
            }
        }

        @Override
        public void alignWidgets() {
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            Color4I.rgba(TOOLBAR_BG).draw(graphics, x, y, w, h);
        }

        private ToolButton addToolButton(String label, int minWidth, Icon icon, BooleanSupplier active, Runnable action) {
            int buttonWidth = buttonWidth(label, minWidth);
            ToolButton button = new ToolButton(this, label, icon, active, action);
            button.setPosAndSize(layoutX, 1, buttonWidth, TOOLBAR_BUTTON_HEIGHT);
            add(button);
            layoutX += buttonWidth + TOOLBAR_GAP;
            return button;
        }

        private int buttonWidth(String label, int minWidth) {
            return Math.max(minWidth, getTheme().getStringWidth(label) + 22);
        }
    }

    private static final class ToolButton extends SimpleTextButton {
        private final BooleanSupplier active;
        private final Runnable action;

        private ToolButton(Panel panel, String label, Icon icon, BooleanSupplier active, Runnable action) {
            super(panel, Component.literal(label), icon);
            this.active = active;
            this.action = action;
        }

        @Override
        public WidgetType getWidgetType() {
            return active.getAsBoolean() || isMouseOver() ? WidgetType.MOUSE_OVER : WidgetType.NORMAL;
        }

        @Override
        public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            Icons.BLUE_BUTTON.draw(graphics, x, y, w, h);
            if (active.getAsBoolean()) {
                Color4I.rgba(TOOLBAR_ACTIVE).draw(graphics, x, y, w, h);
            } else if (isMouseOver()) {
                Color4I.WHITE.withAlpha(35).draw(graphics, x, y, w, h);
            }
        }

        @Override
        public void onClicked(MouseButton button) {
            if (button.isLeft()) {
                runAction();
            }
        }

        private void runAction() {
            action.run();
            playClickSound();
        }
    }
}
