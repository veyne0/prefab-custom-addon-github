package com.prefab.addon.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.network.BuildingManageActionPayload;
import com.prefab.addon.network.BuildingManageDataPayload;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器建筑管理界面 (仅 OP): 从「服务器」tab 的 [管理] 按钮进入。
 *
 * <p>两个标签页:
 * <ul>
 *   <li><b>建筑</b>: 卡片列出服务器 server-cache 里已上传的建筑, 带删除按钮</li>
 *   <li><b>玩家</b>: 列出玩家, 可开关其上传建筑权限 (玩家默认有权限)</li>
 * </ul>
 *
 * <p>数据流: 打开/操作后发 {@link BuildingManageActionPayload} → 服务端回
 * {@link BuildingManageDataPayload} → {@link #handleData} 重建列表。</p>
 */
public final class GuiServerManage {

    private static final List<BuildingManageDataPayload.BuildingFile> buildings = new ArrayList<>();
    private static final List<BuildingManageDataPayload.PlayerPerm> players = new ArrayList<>();

    private static String tab = "buildings";
    private static UIElement listContent;
    private static Label statusL;
    private static Button tabBuildingsBtn;
    private static Button tabPlayersBtn;
    /** 分页: 两个 tab 各自记忆页码, 每页 6 条 */
    private static final int PER_PAGE = 6;
    private static int pageBuildings;
    private static int pagePlayers;
    private static UIElement pagerRow;
    private static Label pagerLabel;
    /** 打开管理界面前的界面 (浏览器), 关闭时返回 */
    private static net.minecraft.client.gui.screens.Screen parentScreen;

    private GuiServerManage() {}

    /** 打开管理界面 (浏览器 [管理] 按钮入口), 仅 OP; 并向服务端请求最新数据 */
    public static void open() {
        // 只有 OP 才能打开 (客户端拦截, 服务端 handleAction 还有二次校验)
        var p = Minecraft.getInstance().player;
        if (p == null || !p.hasPermissions(2)) {
            if (p != null) {
                p.displayClientMessage(PrefabCustomAddon.trc("manage.err.op"), false);
            }
            return;
        }
        parentScreen = Minecraft.getInstance().screen;
        // tab / 分页页码 都是静态字段, 重开界面保留上次所在位置 (页码超界时 rebuildList 会钳制)
        buildings.clear();
        players.clear();
        ModularUI ui = ModularUI.of(UI.of(buildRoot(),
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        // ESC / 关闭都回到上一级界面 (建筑浏览器)
        ModularUIScreen screen = new ModularUIScreen(ui, Component.literal("服务器建筑管理")) {
            @Override
            public void onClose() {
                returnToParent();
            }
        };
        Minecraft.getInstance().setScreen(screen);
        requestList();
    }

    /** 请求服务端下发最新管理数据 */
    private static void requestList() {
        PacketDistributor.sendToServer(new BuildingManageActionPayload("list", "", ""));
    }

    private static void returnToParent() {
        net.minecraft.client.gui.screens.Screen parent = parentScreen;
        parentScreen = null;
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /** 收到服务端管理数据 (NetworkHandler 注册的下行回调) */
    public static void handleData(BuildingManageDataPayload payload) {
        buildings.clear();
        if (payload.buildings() != null) buildings.addAll(payload.buildings());
        players.clear();
        if (payload.players() != null) players.addAll(payload.players());
        Minecraft.getInstance().execute(() -> {
            if (listContent != null) rebuildList();
        });
    }

    // ------------------------------------------------------------------ 布局

    private static UIElement buildRoot() {
        UIElement root = new UIElement();
        root.layout(l -> l.width(340).height(250)
                .flexDirection(FlexDirection.COLUMN).paddingAll(6).gapAll(4));
        root.style(s -> s.background(Sprites.BORDER));
        root.setOverflowVisible(false);

        Label title = new Label();
        title.setText("§l服务器建筑管理", false);
        title.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER).textColor(0xFFFF55));
        title.layout(l -> l.widthPercent(100).height(12));
        root.addChild(title);

        // 标签行: [建筑] [玩家]
        UIElement tabs = new UIElement();
        tabs.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14).gapAll(4));
        root.addChild(tabs);
        String[] ids = {"buildings", "players"};
        String[] names = {"建筑", "玩家"};
        for (int i = 0; i < 2; i++) {
            final String id = ids[i];
            Button b = new Button().setText(names[i], false);
            b.layout(l -> l.widthPercent(50).height(14));
            b.setOnClick(e -> {
                tab = id;
                refreshTabButtons();
                rebuildList();
            });
            tabs.addChild(b);
            if (i == 0) tabBuildingsBtn = b;
            else tabPlayersBtn = b;
        }
        refreshTabButtons();

        // 列表 (滚动, 占满剩余高度)
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(s -> s.mode(ScrollerMode.VERTICAL));
        scroller.verticalScroller(v -> v.setScrollBarSize(6f));
        scroller.layout(l -> l.widthPercent(100).flexGrow(1).minHeight(0));
        root.addChild(scroller);
        listContent = new UIElement();
        listContent.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .paddingAll(2).gapAll(3));
        listContent.style(s -> s.background(Sprites.RECT_DARK));
        listContent.setOverflowVisible(false);
        scroller.addScrollViewChild(listContent);

        // 分页行: [◀] 第 x/y 页 [▶] (单页时隐藏)
        pagerRow = new UIElement();
        pagerRow.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(14)
                .alignItems(AlignItems.CENTER).gapAll(4));
        root.addChild(pagerRow);
        Button prev = new Button().setText("◀ 上一页", false);
        prev.textStyle(s -> s.textColor(0xBBBBBB));
        prev.layout(l -> l.flexGrow(1).height(14).minWidth(0));
        prev.setOnClick(e -> {
            if ("players".equals(tab)) {
                if (pagePlayers > 0) { pagePlayers--; rebuildList(); }
            } else if (pageBuildings > 0) { pageBuildings--; rebuildList(); }
        });
        pagerRow.addChild(prev);
        pagerLabel = new Label();
        pagerLabel.setText("", false);
        pagerLabel.textStyle(t -> t.textColor(0xFFFF88).textAlignHorizontal(Horizontal.CENTER));
        pagerLabel.layout(l -> l.width(70).height(12));
        pagerRow.addChild(pagerLabel);
        Button next = new Button().setText("下一页 ▶", false);
        next.textStyle(s -> s.textColor(0xBBBBBB));
        next.layout(l -> l.flexGrow(1).height(14).minWidth(0));
        next.setOnClick(e -> {
            if ("players".equals(tab)) {
                pagePlayers++;
            } else {
                pageBuildings++;
            }
            rebuildList(); // 超出末页时 clamp 会拉回来
        });
        pagerRow.addChild(next);

        // 状态行
        statusL = new Label();
        statusL.setText("", false);
        statusL.textStyle(t -> t.textColor(0xAAAAAA));
        statusL.layout(l -> l.widthPercent(100).height(10));
        root.addChild(statusL);

        // 底部: [刷新] [返回]
        UIElement bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(16).gapAll(4));
        root.addChild(bottom);
        Button refresh = new Button().setText("刷新", false);
        refresh.textStyle(s -> s.textColor(0x88BBFF));
        refresh.layout(l -> l.widthPercent(50).height(16));
        refresh.setOnClick(e -> {
            status("查询中...");
            requestList();
        });
        bottom.addChild(refresh);
        Button close = new Button().setText("返回", false);
        close.textStyle(s -> s.textColor(0xFFFFFF));
        close.layout(l -> l.widthPercent(50).height(16));
        close.setOnClick(e -> returnToParent());
        bottom.addChild(close);

        // 先按上次保留的 tab/页码渲染 (数据回来后 handleData 会再刷新)
        rebuildList();
        return root;
    }

    /** 切 tab 后刷新标签按钮的选中态 */
    private static void refreshTabButtons() {
        boolean bActive = "buildings".equals(tab);
        styleTabBtn(tabBuildingsBtn, bActive, "建筑");
        styleTabBtn(tabPlayersBtn, !bActive, "玩家");
    }

    private static void styleTabBtn(Button b, boolean active, String text) {
        if (b == null) return;
        b.setText(text, false);
        b.textStyle(s -> s.textColor(active ? 0xFFFF88 : 0xBBBBBB));
        b.buttonStyle(s -> s.baseTexture(active ? Sprites.RECT_LIGHT : Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_LIGHT).pressedTexture(Sprites.RECT_DARK));
    }

    /** 重建当前 tab 的列表内容 (分页: 每页 PER_PAGE 条) */
    private static void rebuildList() {
        listContent.clearAllChildren();
        boolean playersTab = "players".equals(tab);
        int total = playersTab ? players.size() : buildings.size();
        int totalPages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);

        // 页码钳制 + 刷新分页行
        if (playersTab) {
            pagePlayers = Math.max(0, Math.min(pagePlayers, totalPages - 1));
        } else {
            pageBuildings = Math.max(0, Math.min(pageBuildings, totalPages - 1));
        }
        int page = playersTab ? pagePlayers : pageBuildings;
        if (pagerRow != null) {
            pagerRow.setVisible(totalPages > 1);
        }
        if (pagerLabel != null) {
            pagerLabel.setText((page + 1) + " / " + totalPages, false);
        }

        if (total == 0) {
            addEmpty(playersTab ? "服务器上没有玩家" : "服务器上还没有上传的建筑");
            return;
        }
        int from = page * PER_PAGE;
        int to = Math.min(from + PER_PAGE, total);
        if (playersTab) {
            for (int i = from; i < to; i++) {
                listContent.addChild(playerRow(players.get(i)));
            }
        } else {
            for (int i = from; i < to; i++) {
                listContent.addChild(buildingRow(buildings.get(i)));
            }
        }
    }

    private static void addEmpty(String text) {
        Label empty = new Label();
        empty.setText(text, false);
        empty.textStyle(t -> t.textColor(0x888888)
                .textAlignHorizontal(Horizontal.CENTER));
        empty.layout(l -> l.widthPercent(100).height(14));
        listContent.addChild(empty);
    }

    /** 建筑卡片: [名称 + 大小] [删除] */
    private static UIElement buildingRow(BuildingManageDataPayload.BuildingFile f) {
        UIElement card = new UIElement();
        card.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(22)
                .alignItems(AlignItems.CENTER).gapAll(4));
        card.style(s -> s.background(Sprites.RECT_LIGHT));
        card.setOverflowVisible(false);

        UIElement info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1).minWidth(0).gapAll(1));
        card.addChild(info);
        Label name = new Label();
        name.setText(truncate(stripZip(f.name()), 30), false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.widthPercent(100).height(10));
        info.addChild(name);
        Label size = new Label();
        size.setText(humanSize(f.size()), false);
        size.textStyle(t -> t.textColor(0x888888));
        size.layout(l -> l.widthPercent(100).height(9));
        info.addChild(size);

        Button del = new Button().setText("删除", false);
        del.textStyle(s -> s.textColor(0xFF6666));
        del.buttonStyle(s -> s.baseTexture(Sprites.RECT_DARK)
                .hoverTexture(Sprites.RECT_DARK).pressedTexture(Sprites.RECT_DARK));
        del.layout(l -> l.width(36).height(16));
        del.setOnClick(e -> {
            status("删除中: " + stripZip(f.name()));
            PacketDistributor.sendToServer(
                    new BuildingManageActionPayload("delete", f.name(), ""));
        });
        card.addChild(del);
        return card;
    }

    /** 玩家行: [名称] [允许上传/禁止上传] */
    private static UIElement playerRow(BuildingManageDataPayload.PlayerPerm p) {
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).height(20)
                .alignItems(AlignItems.CENTER).gapAll(4));
        row.style(s -> s.background(Sprites.RECT_LIGHT));
        row.setOverflowVisible(false);

        Label name = new Label();
        String shown = p.name() == null
                ? "(离线) " + p.id().toString().substring(0, 8)
                : p.name();
        name.setText(truncate(shown, 26), false);
        name.textStyle(t -> t.textColor(0xFFFFFF));
        name.layout(l -> l.flexGrow(1).minWidth(0).height(10));
        row.addChild(name);

        Label state = new Label();
        state.setText(p.allowed() ? "可上传" : "已禁止", false);
        state.textStyle(t -> t.textColor(p.allowed() ? 0x55FF55 : 0xFF6666));
        state.layout(l -> l.width(40).height(10));
        row.addChild(state);

        // 按钮文字 = 点下去要生效的状态
        Button toggle = new Button().setText(p.allowed() ? "禁止上传" : "允许上传", false);
        toggle.textStyle(s -> s.textColor(p.allowed() ? 0xFF8888 : 0x88FF88));
        toggle.layout(l -> l.width(52).height(16));
        toggle.setOnClick(e -> {
            PacketDistributor.sendToServer(new BuildingManageActionPayload(
                    "perm", p.id().toString(), p.allowed() ? "false" : "true"));
        });
        row.addChild(toggle);
        return row;
    }

    private static void status(String text) {
        if (statusL == null) return;
        int color = text.startsWith("✓") ? 0x55FF55
                : text.startsWith("✗") ? 0xFF5555 : 0xAAAAAA;
        statusL.setText(text, false);
        statusL.textStyle(t -> t.textColor(color));
    }

    private static String stripZip(String name) {
        return name != null && name.endsWith(".zip")
                ? name.substring(0, name.length() - 4) : name;
    }

    private static String truncate(String s, int chars) {
        if (s == null) return "";
        return s.length() <= chars ? s : s.substring(0, Math.max(1, chars - 1)) + "…";
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
