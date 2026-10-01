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
import com.prefab.addon.terminal.TerminalRegistry;

import net.minecraft.resources.ResourceLocation;

public class BasicParticleRenderer extends ParticleRenderer {
    private static final ResourceLocation IMAGE = ResourceLocation.fromNamespaceAndPath(TerminalRegistry.MOD_ID, "textures/particle/basic_particle.png");

    public BasicParticleRenderer(ParticleColor color) {
        super(IMAGE, 2, 32, 0, color.value() * 2, 2, 2);
    }
}
