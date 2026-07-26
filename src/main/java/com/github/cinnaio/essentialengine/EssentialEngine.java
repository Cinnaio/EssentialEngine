package com.github.cinnaio.essentialengine;

import com.github.cinnaio.essentialengine.core.CoreListener;
import com.github.cinnaio.essentialengine.core.CoreModule;
import com.github.cinnaio.essentialengine.core.command.CommandManager;
import com.github.cinnaio.essentialengine.core.config.MessageManager;
import com.github.cinnaio.essentialengine.core.module.ModuleManager;
import com.github.cinnaio.essentialengine.core.scheduler.SchedulerCompat;
import com.github.cinnaio.essentialengine.core.storage.MysqlStorage;
import com.github.cinnaio.essentialengine.core.storage.SqliteStorage;
import com.github.cinnaio.essentialengine.core.storage.StorageProvider;
import com.github.cinnaio.essentialengine.core.storage.StorageType;
import com.github.cinnaio.essentialengine.core.storage.YamlStorage;
import com.github.cinnaio.essentialengine.core.user.UserManager;
import com.github.cinnaio.essentialengine.module.admin.AdminModule;
import com.github.cinnaio.essentialengine.module.chat.ChatModule;
import com.github.cinnaio.essentialengine.module.economy.EconomyManager;
import com.github.cinnaio.essentialengine.module.economy.EconomyModule;
import com.github.cinnaio.essentialengine.module.economy.VaultHook;
import com.github.cinnaio.essentialengine.module.husktowns.HuskTownsModule;
import com.github.cinnaio.essentialengine.module.player.PlayerModule;
import com.github.cinnaio.essentialengine.module.teleport.TeleportModule;
import com.github.cinnaio.essentialengine.module.webapi.WebApiModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * EssentialEngine —— 模块化服务器基础功能插件。
 *
 * <p>提供 CMI / EssentialsX 风格的基础功能（传送、玩家指令、聊天、管理、经济），
 * 全部以模块形式内置于同一个插件，可在 config.yml 中逐个开关；
 * 同时保留了可选的 REST API 与 HuskTowns 对接能力。</p>
 *
 * <p>兼容 Paper 与 Folia（1.21.4+）。</p>
 */
public class EssentialEngine extends JavaPlugin {

    private static EssentialEngine instance;

    private MessageManager messages;
    private CommandManager commands;
    private ModuleManager modules;
    private StorageProvider storage;
    private UserManager users;
    private EconomyManager economy;

    private boolean earlyInitDone;
    private boolean earlyInitFailed;
    private boolean vaultRegistered;

    /** 供工具类取用；插件未启用时返回 null。 */
    public static EssentialEngine get() {
        return instance;
    }

    /**
     * onLoad 早于**所有**插件的 onEnable 执行。
     *
     * <p>经济提供者必须赶在这个时间点之前把自己注册进 Vault：
     * 商店、职业、任务这类插件通常在自己的 onEnable 里
     * {@code getServicesManager().getRegistration(Economy.class)}，
     * 如果那时还没有提供者，它们会直接判定「没有经济插件」而自我禁用。
     * 因此配置、存储、玩家管理器和 Vault 注册都提前到这里完成，
     * onEnable 只负责命令、监听器和模块这些必须在服务端就绪后才能做的事。</p>
     */
    @Override
    public void onLoad() {
        earlyInit();
    }

    /** 幂等的早期初始化：onLoad 与 onEnable 都会调用，被 PlugMan 之类重载时也不会漏。 */
    private void earlyInit() {
        if (earlyInitDone || earlyInitFailed) {
            return;
        }
        instance = this;
        saveDefaultConfig();

        this.messages = new MessageManager(this);
        this.messages.reload();

        if (!setupStorage()) {
            earlyInitFailed = true;
            return;
        }

        this.users = new UserManager(this);
        this.economy = new EconomyManager(this);
        this.earlyInitDone = true;

        registerVault();
    }

    /** 把内置经济注册为 Vault 的提供者。已注册过或未启用时静默跳过。 */
    private void registerVault() {
        if (vaultRegistered || economy == null) {
            return;
        }
        if (!getConfig().getBoolean("modules.economy.enabled", true)
                || !getConfig().getBoolean("modules.economy.vault-hook", true)) {
            return;
        }
        vaultRegistered = VaultHook.register(this, economy,
                getConfig().getString("modules.economy.vault-priority", "Normal"));
    }

