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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import com.prefab.addon.terminal.client.gamecenter.games.graphics.*;

public class Particle {
    // Properties of the Particle
    private int lifetime;
    public ParticleLevel level;
    private Vec2 pos = Vec2.ZERO;
    private Vec2 vel = Vec2.ZERO;
    private Renderer image = new Renderer();
    public Particle(Vec2 pos, ParticleColor color, int lifetime, ParticleLevel level) {
        this.pos = pos;
        this.image = new BasicParticleRenderer(color);
        this.lifetime = lifetime;
        this.level = level;
    }
    public Particle(Vec2 pos, Renderer image, int lifetime, ParticleLevel level) {
        this.pos = pos;
        this.image = image;
        this.lifetime = lifetime;
        this.level = level;
    }
    public Particle(Vec2 pos, ResourceLocation file, int fileWidth, int fileHeight, int lifetime, ParticleLevel level) {
        this.pos = pos;
        this.lifetime = lifetime;
        this.level = level;
        this.image = new BreakParticleRenderer(file, fileWidth, fileHeight);
    }
    public Particle(Vec2 pos, ResourceLocation file, int fileWidth, int fileHeight, int posX, int posY, int width, int height, int lifetime, ParticleLevel level) {
        this.pos = pos;
        this.lifetime = lifetime;
        this.level = level;
        this.image = new ParticleRenderer(file, fileWidth, fileHeight, posX, posY, width, height);
    }

    // getters and setters
    public Vec2 getPos() {
        return pos;
    }
    public float getX() {
        return pos.x;
    }
    public float getY() {
        return pos.y;
    }
    public void setPos(Vec2 pos) {
        this.pos = pos;
    }
    public void moveBy(Vec2 offset) {
        pos = pos.add(offset);
    }
    public Vec2 getVelocity() {
        return vel;
    }
    public void setVelocity(Vec2 vel) {
        this.vel = vel;
    }
    public void addVelocity(Vec2 vel) {
        this.vel = this.vel.add(vel);
    }
    public void tick() {
        this.pos = this.pos.add(this.vel);
        this.lifetime--;
    }

    /**
     * Renders the particle
     * @param graphics GuiGraphics used for rendering
     * @param gameX X position of game
     * @param gameY Y position of game
     */
    public void render(GuiGraphics graphics, int gameX, int gameY, GameStage stage) {
        if (level.isFor(stage)) {
            image.render(graphics, gameX + (int) this.pos.x, gameY + (int) this.pos.y);
        }
    }

    public boolean isForOverlay() {
        return level == ParticleLevel.OVERLAY;
    }

    public boolean isDead() {
        return lifetime <= 0;
    }
}
