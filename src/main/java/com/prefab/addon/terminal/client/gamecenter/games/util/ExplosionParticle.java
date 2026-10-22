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
package com.prefab.addon.terminal.client.gamecenter.games.util;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec2;
import com.prefab.addon.terminal.client.gamecenter.games.graphics.ExplosionParticleRenderer;
import com.prefab.addon.terminal.client.gamecenter.games.graphics.Renderer;

public class ExplosionParticle extends Particle {
    private ExplosionParticleRenderer renderer = null;
    public ExplosionParticle(Vec2 pos, int lifetime, ParticleLevel level) {
        super(pos, new Renderer(), lifetime, level);
    }

    @Override
    public void render(GuiGraphics graphics, int gameX, int gameY, GameStage stage) {
        if (level.isFor(stage)) {
            if (renderer == null) {
                renderer = new ExplosionParticleRenderer(this);
            }
            renderer.render(graphics, gameX + (int)getX(), gameY + (int)getY());
        }
    }
}
