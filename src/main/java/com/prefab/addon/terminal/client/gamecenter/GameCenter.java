/*
 * 游戏中心软件 - ModernTerminal 应用 tab.
 * 游戏本体移植自 GameDiscs 模组 (MIT, (c) 2024 Tejty), 见 games 包内声明.
 * 结构: 左侧游戏列表 (ScrollerView) + 右侧 140x100 游戏画布 (GameCanvasElement)
 *       + 操作提示 + 最高分显示. 键盘输入由画布转发给虚拟手柄.
 */
package com.prefab.addon.terminal.client.gamecenter;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.prefab.addon.terminal.TerminalRegistry;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.BlocktrisGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.FlappyBirdGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.FroggieGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.PongGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.RabbitGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.SlimeGame;
import com.prefab.addon.terminal.client.gamecenter.games.gamediscs.TntSweeperGame;
import com.prefab.addon.terminal.client.gamecenter.games.util.Game;
import com.prefab.addon.terminal.client.gui.TerminalGui;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;
import java.util.function.Supplier;

/** 游戏中心: 选择游戏 → 画布运行. 仅客户端. */
@EventBusSubscriber(modid = TerminalRegistry.HOST_MOD_ID, value = Dist.CLIENT)
public final class GameCenter {
    /** 游戏条目 (每次进入新建实例, 避免复用残留状态). */
    private record GameEntry(String nameKey, Supplier<Game> factory) {
        Game newGame() {
            return factory.get();
        }
    }

    private static final List<GameEntry> GAMES = List.of(
            new GameEntry("gamediscs.pong", PongGame::new),
            new GameEntry("gamediscs.blocktris", BlocktrisGame::new),
            new GameEntry("gamediscs.tnt_sweeper", TntSweeperGame::new),
            new GameEntry("gamediscs.flappy_bird", FlappyBirdGame::new),
            new GameEntry("gamediscs.froggie", FroggieGame::new),
            new GameEntry("gamediscs.rabbit", RabbitGame::new),
            new GameEntry("gamediscs.slime", SlimeGame::new));

    /** 浏览状态快照 (客户端静态): 上次运行的游戏索引, 重开终端后自动恢复 (新实例, 进度从零). */
    private static int lastGameIndex = -1;

    private GameCenter() {
    }

    // ==================== 客户端 tick 驱动 (20Hz) ====================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!TerminalGui.isUiOpen()) {
            if (!GameCanvasElement.LIVE.isEmpty()) {
                GameCanvasElement.LIVE.clear();
            }
            return;
        }
        for (GameCanvasElement canvas : GameCanvasElement.LIVE) {
            canvas.tickGame();
        }
    }

    // ==================== 应用界面 ====================

    /** 构建游戏中心 tab 内容. */
    public static UIElement createContent() {
        UIElement root = new UIElement();
        root.layout(l -> l.flexDirection(FlexDirection.COLUMN).widthPercent(100.0f).heightPercent(100.0f).gapAll(4.0f));

        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100.0f).heightPercent(100.0f).gapAll(6.0f));
        root.addChild(row);

        // 左侧: 游戏列表
        ScrollerView list = new ScrollerView();
        list.layout(l -> l.width(130.0f).heightPercent(100.0f));
        row.addChild(list);

        // 右侧: 画布 + 提示 (flexGrow 占列表之外的剩余宽度; widthPercent(100) 是父行整宽,
        // 叠加左侧 130 固定列表会溢出窗口右缘, 导致预览面板/底部提示被裁)
        UIElement right = new UIElement();
        right.layout(l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).width(0.0f).heightPercent(100.0f)
                .alignItems(AlignItems.CENTER).gapAll(4.0f));
        row.addChild(right);

        Label bestLabel = new Label();
        bestLabel.setText(I18n.get("gui.modern_terminal.game.select_hint"), false);
        bestLabel.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        bestLabel.layout(l -> l.widthPercent(100.0f).height(12.0f));
        right.addChild(bestLabel);

        GameCanvasElement canvas = new GameCanvasElement();
        // 撑满右侧剩余空间; 游戏本体由画布按 Game.WIDTH/HEIGHT 等比放大居中绘制
        canvas.layout(l -> l.widthPercent(100.0f).flexGrow(1.0f));
        right.addChild(canvas);

        Label hint = new Label();
        hint.setText(I18n.get("gui.modern_terminal.game.controls_hint"), false);
        hint.textStyle(t -> t.textAlignHorizontal(Horizontal.CENTER));
        hint.layout(l -> l.widthPercent(100.0f).height(12.0f));
        right.addChild(hint);

        // 底部按钮 (与键盘 R/Q 等价, 鼠标党友好)
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4.0f));
        right.addChild(btnRow);
        Button resetBtn = new Button().setText(I18n.get("gui.modern_terminal.game.reset"), false);
        resetBtn.layout(l -> l.width(56.0f).height(16.0f));
        Button exitBtn = new Button().setText(I18n.get("gui.modern_terminal.game.exit"), false);
        exitBtn.layout(l -> l.width(56.0f).height(16.0f));
        btnRow.addChild(resetBtn);
        btnRow.addChild(exitBtn);

        // 选中游戏 → 新实例 + prepare + 挂到画布 (点击选择与重开恢复共用同一入口)
        java.util.function.IntConsumer launch = idx -> {
            Game game = GAMES.get(idx).newGame();
            game.prepare();
            bestLabel.setText(I18n.get("gui.gamingconsole.best_score") + ": "
                    + game.bestScore() + "  ·  " + game.getName().getString(), false);
            canvas.attach(game,
                    () -> { // Q: 退出游戏 → 浏览状态一并放弃
                        canvas.detach();
                        lastGameIndex = -1;
                        resetBtn.setText(I18n.get("gui.modern_terminal.game.reset"), false);
                        bestLabel.setText(I18n.get("gui.modern_terminal.game.select_hint"), false);
                    },
                    () -> bestLabel.setText(I18n.get("gui.gamingconsole.best_score") + ": "
                            + game.bestScore() + "  ·  " + game.getName().getString(), false));
            // 重置按钮语义: R (prepare), 退出按钮语义: Q
        };
        for (int i = 0; i < GAMES.size(); i++) {
            final int idx = i;
            GameEntry entry = GAMES.get(i);
            Button btn = new Button().setText(I18n.get(entry.nameKey()), false);
            btn.textStyle(t -> t.textAlignHorizontal(Horizontal.LEFT));
            btn.layout(l -> l.widthPercent(100.0f).height(18.0f));
            btn.setOnClick(e -> {
                lastGameIndex = idx;
                launch.accept(idx);
            });
            list.addScrollViewChild(btn);
        }
        // 浏览状态保留: 重开终端后自动恢复上次运行的游戏
        if (lastGameIndex >= 0 && lastGameIndex < GAMES.size()) {
            launch.accept(lastGameIndex);
        }
        resetBtn.setOnClick(e -> {
            if (canvas.hasGame()) {
                canvas.getGame().prepare();
            }
        });
        exitBtn.setOnClick(e -> {
            if (canvas.hasGame()) {
                canvas.detach();
                lastGameIndex = -1; // 主动退出 → 下次回列表
                bestLabel.setText(I18n.get("gui.modern_terminal.game.select_hint"), false);
            }
        });

        return root;
    }
}
