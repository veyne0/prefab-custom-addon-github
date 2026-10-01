/*
 * 绉绘鑷?GameDiscs 妯＄粍 (https://github.com/Tejty/GameDiscs, neoforge-1.21.1 鍒嗘敮)
 * 鍘熶綔鍝佺増鏉?(c) 2024 Tejty - MIT License, 鍘熻鍙瘉澹版槑濡備笅:
 *
 * MIT License
 * Copyright (c) 2024 Tejty
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 */
package com.prefab.addon.terminal.client.gamecenter.games.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import com.prefab.addon.terminal.client.gamecenter.games.audio.GameCenterSounds;

import java.util.Random;


public class SoundPlayer {
    private final SoundManager manager;
    private final Random random;

    public SoundPlayer() {
        manager = Minecraft.getInstance().getSoundManager();
        random = new Random();
    }

    public void play(SoundEvent event) {
        play(event, 1f);
    }
    public void play(SoundEvent event, float pitch) {
        play(event, pitch, 1f);
    }
    public void play(SoundEvent event, float pitch, float volume) {
        manager.play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
    public void playRandom(SoundEvent event, float minPitch, float maxPitch, float volume) {
        play(event, random.nextFloat(minPitch, maxPitch), volume);
    }
    public void playRandom(SoundEvent event, float minPitch, float maxPitch, float minVolume, float maxVolume) {
        playRandom(event, minPitch, maxPitch, random.nextFloat(minVolume, maxVolume));
    }

    public void playClick(boolean down) {
        playRandom(GameCenterSounds.CLICK, down ? 0.3f : 1f, down ? 0.6f : 1.5f, down ? 0.7f : 0.2f, down ? 0.9f : 0.4f);
    }
    public void playJump() {
        playRandom(GameCenterSounds.JUMP, 0.8f, 1.2f, 0.8f, 1.2f);
    }
    public void playPoint() {
        play(GameCenterSounds.POINT, 1f, 0.7f);
    }
    public void playNewBest() {
        play(GameCenterSounds.NEW_BEST, 1.5f, 2f);
    }
    public void playGameOver() {
        play(GameCenterSounds.GAME_OVER, 0.9f, 2f);
    }
    public void playSelect() {
        play(GameCenterSounds.SELECT, 0.5f, 0.5f);
    }
    public void playConfirm() {
        play(GameCenterSounds.CONFIRM, 1f, 0.5f);
    }
}