    @Override
    public void onEnable() {
        earlyInit();
        if (earlyInitFailed) {
            getLogger().severe("存储后端初始化失败，插件已停用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Vault 若是在本插件之后才加载的，onLoad 时会失败，这里补注册一次
        registerVault();

        this.commands = new CommandManager(this);
        if (!commands.init()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.modules = new ModuleManager(this);
        registerModules();

        getServer().getPluginManager().registerEvents(new CoreListener(this), this);
        modules.enableAll();
        users.startAutoSave();

        // 热重载插件时补载已经在线的玩家
        preloadOnlinePlayers();

        getLogger().info("EssentialEngine 已启用 | 存储: " + storage.getName()
                + " | 服务端: " + (SchedulerCompat.isFolia() ? "Folia" : "Paper/Spigot"));
        getLogger().info("已启用模块: " + String.join(", ", modules.getActiveIds()));
    }

    @Override
    public void onDisable() {
        if (vaultRegistered) {
            VaultHook.unregister(this);
            vaultRegistered = false;
        }
        if (modules != null) {
            modules.disableAll();
        }
        if (users != null) {
            users.stop();
        }
        if (storage != null) {
            storage.shutdown();
        }
        SchedulerCompat.cancelAll(this);
        getLogger().info("EssentialEngine 已停用。");
        instance = null;
    }

    private void registerModules() {
        modules.register(new CoreModule(this));
        modules.register(new TeleportModule(this));
        modules.register(new PlayerModule(this));
        modules.register(new ChatModule(this));
        modules.register(new AdminModule(this));
        modules.register(new EconomyModule(this));
        modules.register(new HuskTownsModule(this));
        modules.register(new WebApiModule(this));
    }

    private boolean setupStorage() {
        StorageType type = StorageType.parse(getConfig().getString("storage.type", "yaml"));
        String repository = getConfig().getString("storage.maven-repository", "https://repo1.maven.org/maven2");
        try {
            this.storage = switch (type) {
                case SQLITE -> new SqliteStorage(this,
                        getConfig().getString("storage.sqlite.table-prefix", "ee_"),
                        getConfig().getString("storage.sqlite.file", "database.db"),
                        getConfig().getString("storage.sqlite.driver-version", "3.47.1.0"),
                        repository);
                case MYSQL -> new MysqlStorage(this,
                        getConfig().getString("storage.mysql.table-prefix", "ee_"),
                        getConfig().getString("storage.mysql.host", "127.0.0.1"),
                        getConfig().getInt("storage.mysql.port", 3306),
                        getConfig().getString("storage.mysql.database", "minecraft"),
                        getConfig().getString("storage.mysql.username", "root"),
                        getConfig().getString("storage.mysql.password", ""),
                        getConfig().getString("storage.mysql.parameters",
                                "useSSL=false&characterEncoding=utf8&serverTimezone=UTC"),
                        getConfig().getString("storage.mysql.driver-version", "9.1.0"),
                        repository);
                default -> new YamlStorage(this);
            };
            storage.init();
            return true;
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "初始化 " + type + " 存储失败", error);
            if (type != StorageType.YAML) {
                getLogger().warning("正在回退到 YAML 存储……");
                try {
                    this.storage = new YamlStorage(this);
                    storage.init();
                    return true;
                } catch (Exception fallbackError) {
                    getLogger().log(Level.SEVERE, "YAML 存储也初始化失败", fallbackError);
                }
            }
            return false;
        }
    }

    private void preloadOnlinePlayers() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        SchedulerCompat.runAsync(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (users.getIfLoaded(player.getUniqueId()) == null) {
                    users.loadIntoCache(player.getUniqueId(), player.getName()).startSession();
                }
            }
        });
    }

    /** 重载配置、语言与全部模块。 */
    public void reloadAll() {
        users.flushDirty();
        modules.disableAll();
        reloadConfig();
        messages.reload();
        modules.enableAll();
    }

    // ------------------------------------------------------------------ 访问器

    public MessageManager messages() {
        return messages;
    }

    public CommandManager commands() {
        return commands;
    }

    public ModuleManager modules() {
        return modules;
    }

    public StorageProvider storage() {
        return storage;
    }

    public UserManager users() {
        return users;
    }

    /** 内置经济。onLoad 阶段就已可用，因此 Vault 消费方在自己的 onEnable 里能直接查到。 */
    public EconomyManager economy() {
        return economy;
    }

    public boolean isVaultRegistered() {
        return vaultRegistered;
    }
}
