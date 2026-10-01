/*
 * 移植自 GameDiscs 模组 (https://github.com/Tejty/GameDiscs, neoforge-1.21.1 分支)
 * 原作品版权 (c) 2024 Tejty - MIT License.
 * 本文件为适配层: GameDiscs 自注册音效 → 原版 SoundEvents 映射.
 */
package com.prefab.addon.terminal.client.gamecenter.games.audio;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 游戏中心音效表: 用原版音效替代 GameDiscs 自注册的 11 个 SoundEvent,
 * 避免向注册表新增条目. 命名与 GameDiscs SoundRegistry 一一对应.
 */
public final class GameCenterSounds {
    /** 按键点击 (按下低沉/抬起清脆, 由 SoundPlayer 控制音高). */
    public static final SoundEvent CLICK = SoundEvents.UI_BUTTON_CLICK.value();
    /** 跳跃/弹跳. */
    public static final SoundEvent JUMP = SoundEvents.EXPERIENCE_ORB_PICKUP;
    /** 得分. */
    public static final SoundEvent POINT = SoundEvents.NOTE_BLOCK_PLING.value();
    /** 新纪录. */
    public static final SoundEvent NEW_BEST = SoundEvents.PLAYER_LEVELUP;
    /** 游戏结束. */
    public static final SoundEvent GAME_OVER = SoundEvents.BEACON_DEACTIVATE;
    /** 列表选择移动. */
    public static final SoundEvent SELECT = SoundEvents.UI_BUTTON_CLICK.value();
    /** 确认/开局. */
    public static final SoundEvent CONFIRM = SoundEvents.NOTE_BLOCK_BELL.value();
    /** 爆炸 (失去生命/消除行). */
    public static final SoundEvent EXPLOSION = SoundEvents.GENERIC_EXPLODE.value();
    /** 射击/落块. */
    public static final SoundEvent SHOOT = SoundEvents.ARROW_SHOOT;
    /** 旋转方块. */
    public static final SoundEvent SWING = SoundEvents.PLAYER_ATTACK_SWEEP;
    /** 开关切换. */
    public static final SoundEvent SWITCH = SoundEvents.LEVER_CLICK;

    private GameCenterSounds() {
    }
}
