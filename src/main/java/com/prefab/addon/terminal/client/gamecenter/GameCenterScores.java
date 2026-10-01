/*
 * 游戏中心 - 最高分本地存储 (客户端).
 * 适配说明: 原版 GameDiscs 把最高分写在游戏机物品 NBT (经服务端包同步),
 * 终端架构下无游戏机物品, 改存 .minecraft/modernterminal/game_scores.json.
 */
package com.prefab.addon.terminal.client.gamecenter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.prefab.addon.terminal.TerminalRegistry;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 游戏 id → 最高分, 懒加载, 提交时落盘. 仅客户端主线程访问. */
public final class GameCenterScores {
    private static final Path FILE = FMLPaths.GAMEDIR.get().resolve("modernterminal")
            .resolve("game_scores.json");
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Integer>>() {
    }.getType();

    private static Map<String, Integer> scores = null;

    private GameCenterScores() {
    }

    /** @return 该游戏的最高分 (无记录返回 0). */
    public static int get(String gameId) {
        load();
        return scores.getOrDefault(gameId, 0);
    }

    /** 提交分数; @return true 表示刷新了最高分. */
    public static boolean submit(String gameId, int score) {
        load();
        int best = scores.getOrDefault(gameId, 0);
        if (score > best) {
            scores.put(gameId, score);
            save();
            return true;
        }
        return false;
    }

    private static void load() {
        if (scores != null) {
            return;
        }
        scores = new HashMap<>();
        try {
            if (Files.isRegularFile(FILE)) {
                scores = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), TYPE);
                if (scores == null) {
                    scores = new HashMap<>();
                }
            }
        } catch (Exception e) {
            TerminalRegistry.LOGGER.warn("[GameCenter] 读取最高分失败, 使用空记录: {}", e.toString());
            scores = new HashMap<>();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(scores), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TerminalRegistry.LOGGER.warn("[GameCenter] 保存最高分失败: {}", e.toString());
        }
    }
}
