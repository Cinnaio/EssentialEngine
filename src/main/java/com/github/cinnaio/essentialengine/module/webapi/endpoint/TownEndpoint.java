package com.github.cinnaio.essentialengine.module.webapi.endpoint;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.scheduler.MainThread;
import com.github.cinnaio.essentialengine.module.husktowns.TownService;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HuskTowns 城镇接口。仅在 HuskTowns 模块启用时注册。
 */
public class TownEndpoint {

    private static final String MODULE = "husktowns";

    private final EssentialEngine plugin;
    private final TownService service;

    public TownEndpoint(EssentialEngine plugin, TownService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register(Router router) {
        router.get("/api/husktowns/towns", (session, params) ->
                ApiResponse.ok(MODULE, MainThread.call(plugin, service::getAllTowns, List.of())));

        router.get("/api/husktowns/towns/{name}", (session, params) -> {
            Map<String, Object> town = MainThread.call(plugin, () -> service.getTown(params.get("name")), null);
            return town == null
                    ? ApiResponse.error(MODULE, "找不到城镇: " + params.get("name"))
                    : ApiResponse.ok(MODULE, town);
        });

        router.get("/api/husktowns/towns/{name}/members", (session, params) -> {
            List<Map<String, Object>> members =
                    MainThread.call(plugin, () -> service.getTownMembers(params.get("name")), null);
            return members == null
                    ? ApiResponse.error(MODULE, "找不到城镇: " + params.get("name"))
                    : ApiResponse.ok(MODULE, members);
        });

        router.post("/api/husktowns/towns/{name}/members", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("uuid")) {
                return ApiResponse.error(MODULE, "请求体缺少 uuid 字段");
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(json.get("uuid").getAsString());
            } catch (IllegalArgumentException error) {
                return ApiResponse.error(MODULE, "uuid 格式不正确");
            }
            String role = json.has("role") ? json.get("role").getAsString() : "Member";
            Map<String, Object> result = MainThread.call(plugin,
                    () -> service.addMember(params.get("name"), uuid, role), null);
            return toResponse(result, "成员已加入");
        });

        router.delete("/api/husktowns/towns/{name}/members/{uuid}", (session, params) -> {
            UUID uuid;
            try {
                uuid = UUID.fromString(params.get("uuid"));
            } catch (IllegalArgumentException error) {
                return ApiResponse.error(MODULE, "uuid 格式不正确");
            }
            Map<String, Object> result = MainThread.call(plugin,
                    () -> service.removeMember(params.get("name"), uuid), null);
            return toResponse(result, "成员已移出");
        });

        router.post("/api/husktowns/towns", (session, params) -> {
            JsonObject json = Router.readJson(session);
            if (!json.has("name") || !json.has("owner_uuid")) {
                return ApiResponse.error(MODULE, "请求体需要 name 与 owner_uuid 字段");
            }
            UUID owner;
            try {
                owner = UUID.fromString(json.get("owner_uuid").getAsString());
            } catch (IllegalArgumentException error) {
                return ApiResponse.error(MODULE, "owner_uuid 格式不正确");
            }
            String name = json.get("name").getAsString();
            try {
                Map<String, Object> result = service.createTown(name, owner).join();
                return toResponse(result, "城镇已创建");
            } catch (Exception error) {
                return ApiResponse.error(MODULE, "创建城镇失败: " + error.getMessage());
            }
        });

        router.delete("/api/husktowns/towns/{name}", (session, params) -> {
            Map<String, Object> result = MainThread.call(plugin,
                    () -> service.deleteTown(params.get("name")), null);
            return toResponse(result, "城镇已删除");
        });

        router.get("/api/husktowns/players/{uuid}/town", (session, params) -> {
            UUID uuid;
            try {
                uuid = UUID.fromString(params.get("uuid"));
            } catch (IllegalArgumentException error) {
                return ApiResponse.error(MODULE, "uuid 格式不正确");
            }
            Map<String, Object> result = MainThread.call(plugin, () -> service.getPlayerTown(uuid), null);
            return toResponse(result, "ok");
        });
    }

    private ApiResponse toResponse(Map<String, Object> result, String successMessage) {
        if (result == null) {
            return ApiResponse.error(MODULE, "操作超时或服务器繁忙");
        }
        if (Boolean.FALSE.equals(result.get("success"))) {
            return ApiResponse.error(MODULE, String.valueOf(result.get("message")));
        }
        return ApiResponse.ok(MODULE, result, successMessage);
    }
}
