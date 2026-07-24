package com.github.cinnaio.linkengine.husktowns;

import com.github.cinnaio.linkengine.core.http.ApiResponse;
import com.github.cinnaio.linkengine.core.http.Router;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for HuskTowns operations.
 */
public class TownEndpoint {

    private static final String MODULE = "husktowns";
    private static final Gson GSON = new Gson();

    private final TownService townService;

    public TownEndpoint(Plugin plugin) {
        this.townService = new TownService(plugin);
        this.townService.initialize();
    }

    public void registerRoutes(Router router) {
        // GET /api/husktowns/towns - List all towns
        router.get("/api/husktowns/towns", (session, params) -> {
            List<Map<String, Object>> towns = townService.getAllTowns();
            return ApiResponse.ok(MODULE, towns);
        });

        // GET /api/husktowns/towns/{name} - Get town details
        router.get("/api/husktowns/towns/{name}", (session, params) -> {
            String name = params.get("name");
            Map<String, Object> town = townService.getTown(name);
            if (town == null) {
                return ApiResponse.error(MODULE, "Town not found: " + name);
            }
            return ApiResponse.ok(MODULE, town);
        });

        // GET /api/husktowns/towns/{name}/members - Get town members
        router.get("/api/husktowns/towns/{name}/members", (session, params) -> {
            String name = params.get("name");
            List<Map<String, Object>> members = townService.getTownMembers(name);
            if (members == null) {
                return ApiResponse.error(MODULE, "Town not found: " + name);
            }
            return ApiResponse.ok(MODULE, members);
        });

        // POST /api/husktowns/towns/{name}/members - Add member
        router.post("/api/husktowns/towns/{name}/members", (session, params) -> {
            try {
                String townName = params.get("name");
                String body = getBody(session);
                JsonObject json = GSON.fromJson(body, JsonObject.class);

                if (json == null || !json.has("uuid")) {
                    return ApiResponse.error(MODULE, "Missing 'uuid' in request body");
                }

                UUID uuid = UUID.fromString(json.get("uuid").getAsString());
                String role = json.has("role") ? json.get("role").getAsString() : "Member";

                Map<String, Object> result = townService.addMember(townName, uuid, role);
                if (result.containsKey("success") && !(boolean) result.get("success")) {
                    return ApiResponse.error(MODULE, (String) result.get("message"));
                }
                return ApiResponse.ok(MODULE, result, "Member added");
            } catch (Exception e) {
                return ApiResponse.error(MODULE, "Failed to add member: " + e.getMessage());
            }
        });

        // DELETE /api/husktowns/towns/{name}/members/{uuid} - Remove member
        router.delete("/api/husktowns/towns/{name}/members/{uuid}", (session, params) -> {
            try {
                String townName = params.get("name");
                UUID uuid = UUID.fromString(params.get("uuid"));
                Map<String, Object> result = townService.removeMember(townName, uuid);
                if (result.containsKey("success") && !(boolean) result.get("success")) {
                    return ApiResponse.error(MODULE, (String) result.get("message"));
                }
                return ApiResponse.ok(MODULE, result, "Member removed");
            } catch (Exception e) {
                return ApiResponse.error(MODULE, "Failed to remove member: " + e.getMessage());
            }
        });

        // POST /api/husktowns/towns - Create town
        router.post("/api/husktowns/towns", (session, params) -> {
            try {
                String body = getBody(session);
                JsonObject json = GSON.fromJson(body, JsonObject.class);

                if (json == null || !json.has("name") || !json.has("owner_uuid")) {
                    return ApiResponse.error(MODULE, "Missing 'name' or 'owner_uuid' in request body");
                }

                String townName = json.get("name").getAsString();
                UUID ownerUuid = UUID.fromString(json.get("owner_uuid").getAsString());

                Map<String, Object> result = townService.createTown(townName, ownerUuid).join();
                if (result.containsKey("success") && !(boolean) result.get("success")) {
                    return ApiResponse.error(MODULE, (String) result.get("message"));
                }
                return ApiResponse.ok(MODULE, result, "Town created");
            } catch (Exception e) {
                return ApiResponse.error(MODULE, "Failed to create town: " + e.getMessage());
            }
        });

        // DELETE /api/husktowns/towns/{name} - Delete town
        router.delete("/api/husktowns/towns/{name}", (session, params) -> {
            String townName = params.get("name");
            Map<String, Object> result = townService.deleteTown(townName);
            if (result.containsKey("success") && !(boolean) result.get("success")) {
                return ApiResponse.error(MODULE, (String) result.get("message"));
            }
            return ApiResponse.ok(MODULE, result, "Town deleted");
        });

        // GET /api/husktowns/players/{uuid}/town - Get player's town
        router.get("/api/husktowns/players/{uuid}/town", (session, params) -> {
            try {
                UUID uuid = UUID.fromString(params.get("uuid"));
                Map<String, Object> result = townService.getPlayerTown(uuid);
                if (result.containsKey("success") && !(boolean) result.get("success")) {
                    return ApiResponse.error(MODULE, (String) result.get("message"));
                }
                return ApiResponse.ok(MODULE, result);
            } catch (Exception e) {
                return ApiResponse.error(MODULE, "Invalid UUID: " + e.getMessage());
            }
        });
    }

    private String getBody(NanoHTTPD.IHTTPSession session) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(session.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
