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
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public class MultiImage extends Renderer {
    private List<Image> images = new ArrayList<>();
    private int current = 0;

    public MultiImage(List<Image>images) {
        this.images = images;
    }

    public MultiImage(ResourceLocation file, int fileWidth, int fileHeight, List<Rect2i> rects) {
        for (Rect2i rect : rects) {
            images.add(new Image(file, fileWidth, fileHeight, rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight()));
        }
    }
    public MultiImage(ResourceLocation file, int fileWidth, int fileHeight, int count) {
        this(file, fileWidth, fileHeight, fromFile(fileWidth, fileHeight, count));
    }

    public static List<Rect2i> fromFile(int fileWidth, int fileHeight, int count) {
        List<Rect2i> rects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rects.add(new Rect2i(0, fileHeight / count * i, fileWidth, fileHeight / count));
        }
        return rects;
    }

    public MultiImage setImage(int index) {
        current = Math.min(Math.max(index, 0), count() - 1);
        return this;
    }
    public int current() {
        return current;
    }

    public int count() {
        return images.size();
    }

    @Override
    public void render(GuiGraphics graphics, int posX, int posY) {
        if (count() > 0) {
            images.get(current).render(graphics, posX, posY);
        }
    }
}
