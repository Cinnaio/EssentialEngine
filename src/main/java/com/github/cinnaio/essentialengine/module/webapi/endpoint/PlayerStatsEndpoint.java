package com.github.cinnaio.essentialengine.module.webapi.endpoint;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.module.playerstats.PlayerStatsService;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 玩家中心的数据接口；由 Web API 的 API Key 保护。 */
public class PlayerStatsEndpoint {

    private static final String MODULE = "playerstats";

    private final EssentialEngine plugin;
    private final PlayerStatsService stats;

    public PlayerStatsEndpoint(EssentialEngine plugin, PlayerStatsService stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public void register(Router router) {
        router.get("/api/essentials/players/{identifier}/stats", (session, params) -> {
            UserData data = resolve(params.get("identifier"));
            if (data == null) {
                return ApiResponse.error(MODULE, "找不到玩家: " + params.get("identifier"));
            }
            try {
                return ApiResponse.ok(MODULE, stats.profile(data));
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取玩家统计失败: " + error.getMessage());
            }
        });

        router.get("/api/essentials/leaderboards/playtime", (session, params) -> {
            int limit = limit(session.getParms().get("limit"), 10);
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("metric", "playtime");
                body.put("entries", stats.leaderboard(limit));
                return ApiResponse.ok(MODULE, body);
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "读取游玩时长排行榜失败: " + error.getMessage());
            }
        });
    }

    /** 名字或 UUID 都能解析；离线玩家会从存储读取。 */
    private UserData resolve(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(identifier);
        } catch (IllegalArgumentException ignored) {
            uuid = plugin.users().resolveUuid(identifier);
        }
        return uuid == null ? null : plugin.users().loadOffline(uuid);
    }

    private int limit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(raw)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
