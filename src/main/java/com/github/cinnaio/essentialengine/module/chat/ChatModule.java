package com.github.cinnaio.essentialengine.module.chat;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.command.CommandError;
import com.github.cinnaio.essentialengine.core.module.EngineModule;
import com.github.cinnaio.essentialengine.core.user.UserData;
import com.github.cinnaio.essentialengine.core.util.PlayerUtil;
import com.github.cinnaio.essentialengine.core.util.Text;
import com.github.cinnaio.essentialengine.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天与消息模块：私聊、昵称、广播、AFK、邮件、屏蔽。
 */
public class ChatModule extends EngineModule {

    private static final String PERM = "essentialengine.command.";
    /** 邮件条目内部分隔符（正常聊天不可能输入的控制字符）。 */
    private static final String MAIL_SEP = String.valueOf((char) 1);
    private ChatManager manager;

    public ChatModule(EssentialEngine plugin) {
        super(plugin, "chat", "聊天与消息");
    }

    public ChatManager getManager() {
        return manager;
    }

    @Override
    protected void setup() {
        this.manager = new ChatManager(plugin);
        manager.start();
        listener(new ChatListener(plugin, manager));

        command("msg").aliases("tell", "whisper", "w", "m").permission(PERM + "msg")
                .description("私聊").usage("/msg <玩家> <内容>").minArgs(2)
                .handler(this::msg)
                .completer((sender, args) -> args.length <= 1 ? PlayerUtil.visibleNames(sender) : List.of());

        command("reply").aliases("r").permission(PERM + "reply")
                .description("回复上一条私聊").usage("/reply <内容>").minArgs(1)
                .handler(this::reply);

        command("msgtoggle").aliases("tptoggle-msg", "togglemsg").playerOnly().permission(PERM + "msgtoggle")
                .description("开关接收私聊").usage("/msgtoggle").handler(this::msgToggle);

        command("socialspy").aliases("ss").playerOnly().permission(PERM + "socialspy")
                .description("监听私聊").usage("/socialspy [on|off]").handler(this::socialSpy);

        command("ignore").aliases("block").playerOnly().permission(PERM + "ignore")
                .description("屏蔽某位玩家").usage("/ignore <玩家>").minArgs(1)
                .handler(this::ignore)
                .completer((sender, args) -> PlayerUtil.visibleNames(sender));

        command("nick").aliases("nickname").permission(PERM + "nick")
                .description("设置昵称").usage("/nick <昵称|off> [玩家]").minArgs(1)
                .handler(this::nick);

        command("broadcast").aliases("bc", "announce").permission(PERM + "broadcast")
                .description("全服广播").usage("/broadcast <内容>").minArgs(1)
                .handler(this::broadcast);

        command("me").aliases("action").playerOnly().permission(PERM + "me")
                .description("以第三人称发言").usage("/me <内容>").minArgs(1)
                .handler(this::me);

        command("afk").aliases("away").playerOnly().permission(PERM + "afk")
                .description("切换离开状态").usage("/afk [理由]").handler(this::afk);

        command("mail").aliases("email").playerOnly().permission(PERM + "mail")
                .description("离线邮件").usage("/mail <read|send|clear> [玩家] [内容]")
                .handler(this::mail)
                .completer((sender, args) -> args.length <= 1
                        ? List.of("read", "send", "clear") : PlayerUtil.visibleNames(sender));
    }

    @Override
    protected void shutdown() {
        if (manager != null) {
            manager.stop();
        }
    }

    // ------------------------------------------------------------------ 私聊

    private void msg(CommandSender sender, String label, String[] args) {
        Player target = PlayerUtil.requireOnline(sender, args[0]);
        if (target.equals(sender)) {
            throw new CommandError("chat.message-self");
        }
        manager.sendPrivate(sender, target, join(args, 1));
    }

    private void reply(CommandSender sender, String label, String[] args) {
        UUID targetId = null;
        if (sender instanceof Player player) {
            targetId = plugin.users().get(player).getReplyTarget();
        }
        if (targetId == null) {
            throw new CommandError("chat.no-reply-target");
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !PlayerUtil.canSee(sender, target)) {
            throw new CommandError("chat.reply-target-offline");
        }
        manager.sendPrivate(sender, target, join(args, 0));
    }

    private void msgToggle(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        boolean value = PlayerUtil.parseToggle(args.length > 0 ? args[0] : null, data.isAcceptingMessages());
        data.setAcceptingMessages(value);
        plugin.messages().send(sender, value ? "chat.msgtoggle-on" : "chat.msgtoggle-off");
    }

    private void socialSpy(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        if (!player.hasPermission(ChatManager.SPY_PERMISSION)) {
            throw new CommandError("general.no-permission", "permission", ChatManager.SPY_PERMISSION);
        }
        UserData data = plugin.users().get(player);
        boolean value = PlayerUtil.parseToggle(args.length > 0 ? args[0] : null, data.isSocialSpy());
        data.setSocialSpy(value);
        plugin.messages().send(sender, value ? "chat.socialspy-on" : "chat.socialspy-off");
    }

