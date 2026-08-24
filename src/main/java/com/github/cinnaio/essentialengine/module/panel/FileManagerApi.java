package com.github.cinnaio.essentialengine.module.panel;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.module.webapi.http.ApiResponse;
import com.github.cinnaio.essentialengine.module.webapi.http.Router;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 面板的「文件」页后端：浏览 / 预览 / 编辑 / 删除 {@code data} 与 {@code userdata}
 * 下的文件，支持单文件下载与整目录 zip 打包。
 *
 * <p><b>只挂在面板内部</b>（不暴露给 webapi），由 {@code modules.panel.file-manager}
 * 控制是否启用。所有路径都严格限制在两个根目录内，越界（{@code ../}、绝对路径、
 * 符号链接逃逸）一律拒绝；删除前自动备份到 {@code <插件目录>/backups/}；
 * 编辑与删除都会记录审计日志（含来源 IP）。</p>
 */
public class FileManagerApi {

    private static final String MODULE = "panel";
    /** 允许浏览的两个根目录（相对插件数据目录）。 */
    private static final Set<String> ROOTS = Set.of("data", "userdata");
    /** 视为文本、可预览 / 编辑的扩展名。 */
    private static final Set<String> TEXT_EXT = Set.of(
            "yml", "yaml", "json", "jsonl", "txt", "log", "cfg", "properties", "md", "lang");
    /** 只读预览每页行数。 */
    private static final int PAGE_LINES = 500;
    /** 预览时单行最多保留的字符数，超长截断并标注。 */
    private static final int LINE_CAP = 4096;
    /** 在线编辑的最大文件大小（字节），超过只能预览 / 下载。 */
    private static final long EDIT_MAX = 2L * 1024 * 1024;

    private final EssentialEngine plugin;

    public FileManagerApi(EssentialEngine plugin) {
        this.plugin = plugin;
    }

    public void register(Router router) {
        router.get("/api/files/list", (session, params) -> list(session));
        router.get("/api/files/preview", (session, params) -> preview(session));
        router.post("/api/files/edit", (session, params) -> edit(session));
        router.post("/api/files/delete", (session, params) -> delete(session));
        router.getRaw("/api/files/download", (session, params) -> download(session));
        router.getRaw("/api/files/zip", (session, params) -> zip(session));
    }

    // ------------------------------------------------------------------ 目录浏览

    private ApiResponse list(NanoHTTPD.IHTTPSession session) {
        String root = session.getParms().get("root");
        String rel = session.getParms().get("path");
        File dir = resolve(root, rel == null ? "" : rel);
        if (dir == null) {
            return ApiResponse.error(MODULE, "路径不合法或越界");
        }
        if (!dir.isDirectory()) {
            // 全新服务器 data/ 目录可能还没被插件创建，直接当成空目录展示
            if (!dir.mkdirs()) {
                return ApiResponse.error(MODULE, "目录不存在或无法创建: " + dir.getName());
            }
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::isDirectory).reversed()
                    .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", child.getName());
                item.put("isDir", child.isDirectory());
                item.put("size", child.isDirectory() ? 0 : child.length());
                item.put("modified", child.lastModified());
                boolean text = !child.isDirectory() && isTextFile(child.getName());
                item.put("text", text);
                item.put("editable", text && child.length() <= EDIT_MAX);
                entries.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("root", root);
        data.put("path", rel == null ? "" : rel);
        data.put("parent", parentPath(rel));
        data.put("count", entries.size());
        data.put("entries", entries);
        return ApiResponse.ok(MODULE, data);
    }

    /** 上一级目录的相对路径；已在根目录时为 ""。 */
    private static String parentPath(String rel) {
        if (rel == null || rel.isEmpty()) {
            return "";
        }
        int slash = rel.lastIndexOf('/');
        return slash <= 0 ? "" : rel.substring(0, slash);
    }

    // ------------------------------------------------------------------ 预览 / 编辑

