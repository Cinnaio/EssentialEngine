package com.github.cinnaio.essentialengine.module.monitor;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.storage.MonitorEvent;
import com.github.cinnaio.essentialengine.core.storage.PerfSample;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import fi.iki.elonen.NanoHTTPD;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 性能监控的 REST 接口（为 AstrBot 等外部程序预留）。
 *
 * <p>由 webapi 模块在启用时挂载（需同时开启 {@code modules.monitor} 与
 * {@code modules.webapi}），鉴权与其它接口一致：请求头
 * {@code Authorization: Bearer <api-key>}。</p>
 *
 * <p>预留的接口契约（详见 README「性能监控与 AstrBot 预留接口」）：</p>
 * <pre>
 * GET  /api/monitor/status                  当前状态快照（TPS / 内存 / 在线 / Tick / Spark / 会话）
 * GET  /api/monitor/samples?minutes=&limit= 性能采样历史（按时间正序）
 * GET  /api/monitor/events?limit=&type=&since= 最近事件（倒序，可按类型过滤，卡顿含诊断 data）
 * GET  /api/monitor/sessions                会话记录（启动 / 关闭配对，含异常退出）
 * POST /api/monitor/events                  写入自定义事件（需 allow-custom-events）
 * </pre>
 */
public class MonitorEndpoint {

    private static final String MODULE = "monitor";
    private static final Gson GSON = new Gson();

    private final EssentialEngine plugin;
    private final MonitorService service;

    public MonitorEndpoint(EssentialEngine plugin, MonitorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register(Router router) {
        router.get("/api/monitor/status", (session, params) ->
                ApiResponse.ok(MODULE, service.status()));

        router.get("/api/monitor/samples", (session, params) -> {
            int minutes = intParam(session, "minutes", 30);
            int limit = intParam(session, "limit", 500);
            List<PerfSample> samples = service.samples(minutes, limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", samples.size());
            data.put("minutes", minutes);
            data.put("samples", samples);
            return ApiResponse.ok(MODULE, data);
        });

        router.get("/api/monitor/events", (session, params) -> {
            int limit = intParam(session, "limit", 50);
            String type = session.getParms().get("type");
            long since = longParam(session, "since", 0);
            List<MonitorEvent> events = service.events(limit, type, since);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", events.size());
            data.put("events", events);
            return ApiResponse.ok(MODULE, data);
        });

        router.get("/api/monitor/sessions", (session, params) -> {
            int limit = intParam(session, "limit", 20);
            List<Map<String, Object>> sessions = service.sessions(limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", sessions.size());
            data.put("sessions", sessions);
            return ApiResponse.ok(MODULE, data);
        });

        // AstrBot 预留：允许外部程序写入自定义事件（如「检测到玩家刷屏」「定时任务告警」）
        router.post("/api/monitor/events", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("type")) {
                return ApiResponse.error(MODULE, "请求体需要 type 字段（事件类型）");
            }
            String type = json.get("type").getAsString();
            String message = json.has("message") ? json.get("message").getAsString() : type;
            Map<String, Object> data = null;
            if (json.has("data") && json.get("data").isJsonObject()) {
                data = GSON.fromJson(json.getAsJsonObject("data"),
                        new TypeToken<Map<String, Object>>() {
                        }.getType());
            }
            if (!service.recordCustomEvent(type, message, data)) {
                return ApiResponse.error(MODULE,
                        "自定义事件写入未开启（modules.monitor.allow-custom-events）");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            result.put("message", message);
            result.put("recorded", true);
            return ApiResponse.ok(MODULE, result, "事件已记录");
        });
    }

    private static int intParam(NanoHTTPD.IHTTPSession session, String key, int fallback) {
        try {
            return Integer.parseInt(session.getParms().get(key));
        } catch (Exception error) {
            return fallback;
        }
    }

    private static long longParam(NanoHTTPD.IHTTPSession session, String key, long fallback) {
        try {
            return Long.parseLong(session.getParms().get(key));
        } catch (Exception error) {
            return fallback;
        }
    }
}
