package com.github.cinnaio.linkengine.servercore;

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

/**
 * REST endpoints for server core operations.
 */
public class ServerEndpoint {

    private static final String MODULE = "servercore";
    private static final Gson GSON = new Gson();

    private final ServerService serverService;
    private final Plugin plugin;

    public ServerEndpoint(Plugin plugin) {
        this.plugin = plugin;
        this.serverService = new ServerService(plugin);
    }

    public void registerRoutes(Router router) {
        // GET /api/server/status - Server status
        router.get("/api/server/status", (session, params) -> {
            Map<String, Object> status = serverService.getServerStatus();
            return ApiResponse.ok(MODULE, status);
        });

        // GET /api/server/players - Online players list
        router.get("/api/server/players", (session, params) -> {
            List<Map<String, Object>> players = serverService.getOnlinePlayers();
            return ApiResponse.ok(MODULE, players);
        });

        // GET /api/server/players/{name} - Player info
        router.get("/api/server/players/{name}", (session, params) -> {
            String name = params.get("name");
            Map<String, Object> info = serverService.getPlayerInfo(name);
            if (info == null) {
                return ApiResponse.error(MODULE, "Player not found or not online: " + name);
            }
            return ApiResponse.ok(MODULE, info);
        });

        // POST /api/server/command - Execute server command
        router.post("/api/server/command", (session, params) -> {
            try {
                String body = getBody(session);
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                if (json == null || !json.has("command")) {
                    return ApiResponse.error(MODULE, "Missing 'command' in request body");
                }
                String command = json.get("command").getAsString();

                // Check command whitelist
                if (!isCommandAllowed(command)) {
                    return ApiResponse.error(MODULE, "Command not allowed: " + command);
                }

                Map<String, Object> result = serverService.executeCommand(command);
                boolean success = (boolean) result.get("success");
                if (success) {
                    return ApiResponse.ok(MODULE, result, "Command executed");
                } else {
                    return ApiResponse.error(MODULE, (String) result.get("message"));
                }
            } catch (Exception e) {
                return ApiResponse.error(MODULE, "Failed to parse request: " + e.getMessage());
            }
        });

        // GET /api/server/plugins - Installed plugins
        router.get("/api/server/plugins", (session, params) -> {
            List<Map<String, Object>> plugins = serverService.getPlugins();
            return ApiResponse.ok(MODULE, plugins);
        });
    }

    /**
     * Check if a command is allowed based on config whitelist.
     */
    private boolean isCommandAllowed(String command) {
        List<String> allowed = plugin.getConfig().getStringList("modules.servercore.allowed-commands");
        if (allowed == null || allowed.isEmpty()) {
            return true; // Empty whitelist = all allowed
        }
        String baseCommand = command.split(" ")[0].toLowerCase().replace("/", "");
        return allowed.stream().anyMatch(c -> c.equalsIgnoreCase(baseCommand));
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