    private ApiResponse preview(NanoHTTPD.IHTTPSession session) {
        String root = session.getParms().get("root");
        String rel = session.getParms().get("path");
        String pageParam = session.getParms().get("page");
        boolean full = "full".equalsIgnoreCase(pageParam);
        File file = resolve(root, rel);
        if (file == null) {
            return ApiResponse.error(MODULE, "路径不合法或越界");
        }
        if (!file.isFile()) {
            return ApiResponse.error(MODULE, "不是文件");
        }
        if (!isTextFile(file.getName())) {
            return ApiResponse.error(MODULE, "非文本文件，不支持预览（可下载）");
        }

        long size = file.length();
        if (full) {
            if (size > EDIT_MAX) {
                return ApiResponse.error(MODULE, "文件超过 " + EDIT_MAX / 1024 / 1024 + " MB，仅可预览 / 下载");
            }
            try {
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", file.getName());
                data.put("size", size);
                data.put("editable", true);
                data.put("content", content);
                return ApiResponse.ok(MODULE, data);
            } catch (IOException error) {
                return ApiResponse.error(MODULE, "读取失败: " + error.getMessage());
            }
        }

        int page = parsePage(pageParam);
        List<String> lines = new ArrayList<>();
        boolean hasMore = false;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (skipped < page * PAGE_LINES) {
                    skipped++;
                    continue;
                }
                if (lines.size() >= PAGE_LINES) {
                    hasMore = true;
                    break;
                }
                if (line.length() > LINE_CAP) {
                    line = line.substring(0, LINE_CAP) + " …[本行过长已截断]";
                    truncated = true;
                }
                lines.add(line);
            }
        } catch (IOException error) {
            return ApiResponse.error(MODULE, "读取失败: " + error.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", file.getName());
        data.put("size", size);
        data.put("page", page);
        data.put("hasMore", hasMore);
        data.put("truncated", truncated);
        data.put("editable", size <= EDIT_MAX);
        data.put("lines", lines);
        return ApiResponse.ok(MODULE, data);
    }

    private ApiResponse edit(NanoHTTPD.IHTTPSession session) {
        JsonObject body = Router.readJson(session);
        String root = body.has("root") ? body.get("root").getAsString() : "";
        String rel = body.has("path") ? body.get("path").getAsString() : "";
        String content = body.has("content") ? body.get("content").getAsString() : "";
        File file = resolve(root, rel);
        if (file == null) {
            return ApiResponse.error(MODULE, "路径不合法或越界");
        }
        if (!file.isFile()) {
            return ApiResponse.error(MODULE, "不是文件");
        }
        if (!isTextFile(file.getName())) {
            return ApiResponse.error(MODULE, "非文本文件，不支持编辑");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.length() > EDIT_MAX || bytes.length > EDIT_MAX) {
            return ApiResponse.error(MODULE, "文件或内容超过 " + EDIT_MAX / 1024 / 1024 + " MB，拒绝保存");
        }
        String validation = validateFormat(file.getName(), content);
        if (validation != null) {
            return ApiResponse.error(MODULE, validation);
        }
        try {
            Path target = file.toPath();
            Path tmp = Files.createTempFile(target.getParent(), file.getName() + ".", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            audit(session, "编辑", root + "/" + rel, bytes.length);
            return ApiResponse.ok(MODULE, Map.of("ok", true), "已保存 " + file.getName());
        } catch (IOException error) {
            return ApiResponse.error(MODULE, "保存失败: " + error.getMessage());
        }
    }

    /**
     * 保存前按扩展名做格式校验，返回错误描述；合法返回 null。
     * 只校验语法，不重写内容——注释、空行、缩进风格都原样保留。
     */
    private String validateFormat(String name, String content) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            try {
                new YamlConfiguration().loadFromString(content);
            } catch (InvalidConfigurationException error) {
                String detail = error.getMessage();
                return "YAML 格式错误，已取消保存"
                        + (detail == null ? "" : ": " + detail.split("\n")[0].trim());
            }
            return null;
        }
        if (lower.endsWith(".json")) {
            return isValidJson(content) ? null : "JSON 格式错误，已取消保存";
        }
        if (lower.endsWith(".jsonl")) {
            int lineNo = 0;
            for (String line : content.split("\n", -1)) {
                lineNo++;
                if (!line.isBlank() && !isValidJson(line)) {
                    return "JSONL 第 " + lineNo + " 行不是合法 JSON，已取消保存";
                }
            }
            return null;
        }
        return null;
    }

    /** 严格 JSON 校验：不允许宽松语法，也不允许根值后面的多余内容。 */
    private static boolean isValidJson(String text) {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            JsonParser.parseReader(reader);
            reader.peek();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 删除（删前备份）

    private ApiResponse delete(NanoHTTPD.IHTTPSession session) {
        JsonObject body = Router.readJson(session);
        String root = body.has("root") ? body.get("root").getAsString() : "";
        String rel = body.has("path") ? body.get("path").getAsString() : "";
        File target = resolve(root, rel);
        if (target == null) {
            return ApiResponse.error(MODULE, "路径不合法或越界");
        }
        if (!target.exists()) {
            return ApiResponse.error(MODULE, "文件不存在");
        }
        if (target.equals(rootDir(root))) {
            return ApiResponse.error(MODULE, "不能删除根目录");
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backupDir = new File(new File(plugin.getDataFolder(), "backups"), stamp + "-" + root);
            File backupTarget = new File(backupDir, rel == null || rel.isEmpty() ? "root" : rel);
            if (target.isDirectory()) {
                copyTree(target, backupTarget);
            } else {
                backupTarget.getParentFile().mkdirs();
                Files.copy(target.toPath(), backupTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            deleteTree(target);
            audit(session, "删除（已备份 backups/" + backupDir.getName() + "）", root + "/" + rel, -1);
            return ApiResponse.ok(MODULE,
                    Map.of("ok", true, "backup", backupDir.getName()), "已删除，原文件已备份到 backups/" + backupDir.getName());
        } catch (IOException error) {
            return ApiResponse.error(MODULE, "删除失败: " + error.getMessage());
        }
    }

    private static void copyTree(File source, File dest) throws IOException {
        try (Stream<Path> stream = Files.walk(source.toPath())) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path target = dest.toPath().resolve(source.toPath().relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(File target) throws IOException {
        try (Stream<Path> stream = Files.walk(target.toPath())) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------ 下载 / 打包

    private NanoHTTPD.Response download(NanoHTTPD.IHTTPSession session) throws IOException {
        String root = session.getParms().get("root");
        String rel = session.getParms().get("path");
        File file = resolve(root, rel);
        if (file == null || !file.isFile()) {
            return jsonError(NanoHTTPD.Response.Status.NOT_FOUND, "路径不合法或越界");
        }
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime(file.getName()),
                new FileInputStream(file), file.length());
        response.addHeader("Content-Disposition",
                "attachment; filename=\"" + safeHeader(file.getName()) + "\"");
        audit(session, "下载", root + "/" + rel, file.length());
        return response;
    }

    /** 把整个根目录（data / userdata）打包成 zip 下载；zip 写进临时文件再流式发送。 */
    private NanoHTTPD.Response zip(NanoHTTPD.IHTTPSession session) throws IOException {
        String root = session.getParms().get("root");
        File base = rootDir(root);
        if (base == null || !base.isDirectory()) {
            return jsonError(NanoHTTPD.Response.Status.NOT_FOUND, "目录不存在");
        }
        File temp = File.createTempFile("ee-panel-" + root + "-", ".zip");
        long count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            try (Stream<Path> stream = Files.walk(base.toPath())) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    // 条目带 data/ 或 userdata/ 前缀，解压时结构一目了然
                    String entryName = base.toPath().getParent().relativize(path).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                    count++;
                }
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK, "application/zip", new DeletingInputStream(temp));
        response.addHeader("Content-Disposition",
                "attachment; filename=\"essentialengine-" + root + "-" + stamp + ".zip\"");
        audit(session, "打包下载 " + count + " 个文件", root, temp.length());
        return response;
    }

    /** 读完即删的输入流：zip 临时文件随响应发送完毕后自动清理。 */
    private static final class DeletingInputStream extends FileInputStream {
        private final File file;

        DeletingInputStream(File file) throws IOException {
            super(file);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                file.delete();
            }
        }
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".jsonl")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".txt")
                || lower.endsWith(".log") || lower.endsWith(".cfg") || lower.endsWith(".properties")
                || lower.endsWith(".md") || lower.endsWith(".lang")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    /** Content-Disposition 里不允许出现引号、换行与反斜杠。 */
    private static String safeHeader(String name) {
        return name.replaceAll("[\\r\\n\"\\\\]", "_");
    }

    private static NanoHTTPD.Response jsonError(NanoHTTPD.Response.Status status, String message) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8",
                ApiResponse.error(MODULE, message).toJson());
    }

    // ------------------------------------------------------------------ 路径安全

    private File rootDir(String root) {
        if (root == null || !ROOTS.contains(root)) {
            return null;
        }
        return new File(plugin.getDataFolder(), root);
    }

    /**
     * 把相对路径解析到根目录下的绝对文件，做三层校验：
     * 路径必须是相对的、归一化后必须仍落在根目录内、文件存在时真实路径（穿透符号链接）
     * 也必须仍在根目录内。任何一层不满足都返回 null。
     */
    private File resolve(String root, String rel) {
        File base = rootDir(root);
        if (base == null) {
            return null;
        }
        if (rel == null || rel.isBlank()) {
            return base;
        }
        String cleaned = rel.replace('\\', '/').replaceAll("^/+", "");
        if (cleaned.isEmpty()) {
            return base;
        }
        Path basePath = base.toPath().toAbsolutePath().normalize();
        Path target = basePath.resolve(cleaned).normalize();
        if (!target.startsWith(basePath)) {
            return null;
        }
        File file = target.toFile();
        try {
            if (file.exists() && !file.toPath().toRealPath().startsWith(basePath.toRealPath())) {
                return null;
            }
        } catch (IOException error) {
            return null;
        }
        return file;
    }

    private static boolean isTextFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static int parsePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 审计

    private void audit(NanoHTTPD.IHTTPSession session, String action, String path, long size) {
        plugin.getLogger().warning("[Panel][文件] " + PanelServer.clientIp(session) + " "
                + action + " " + path + (size >= 0 ? "（" + size + " B）" : ""));
    }
}
