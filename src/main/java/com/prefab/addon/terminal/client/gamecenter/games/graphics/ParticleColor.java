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
package com.prefab.addon.terminal.client.gamecenter.games.graphics;

import java.util.Random;

public enum ParticleColor {
    WHITE,
    ORANGE,
    MAGENTA,
    LIGHT_BLUE,
    YELLOW,
    LIME,
    PINK,
    GRAY,
    LIGHT_GRAY,
    CYAN,
    PURPLE,
    BLUE,
    BROWN,
    GREEN,
    RED,
    BLACK;

    public static ParticleColor random(Random random) {
        return switch (random.nextInt(0, 16)) {
            case 1 -> ORANGE;
            case 2 -> MAGENTA;
            case 3 -> LIGHT_BLUE;
            case 4 -> YELLOW;
            case 5 -> LIME;
            case 6 -> PINK;
            case 7 -> GRAY;
            case 8 -> LIGHT_GRAY;
            case 9 -> CYAN;
            case 10 -> PURPLE;
            case 11 -> BLUE;
            case 12 -> BROWN;
            case 13 -> GREEN;
            case 14 -> RED;
            case 15 -> BLACK;
            default -> WHITE;
        };
    }

    public int value() {
        return switch (this) {
            case WHITE -> 0;
            case ORANGE -> 1;
            case MAGENTA -> 2;
            case LIGHT_BLUE -> 3;
            case YELLOW -> 4;
            case LIME -> 5;
            case PINK -> 6;
            case GRAY -> 7;
            case LIGHT_GRAY -> 8;
            case CYAN -> 9;
            case PURPLE -> 10;
            case BLUE -> 11;
            case BROWN -> 12;
            case GREEN -> 13;
            case RED -> 14;
            case BLACK -> 15;
        };
    }
}
