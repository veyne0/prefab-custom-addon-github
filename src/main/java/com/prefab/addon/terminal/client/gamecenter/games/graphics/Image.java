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

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public class Image extends Renderer {
    private final ResourceLocation file;
    private final int fileWidth, fileHeight, x, y, width, height;

    public Image(ResourceLocation file, int width, int height) {
        this(file, width, height, 0, 0, width, height);
    }
    public Image(ResourceLocation file, int fileWidth, int fileHeight, int x, int y, int width, int height) {
        this.file = file;
        this.fileWidth = fileWidth;
        this.fileHeight = fileHeight;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    @Override
    public void render(GuiGraphics graphics, int posX, int posY) {
        graphics.blit(file, posX, posY, 0, x, y, width, height, fileWidth, fileHeight);
    }
}
