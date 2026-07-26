package com.github.cinnaio.essentialengine.core.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC 驱动运行时加载器。
 *
 * <p>不把 SQLite / MySQL 驱动打进 jar，也不写在 plugin.yml 的 libraries 里
 * （那样即使用 YAML 存储也会强制联网下载，断网时插件直接加载失败）。
 * 这里改成：只有真的选了 SQL 后端时，才去下载对应驱动到
 * {@code plugins/EssentialEngine/libs/}，之后离线也能用。</p>
 *
 * <p>国内服主可以在 config.yml 里把 {@code storage.maven-repository} 换成阿里云镜像。</p>
 */
public final class DependencyLoader {

    private static final Map<String, ClassLoader> LOADED = new HashMap<>();

    private DependencyLoader() {
    }

    /**
     * 加载并实例化一个 JDBC 驱动。
     *
     * @param repository Maven 仓库地址，例如 {@code https://repo1.maven.org/maven2}
     */
    public static Driver loadDriver(Plugin plugin, String repository, String group, String artifact,
                                    String version, String driverClass) throws Exception {
        // 1. 服务端自带就直接用
        try {
            Class<?> found = Class.forName(driverClass);
            return (Driver) found.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
            // 继续走下载流程
        }

        String key = group + ":" + artifact + ":" + version;
        ClassLoader loader = LOADED.get(key);
        if (loader == null) {
            File libs = new File(plugin.getDataFolder(), "libs");
            if (!libs.exists() && !libs.mkdirs()) {
                throw new IllegalStateException("无法创建目录: " + libs.getAbsolutePath());
            }
            File jar = new File(libs, artifact + "-" + version + ".jar");
            if (!jar.exists() || jar.length() == 0) {
                String base = repository == null || repository.isEmpty()
                        ? "https://repo1.maven.org/maven2" : repository;
                if (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                String url = base + "/" + group.replace('.', '/') + "/" + artifact + "/"
                        + version + "/" + artifact + "-" + version + ".jar";
                plugin.getLogger().info("正在下载数据库驱动 " + key + " ...");
                download(url, jar);
                plugin.getLogger().info("驱动下载完成: " + jar.getName());
            }
            loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                    DependencyLoader.class.getClassLoader());
            LOADED.put(key, loader);
        }

        Class<?> found = Class.forName(driverClass, true, loader);
        return (Driver) found.getDeclaredConstructor().newInstance();
    }

    private static void download(String url, File target) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        URL remote = URI.create(url).toURL();
        try (InputStream in = remote.openStream()) {
            Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (temp.length() == 0) {
            Files.deleteIfExists(temp.toPath());
            throw new IllegalStateException("下载到的文件为空: " + url);
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