    private void ignore(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData self = plugin.users().get(player);
        plugin.users().lookup(sender, args[0], other -> {
            if (other.getUuid().equals(player.getUniqueId())) {
                plugin.messages().send(sender, "chat.ignore-self");
                return;
            }
            boolean added = self.toggleIgnore(other.getUuid());
            plugin.messages().send(sender, added ? "chat.ignore-added" : "chat.ignore-removed",
                    "player", other.getName());
        });
    }

    // ------------------------------------------------------------------ 昵称

    private void nick(CommandSender sender, String label, String[] args) {
        Player target;
        String value = args[0];
        if (args.length > 1) {
            if (!sender.hasPermission(PERM + "nick.others")) {
                throw new CommandError("general.no-permission", "permission", PERM + "nick.others");
            }
            target = PlayerUtil.requireOnline(sender, args[1]);
        } else {
            target = PlayerUtil.requirePlayer(sender);
        }

        UserData data = plugin.users().get(target);
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("none") || value.equals("取消")) {
            data.setNickname(null);
            target.displayName(Text.parse(target.getName()));
            target.playerListName(Text.parse(target.getName()));
            plugin.messages().send(sender, "chat.nick-reset", "player", target.getName());
            return;
        }
        if (!sender.hasPermission(PERM + "nick.color")) {
            value = value.replace('&', '＆').replace('§', '＆');
        }
        int max = cfgInt("nick-max-length", 16);
        if (Text.plain(value).length() > max) {
            throw new CommandError("chat.nick-too-long", "max", String.valueOf(max));
        }
        data.setNickname(value);
        target.displayName(Text.parse(value));
        target.playerListName(Text.parse(value));
        plugin.messages().send(sender, "chat.nick-set", "player", target.getName(), "nick", value);
    }

    // ------------------------------------------------------------------ 广播与 /me

    private void broadcast(CommandSender sender, String label, String[] args) {
        String format = cfgString("broadcast-format", "&8[&c广播&8] &f{message}");
        String message = join(args, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.messages().sendRaw(player, format, "message", message);
        }
        plugin.messages().sendRaw(Bukkit.getConsoleSender(), format, "message", message);
    }

    private void me(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        if (data.isMuted()) {
            throw new CommandError("admin.you-are-muted",
                    "reason", data.getMuteReason(),
                    "time", data.getMuteExpiry() <= 0 ? "永久"
                            : TimeUtil.formatDuration(data.getMuteExpiry() - System.currentTimeMillis()));
        }
        String format = cfgString("me-format", "&d* {player} &f{message}");
        String message = manager.colorize(sender, join(args, 0));
        for (Player online : Bukkit.getOnlinePlayers()) {
            plugin.messages().sendRaw(online, format,
                    "player", PlayerUtil.display(player), "message", message);
        }
    }

    // ------------------------------------------------------------------ AFK

    private void afk(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData data = plugin.users().get(player);
        manager.setAfk(player, data, !data.isAfk(), args.length > 0 ? join(args, 0) : null);
    }

    // ------------------------------------------------------------------ 邮件

    private void mail(CommandSender sender, String label, String[] args) {
        Player player = PlayerUtil.requirePlayer(sender);
        UserData self = plugin.users().get(player);
        String action = args.length == 0 ? "read" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "clear" -> {
                self.clearMails();
                plugin.messages().send(sender, "chat.mail-cleared");
            }
            case "send" -> {
                if (args.length < 3) {
                    throw new CommandError("general.usage", "usage", "/mail send <玩家> <内容>");
                }
                String content = join(args, 2);
                plugin.users().lookup(sender, args[1], target -> {
                    int max = cfgInt("mail-max", 30);
                    if (target.getMails().size() >= max) {
                        plugin.messages().send(sender, "chat.mail-box-full", "player", target.getName());
                        return;
                    }
                    target.addMail(System.currentTimeMillis() + MAIL_SEP + player.getName() + MAIL_SEP + content);
                    plugin.messages().send(sender, "chat.mail-sent", "player", target.getName());
                    Player online = Bukkit.getPlayer(target.getUuid());
                    if (online != null) {
                        plugin.messages().send(online, "chat.mail-received", "player", player.getName());
                    }
                });
            }
            default -> {
                List<String> mails = self.getMails();
                if (mails.isEmpty()) {
                    plugin.messages().send(sender, "chat.mail-empty");
                    return;
                }
                plugin.messages().send(sender, "chat.mail-header", "count", String.valueOf(mails.size()));
                for (String entry : mails) {
                    String[] parts = entry.split(MAIL_SEP, 3);
                    long time = 0;
                    try {
                        time = Long.parseLong(parts[0]);
                    } catch (Exception ignored) {
                    }
                    plugin.messages().send(sender, "chat.mail-entry",
                            "time", TimeUtil.formatDate(time),
                            "sender", parts.length > 1 ? parts[1] : "?",
                            "message", parts.length > 2 ? parts[2] : entry);
                }
            }
        }
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
