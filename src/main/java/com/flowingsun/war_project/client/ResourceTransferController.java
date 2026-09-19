package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.html.HtmlDocument;
import com.flowingsun.war_project.html.HtmlLayout;
import com.flowingsun.war_project.html.HtmlNode;
import com.flowingsun.war_project.html.HtmlViewHost;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.resource.ResourceData;
import com.flowingsun.war_project.resource.ResourceKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The transfer panel: right-click a resource icon on the island while the chat screen is open, pick
 * a teammate, type an amount and confirm. Rendering goes through the HTML kernel; the numeric entry
 * borrows vanilla's EditBox so the caret, selection and paste behave exactly like the chat box.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, value = Dist.CLIENT)
public final class ResourceTransferController {
    private static final String PATH = "html/transfer_panel.html";
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_OFFSET = 6;

    private static final String FALLBACK = buildFallback();

    private static HtmlViewHost host;
    private static EditBox amountBox;
    private static ResourceKind kind = ResourceKind.AMMO;
    private static final List<Member> roster = new ArrayList<>();
    private static String selected;
    private static int scrollOffset;
    private static boolean open;
    private static boolean pending;
    private static String notice = "";
    private static boolean noticeOk;
    private static long pendingSince;
    private static long closeAt;
    private static long lastNanos;
    private static int panelX = 40;
    private static int panelY = 40;

    private ResourceTransferController() {
    }

    private record Member(String name, boolean online, double ammo, double fuel) {
    }

