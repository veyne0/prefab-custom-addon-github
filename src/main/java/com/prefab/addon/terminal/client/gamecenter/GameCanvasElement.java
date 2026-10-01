/*
 * 游戏中心 - 游戏画布元素 (客户端).
 * 职责: 把 GameDiscs 的 Game 嵌进 LDLib2 UIElement —
 *   逐帧绘制 (drawBackgroundAdditional + scissor 裁剪到元素区),
 *   键盘事件 → 虚拟手柄 Controls (WASD/方向键/空格/回车),
 *   客户端 tick (20Hz) 驱动 Game.tick().
 * 交互模式与 GameDiscs GamingConsoleScreen 一致: 任意方向/动作键开始, R 重置, Q 退出.
 */
package com.prefab.addon.terminal.client.gamecenter;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.prefab.addon.terminal.client.gamecenter.games.controls.Button;
import com.prefab.addon.terminal.client.gamecenter.games.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class GameCanvasElement extends UIElement {
    /** 存活的画布 (tick 驱动用); GUI 关闭时由 GameCenter 清空. */
    static final Set<GameCanvasElement> LIVE = new HashSet<>();

    /** 当前运行的游戏 (null = 未选择, 画布显示占位提示). */
    @Nullable
    private Game game;
    /** Q 退出游戏回调 (由游戏中心界面接: 返回游戏列表). */
    @Nullable
    private Runnable onExitGame;
    /** R 重置回调 (由游戏中心界面接: 刷新最高分显示). */
    @Nullable
    private Runnable onResetGame;

    public GameCanvasElement() {
        style(s -> s.backgroundTexture(Sprites.RECT_DARK));
        setFocusable(true);
        addEventListener(UIEvents.KEY_DOWN, this::onKeyDown);
        addEventListener(UIEvents.KEY_UP, this::onKeyUp);
        addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
        LIVE.add(this);
    }

    /** 附加一个新游戏 (已 prepare); 附加后立刻聚焦等待任意键开局. */
    public void attach(Game newGame, Runnable exitCb, Runnable resetCb) {
        this.game = newGame;
        this.onExitGame = exitCb;
        this.onResetGame = resetCb;
        focus();
    }

    /** 游戏是否仍在运行中 (有游戏附加). */
    public boolean hasGame() {
        return game != null;
    }

    /** 退出当前游戏, 回到未选择状态. */
    public void detach() {
        this.game = null;
    }

    @Nullable
    public Game getGame() {
        return game;
    }

    /** 客户端 tick (GameCenter 20Hz 驱动). */
    void tickGame() {
        if (game != null && Minecraft.getInstance().screen != null) {
            game.tick();
        }
    }

    private void onMouseDown(UIEvent event) {
        // 点击画布即拿焦点, 键盘输入直达虚拟手柄
        focus();
        event.stopPropagation();
    }

    private void onKeyDown(UIEvent event) {
        if (game == null || !isFocused() || event.keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        // R 重置 / Q 退出 (与 GamingConsoleScreen 语义一致)
        if (event.keyCode == GLFW.GLFW_KEY_R) {
            game.prepare();
            if (onResetGame != null) {
                onResetGame.run();
            }
            event.stopPropagation();
            return;
        }
        if (event.keyCode == GLFW.GLFW_KEY_Q) {
            if (onExitGame != null) {
                onExitGame.run();
            }
            event.stopPropagation();
            return;
        }
        Button btn = mapKey(event.keyCode);
        if (btn != null) {
            game.controls.setButton(btn, true);
            event.stopPropagation();
        }
    }

    private void onKeyUp(UIEvent event) {
        if (game == null || !isFocused() || event.keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        Button btn = mapKey(event.keyCode);
        if (btn != null) {
            game.controls.setButton(btn, false);
            event.stopPropagation();
        }
    }

    /** GLFW 键码 → 虚拟手柄 (WASD 与方向键双支持). */
    @Nullable
    private static Button mapKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> Button.UP;
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> Button.DOWN;
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> Button.LEFT;
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> Button.RIGHT;
            case GLFW.GLFW_KEY_SPACE -> Button.BUTTON1;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> Button.BUTTON2;
            default -> null;
        };
    }

    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        if (game == null) {
            return;
        }
        GuiGraphics graphics = guiContext.graphics;
        int x = (int) getPositionX();
        int y = (int) getPositionY();
        int w = (int) getSizeWidth();
        int h = (int) getSizeHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 关键: 先把此前批处理内容 (含本元素背景) flush 落盘。
        // LDLib2 元素背景走 bufferSource 批量绘制, 而 vanilla blit/fill 是即时上屏;
        // 若不先 flush, 批量内容会在本帧末才绘制, 恰好盖在已上屏的游戏画面之上 (只留后画的字体)。
        graphics.flush();
        // 游戏原生 140x100, 按画布尺寸等比放大; 取 0.5 步长减少最近邻采样的像素不均
        float scale = Math.max(1.0f, Math.min(w / (float) Game.WIDTH, h / (float) Game.HEIGHT));
        scale = Math.max(1.0f, (float) (Math.floor(scale * 2.0) / 2.0));
        int sw = (int) (Game.WIDTH * scale);
        int sh = (int) (Game.HEIGHT * scale);
        // 游戏区在画布内居中
        int ox = x + (w - sw) / 2;
        int oy = y + (h - sh) / 2;
        // 裁剪到游戏区, 防止粒子/分数越界画到其他 UI 上
        guiContext.enableScissor(ox, oy, sw, sh);
        graphics.pose().pushPose();
        graphics.pose().translate(ox, oy, 0f);
        graphics.pose().scale(scale, scale, 1.0f);
        game.render(graphics, 0, 0);
        graphics.pose().popPose();
        // 游戏本体全是即时上屏绘制, 立刻落盘:
        // 避免滞留批次 (游戏字体等) 被后续元素在自己的裁剪区内错误地 flush 掉
        graphics.flush();
        guiContext.disableScissor();
    }
}