    private static String buildFallback() {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"panel\" class=\"panel\">");
        html.append("<div id=\"title\" class=\"title\">Transfer</div><div class=\"roster\">");
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            html.append("<div id=\"row").append(i).append("\" class=\"row\">")
                    .append("<span id=\"row").append(i).append("-name\" class=\"pname\"></span>")
                    .append("<span id=\"row").append(i).append("-ammo\" class=\"pval\"></span>")
                    .append("<span id=\"row").append(i).append("-fuel\" class=\"pval\"></span>")
                    .append("</div>");
        }
        html.append("</div><div class=\"entry\"><span class=\"label\">Amount</span>")
                .append("<div id=\"inputbox\" class=\"inputbox\"></div></div>")
                .append("<div class=\"actions\"><div id=\"btn-confirm\" class=\"button primary\">Confirm</div>")
                .append("<div id=\"btn-cancel\" class=\"button\">Cancel</div></div>")
                .append("<div id=\"result\" class=\"result\"></div></div>");
        html.append("<style>");
        html.append(".panel { display:flex; flex-direction:column; gap:6px; width:224px; background-color:#12161df2; "
                + "border-radius:10px; padding:10px; box-shadow:3px 3px 6px #00000080; opacity:0; "
                + "transform:scale(0.94); transition:160ms ease-out; }");
        html.append(".panel.open { opacity:1; transform:scale(1); }");
        html.append(".title { color:#ffffff; }");
        html.append(".roster { display:flex; flex-direction:column; gap:2px; }");
        html.append(".row { display:flex; flex-direction:row; align-items:center; gap:6px; padding:3px 6px; border-radius:6px; }");
        html.append(".row.hidden { display:none; }");
        html.append(".row.offline { opacity:0.45; }");
        html.append(".row:hover { background-color:#2a3444; }");
        html.append(".row.selected { background-color:#1f3a5f; }");
        html.append(".pname { color:#ffffff; }");
        html.append(".pval { color:#9ecbff; }");
        html.append(".entry { display:flex; flex-direction:row; align-items:center; gap:6px; }");
        html.append(".label { color:#9aa4af; }");
        html.append(".inputbox { width:70px; height:14px; background-color:#080b10ff; border-radius:4px; }");
        html.append(".actions { display:flex; flex-direction:row; align-items:center; gap:6px; }");
        html.append(".button { color:#ffffff; background-color:#232a34; border-radius:6px; padding:3px 10px; }");
        html.append(".button:hover { background-color:#2f3a48; }");
        html.append(".button.disabled { opacity:0.55; }");
        html.append(".button.primary { background-color:#1f6feb; }");
        html.append(".button.primary:hover { background-color:#2b7ffb; }");
        html.append(".result { color:#9aa4af; }");
        html.append(".result.ok { color:#7ce38b; }");
        html.append(".result.fail { color:#ff8f8f; }");
        html.append("</style>");
        return html.toString();
    }

    public static boolean isOpen() {
        return open;
    }

    /** Client-side state hook used by ResourceClientState is not needed; kept for tests/debug. */
    public static void onResult(boolean ok, String message) {
        pending = false;
        noticeOk = ok;
        notice = message == null ? "" : message;
        if (ok) {
            // Show the verdict for a moment, then fade the panel out.
            closeAt = System.currentTimeMillis() + 500L;
        }
        if (open) {
            updateNodes();
        }
    }

    private static HtmlViewHost host() {
        if (host == null) {
            host = new HtmlViewHost(HtmlDocument.parse(HtmlResources.load(PATH, FALLBACK)));
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int index = i;
                host.setOnClick("row" + i, () -> selectRow(index));
            }
            host.setOnClick("btn-confirm", ResourceTransferController::confirm);
            host.setOnClick("btn-cancel", ResourceTransferController::close);
        }
        return host;
    }

    private static EditBox amountBox() {
        if (amountBox == null) {
            Minecraft minecraft = Minecraft.getInstance();
            amountBox = new EditBox(minecraft.font, 0, 0, 60, 14, Component.empty());
            amountBox.setMaxLength(4);
            amountBox.setFilter(value -> value.chars().allMatch(Character::isDigit));
            amountBox.setValue("1");
            amountBox.setCanLoseFocus(false);
            amountBox.setFocused(true);
        }
        return amountBox;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        float deltaMs = lastNanos == 0L ? 16.0F : Math.min(120.0F, (now - lastNanos) / 1_000_000.0F);
        lastNanos = now;
        if (minecraft.player == null) {
            close();
            return;
        }
        ResourceIslandView.tick(deltaMs);
        host().tick(deltaMs);
        if (closeAt > 0L && System.currentTimeMillis() >= closeAt) {
            close();
            return;
        }
        if (pending && System.currentTimeMillis() - pendingSince > 3000L) {
            pending = false;
            notice = "No response from the server.";
            noticeOk = false;
        }
        if (open) {
            refreshRoster();
            amountBox().tick();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
        ResourceIslandView.reset();
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        ResourceIslandView.render(graphics, screenWidth);
        if (!open) {
            return;
        }
        HtmlViewHost view = host();
        HtmlLayout.apply(view.document(), minecraft.font, panelX, panelY, screenWidth);
        int width = view.document().root.width;
        int height = view.document().root.height;
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int x = Math.max(2, Math.min(panelX, screenWidth - width - 2));
        int y = Math.max(2, Math.min(panelY, screenHeight - height - 2));
        panelX = x;
        panelY = y;
        view.render(graphics, minecraft.font, x, y, screenWidth);
        HtmlNode box = view.node("inputbox");
        if (box != null) {
            EditBox edit = amountBox();
            edit.setX(box.x + 2);
            edit.setY(box.y + 1);
            edit.setWidth(Math.max(10, box.width - 4));
            edit.setHeight(Math.max(8, box.height - 2));
            edit.render(graphics, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.screen instanceof ChatScreen)) {
            return;
        }
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        if (open) {
            if (event.getButton() == 0) {
                if (host().contains(mouseX, mouseY)) {
                    host().mousePressed(mouseX, mouseY, 0);
                } else {
                    close();
                }
                event.setCanceled(true);
            }
            return;
        }
        if (event.getButton() == 1) {
            ResourceKind icon = ResourceIslandView.iconAt(mouseX, mouseY);
            if (icon != null) {
                openPanel(icon, mouseX, mouseY);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!open || event.getButton() != 0) {
            return;
        }
        if (host().contains(event.getMouseX(), event.getMouseY())) {
            host().mouseReleased(event.getMouseX(), event.getMouseY(), 0);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!open || !host().contains(event.getMouseX(), event.getMouseY())) {
            return;
        }
        int maxOffset = Math.max(0, roster.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(event.getScrollDelta())));
        updateNodes();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!open) {
            return;
        }
        int key = event.getKeyCode();
        if (key == 256) {
            close();
            event.setCanceled(true);
            return;
        }
        if (key == 257 || key == 335) {
            confirm();
            event.setCanceled(true);
            return;
        }
        if (amountBox().keyPressed(key, event.getScanCode(), event.getModifiers())) {
            updateNodes();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!open) {
            return;
        }
        if (amountBox().charTyped(event.getCodePoint(), event.getModifiers())) {
            updateNodes();
            event.setCanceled(true);
        }
    }

    private static void openPanel(ResourceKind resourceKind, double mouseX, double mouseY) {
        kind = resourceKind;
        selected = null;
        scrollOffset = 0;
        pending = false;
        notice = "";
        noticeOk = false;
        open = true;
        panelX = (int) mouseX + PANEL_OFFSET;
        panelY = (int) mouseY + PANEL_OFFSET;
        HtmlNode panel = host().node("panel");
        if (panel != null) {
            panel.setClass("open", true);
        }
        HtmlNode title = host().node("title");
        if (title != null) {
            title.text = "Transfer " + (kind == ResourceKind.FUEL ? "Fuel" : "Ammo");
        }
        amountBox().setValue("1");
        amountBox().setFocused(true);
        refreshRoster();
    }

    private static void close() {
        open = false;
        pending = false;
        closeAt = 0L;
        selected = null;
        if (host != null) {
            HtmlNode panel = host.node("panel");
            if (panel != null) {
                panel.setClass("open", false);
            }
        }
        if (amountBox != null) {
            amountBox.setFocused(false);
        }
    }

    private static void refreshRoster() {
        Set<String> online = onlinePlayerNames();
        List<Member> fresh = new ArrayList<>();
        for (WarProjectNetwork.TeamMemberEntry entry : ResourceClientState.teammates()) {
            fresh.add(new Member(entry.name(), online.contains(entry.name()), entry.ammo(), entry.fuel()));
        }
        fresh.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        boolean changed = fresh.size() != roster.size();
        if (!changed) {
            for (int i = 0; i < fresh.size(); i++) {
                Member left = roster.get(i);
                Member right = fresh.get(i);
                if (!left.name().equals(right.name()) || left.online() != right.online()
                        || left.ammo() != right.ammo() || left.fuel() != right.fuel()) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            roster.clear();
            roster.addAll(fresh);
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, roster.size() - VISIBLE_ROWS)));
        if (selected != null) {
            Member member = selectedMember();
            if (member == null || !member.online()) {
                selected = null;
            }
        }
        updateNodes();
    }

    private static void updateNodes() {
        HtmlViewHost view = host();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            HtmlNode row = view.node("row" + i);
            if (row == null) {
                continue;
            }
            int index = scrollOffset + i;
            if (index >= roster.size()) {
                row.setClass("hidden", true);
                continue;
            }
            Member member = roster.get(index);
            row.setClass("hidden", false);
            row.setClass("offline", !member.online());
            row.setClass("selected", member.name().equals(selected));
            setText(view, "row" + i + "-name", member.name());
            setText(view, "row" + i + "-ammo", "A " + ResourceIslandView.amountText(member.ammo()));
            setText(view, "row" + i + "-fuel", "F " + ResourceIslandView.amountText(member.fuel()));
        }
        HtmlNode result = view.node("result");
        if (result != null) {
            setText(view, "result", pending ? "Sending..." : notice);
            result.setClass("ok", !pending && noticeOk);
            result.setClass("fail", !pending && !notice.isEmpty() && !noticeOk);
        }
        HtmlNode confirm = view.node("btn-confirm");
        if (confirm != null) {
            confirm.disabled = !canConfirm();
            confirm.setClass("disabled", confirm.disabled);
        }
    }

    private static void selectRow(int visibleIndex) {
        int index = scrollOffset + visibleIndex;
        if (index >= roster.size()) {
            return;
        }
        Member member = roster.get(index);
        if (!member.online()) {
            notice = member.name() + " is offline.";
            noticeOk = false;
            updateNodes();
            return;
        }
        selected = member.name();
        notice = "";
        noticeOk = false;
        updateNodes();
    }

    private static Member selectedMember() {
        if (selected == null) {
            return null;
        }
        for (Member member : roster) {
            if (member.name().equals(selected)) {
                return member;
            }
        }
        return null;
    }

    private static double requestedAmount() {
        try {
            return Double.parseDouble(amountBox().getValue().trim());
        } catch (NumberFormatException exception) {
            return -1.0D;
        }
    }

    private static boolean canConfirm() {
        Member member = selectedMember();
        if (pending || member == null || !member.online()) {
            return false;
        }
        double amount = requestedAmount();
        if (amount <= 0.0D || amount > ResourceClientState.amount(kind)) {
            return false;
        }
        double held = kind == ResourceKind.FUEL ? member.fuel() : member.ammo();
        return amount <= ResourceData.MAX_AMOUNT - held;
    }

    private static void confirm() {
        if (!canConfirm()) {
            Member member = selectedMember();
            double amount = requestedAmount();
            if (member == null) {
                notice = "Select a teammate first.";
            } else if (amount <= 0.0D) {
                notice = "Enter a valid amount.";
            } else if (amount > ResourceClientState.amount(kind)) {
                notice = "Not enough " + kind.id() + ".";
            } else {
                notice = member.name() + " cannot receive that much.";
            }
            noticeOk = false;
            updateNodes();
            return;
        }
        pending = true;
        pendingSince = System.currentTimeMillis();
        notice = "";
        noticeOk = false;
        WarProjectNetwork.sendTransfer(selected, kind.id(), requestedAmount());
        updateNodes();
    }

    private static Set<String> onlinePlayerNames() {
        Set<String> names = new LinkedHashSet<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                names.add(info.getProfile().getName());
            }
        }
        return names;
    }

    private static void setText(HtmlViewHost view, String id, String text) {
        HtmlNode node = view.node(id);
        if (node != null && !text.equals(node.text)) {
            node.text = text;
            view.markLayoutDirty();
        }
    }
}
