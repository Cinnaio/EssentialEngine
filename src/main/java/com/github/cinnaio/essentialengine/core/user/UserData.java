package com.github.cinnaio.essentialengine.core.user;

import com.github.cinnaio.essentialengine.core.util.LocationUtil;
import org.bukkit.Location;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;

/**
 * 一名玩家的全部持久化数据。
 *
 * <p>{@link #serialize()} / {@link #deserialize} 是三种存储后端唯一的数据格式，
 * 换后端时结构不变，可以直接互相迁移。</p>
 */
public class UserData {

    private final UUID uuid;

    private String name = "";
    private String nickname;
    /** 客户端语言标签（如 zh_cn），用于登录拦截画面等客户端设置尚未同步的场景。 */
    private String clientLocale;
    private double balance;
    private long firstJoin;
    private long lastLogin;
    private long lastSeen;
    private long playtime;
    /** 按服务器本地日期累计的在线时长，用于玩家中心趋势和活跃排行。 */
    private final Map<String, Long> activityByDay = new LinkedHashMap<>();

    private final Map<String, Map<String, Object>> homes = new LinkedHashMap<>();
    private Map<String, Object> lastLocation;
    private Map<String, Object> logoutLocation;

    private boolean godMode;
    private boolean flightEnabled;
    private boolean vanished;
    private boolean socialSpy;
    private boolean acceptingMessages = true;

    private final Set<UUID> ignored = new LinkedHashSet<>();
    private final List<String> mails = new ArrayList<>();
    private final Map<String, Long> kitCooldowns = new LinkedHashMap<>();

    private boolean banned;
    private String banReason = "";
    private String banSource = "";
    private long banExpiry;

    private boolean muted;
    private String muteReason = "";
    private String muteSource = "";
    private long muteExpiry;

    // ---- 仅运行期存在，不落盘 ----
    private transient boolean afk;
    private transient long lastActivity = System.currentTimeMillis();
    private transient long sessionStart;
    /** 已经结算进 playtime / activityByDay 的本次会话时间点。 */
    private transient long sessionCheckpoint;
    /** 本次会话已结算的时长，供占位符等调用方读取完整会话时长。 */
    private transient long sessionPlaytime;
    private transient UUID replyTarget;
    private transient volatile boolean dirty;

    public UserData(UUID uuid) {
        this.uuid = uuid;
    }

    public UserData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        long now = System.currentTimeMillis();
        this.firstJoin = now;
        this.lastLogin = now;
        this.lastSeen = now;
        this.dirty = true;
    }

    // ------------------------------------------------------------------ 基本属性

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        if (value != null && !value.equals(this.name)) {
            this.name = value;
            markDirty();
        }
    }

    /** 昵称，未设置时返回 null。 */
    public String getNickname() {
        return nickname;
    }

    public void setNickname(String value) {
        this.nickname = value == null || value.isEmpty() ? null : value;
        markDirty();
    }

    /** 展示用名字：有昵称用昵称，否则用玩家名。 */
    public String getDisplayName() {
        return nickname == null ? name : nickname;
    }

    /** 最近一次同步到的客户端语言标签（小写，如 zh_cn），未知时返回 null。 */
    public String getClientLocale() {
        return clientLocale;
    }

    public void setClientLocale(String value) {
        String normalized = value == null || value.isEmpty()
                ? null : value.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!java.util.Objects.equals(this.clientLocale, normalized)) {
            this.clientLocale = normalized;
            markDirty();
        }
    }

    // ------------------------------------------------------------------ 余额
    //
    // 余额的读写全部在 this 上加锁。本插件是 Vault 的经济提供者，任何插件都可能
    // 从异步线程调进来改钱，REST 接口更是直接跑在 HTTP 工作线程上；裸的 double
    // 字段既没有跨线程可见性，也挡不住「查余额 → 扣款」中间被插进另一次扣款
    // ——那会让同一笔钱被花两次。需要读-改-写的场景一律用下面的原子方法，
    // 不要在外面自己拼 getBalance() + setBalance()。

    /** 一次余额变动的前后快照。 */
    public record BalanceChange(double before, double after) {
        public double delta() {
            return after - before;
        }
    }

    /** 金额统一保留两位小数，避免浮点误差随着交易次数累积。 */
    public static double roundMoney(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public synchronized double getBalance() {
        return balance;
    }

    public synchronized void setBalance(double value) {
        this.balance = Math.max(0D, roundMoney(value));
        markDirty();
    }

    public synchronized void addBalance(double amount) {
        setBalance(this.balance + amount);
    }

    /**
     * 原子地按 operator 计算新余额。
     *
     * <p>返回变动前后的值，这样调用方记流水时用的是<b>这次变动</b>的结果，
     * 而不是改完再查一次——中间可能已经被别的线程改过，记出来的数对不上。</p>
     */
    public synchronized BalanceChange updateBalance(DoubleUnaryOperator operator) {
        double before = balance;
        setBalance(operator.applyAsDouble(before));
        return new BalanceChange(before, balance);
    }

    /**
     * 原子扣款：余额足够才扣。
     *
     * @return 扣款成功时返回前后余额，余额不足时返回 {@code null}（余额保持不变）
     */
    public synchronized BalanceChange tryWithdraw(double amount) {
        double cost = roundMoney(amount);
        if (balance < cost) {
            return null;
        }
        return updateBalance(current -> current - cost);
    }

    public long getFirstJoin() {
        return firstJoin;
    }

    public void setFirstJoin(long value) {
        this.firstJoin = value;
        markDirty();
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(long value) {
        this.lastLogin = value;
        markDirty();
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long value) {
        this.lastSeen = value;
        markDirty();
    }

    /** 累计在线时长（毫秒），不含尚未结算的本次会话尾段。 */
    public synchronized long getPlaytime() {
        return playtime;
    }

    public synchronized void addPlaytime(long millis) {
        if (millis > 0) {
            this.playtime += millis;
            markDirty();
        }
    }

    /** 累计在线时长（毫秒），含本次会话尚未结算的尾段。 */
    public synchronized long getTotalPlaytime() {
        if (sessionStart <= 0) {
            return playtime;
        }
        long checkpoint = sessionCheckpoint > 0 ? sessionCheckpoint : sessionStart;
        return playtime + Math.max(0L, System.currentTimeMillis() - checkpoint);
    }

    /** 本次会话时长（含已自动保存的部分和未结算的尾段）。 */
    public synchronized long getSessionPlaytime() {
        if (sessionStart <= 0) {
            return 0L;
        }
        long checkpoint = sessionCheckpoint > 0 ? sessionCheckpoint : sessionStart;
        return sessionPlaytime + Math.max(0L, System.currentTimeMillis() - checkpoint);
    }

    /**
     * 返回按服务器本地日期累计的在线时长。
     *
     * <p>返回副本，避免 Web API 或其它异步调用方修改玩家内部状态。</p>
     */
    public synchronized Map<String, Long> getActivityByDay() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activityByDay));
    }

    // ------------------------------------------------------------------ 家

    public Location getHome(String homeName) {
        Map<String, Object> raw = homes.get(key(homeName));
        return raw == null ? null : LocationUtil.deserialize(raw);
    }

    public boolean hasHome(String homeName) {
        return homes.containsKey(key(homeName));
    }

    public void setHome(String homeName, Location location) {
        Map<String, Object> serialized = LocationUtil.serialize(location);
        if (serialized != null) {
            homes.put(key(homeName), serialized);
            markDirty();
        }
    }

    public boolean removeHome(String homeName) {
        boolean removed = homes.remove(key(homeName)) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public Set<String> getHomeNames() {
        return Collections.unmodifiableSet(homes.keySet());
    }

    public int getHomeCount() {
        return homes.size();
    }

    // ------------------------------------------------------------------ 坐标

    public Location getLastLocation() {
        return lastLocation == null ? null : LocationUtil.deserialize(lastLocation);
    }

    public void setLastLocation(Location location) {
        this.lastLocation = LocationUtil.serialize(location);
        markDirty();
    }

    public Location getLogoutLocation() {
        return logoutLocation == null ? null : LocationUtil.deserialize(logoutLocation);
    }

    public void setLogoutLocation(Location location) {
        this.logoutLocation = LocationUtil.serialize(location);
        markDirty();
    }

    // ------------------------------------------------------------------ 开关

    public boolean isGodMode() {
        return godMode;
    }

    public void setGodMode(boolean value) {
        this.godMode = value;
        markDirty();
    }

    public boolean isFlightEnabled() {
        return flightEnabled;
    }

    public void setFlightEnabled(boolean value) {
        this.flightEnabled = value;
        markDirty();
    }

    public boolean isVanished() {
        return vanished;
    }

    public void setVanished(boolean value) {
        this.vanished = value;
        markDirty();
    }

    public boolean isSocialSpy() {
        return socialSpy;
    }

    public void setSocialSpy(boolean value) {
        this.socialSpy = value;
        markDirty();
    }

    public boolean isAcceptingMessages() {
        return acceptingMessages;
    }

    public void setAcceptingMessages(boolean value) {
        this.acceptingMessages = value;
        markDirty();
    }

    // ------------------------------------------------------------------ 屏蔽与邮件

    public Set<UUID> getIgnored() {
        return Collections.unmodifiableSet(ignored);
    }

    public boolean isIgnoring(UUID other) {
        return ignored.contains(other);
    }

    public boolean toggleIgnore(UUID other) {
        boolean added;
        if (ignored.contains(other)) {
            ignored.remove(other);
            added = false;
        } else {
            ignored.add(other);
            added = true;
        }
        markDirty();
        return added;
    }

    public List<String> getMails() {
        return Collections.unmodifiableList(mails);
    }

    public void addMail(String entry) {
        mails.add(entry);
        markDirty();
    }

    public void clearMails() {
        mails.clear();
        markDirty();
    }

    // ------------------------------------------------------------------ 套装冷却

    /** 上次领取该套装的时间戳，从未领取返回 0。 */
    public long getKitUsed(String kitName) {
        Long value = kitCooldowns.get(key(kitName));
        return value == null ? 0L : value;
    }

    public void markKitUsed(String kitName) {
        kitCooldowns.put(key(kitName), System.currentTimeMillis());
        markDirty();
    }

    // ------------------------------------------------------------------ 封禁 / 禁言

    public boolean isBanned() {
        if (!banned) {
            return false;
        }
        if (banExpiry > 0 && System.currentTimeMillis() > banExpiry) {
            clearBan();
            return false;
        }
        return true;
    }

    public void setBan(String reason, String source, long expiry) {
        this.banned = true;
        this.banReason = reason == null ? "" : reason;
        this.banSource = source == null ? "" : source;
        this.banExpiry = expiry;
        markDirty();
    }

    public void clearBan() {
        this.banned = false;
        this.banReason = "";
        this.banSource = "";
        this.banExpiry = 0;
        markDirty();
    }

    public String getBanReason() {
        return banReason;
    }

    public String getBanSource() {
        return banSource;
    }

    /** 0 表示永久。 */
    public long getBanExpiry() {
        return banExpiry;
    }

    public boolean isMuted() {
        if (!muted) {
            return false;
        }
        if (muteExpiry > 0 && System.currentTimeMillis() > muteExpiry) {
            clearMute();
            return false;
        }
        return true;
    }

    public void setMute(String reason, String source, long expiry) {
        this.muted = true;
        this.muteReason = reason == null ? "" : reason;
        this.muteSource = source == null ? "" : source;
        this.muteExpiry = expiry;
        markDirty();
    }

    public void clearMute() {
        this.muted = false;
        this.muteReason = "";
        this.muteSource = "";
        this.muteExpiry = 0;
        markDirty();
    }

    public String getMuteReason() {
        return muteReason;
    }

    public String getMuteSource() {
        return muteSource;
    }

    public long getMuteExpiry() {
        return muteExpiry;
    }

    // ------------------------------------------------------------------ 运行期状态

    public boolean isAfk() {
        return afk;
    }

    public void setAfk(boolean value) {
        this.afk = value;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void touchActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public void startSession() {
        startSession(System.currentTimeMillis());
    }

    /** 使用指定时间开始会话，便于时间边界测试。 */
    public synchronized void startSession(long now) {
        this.sessionStart = now;
        this.sessionCheckpoint = now;
        this.sessionPlaytime = 0L;
        this.lastActivity = now;
    }

    /**
     * 把本次会话自上次检查点以来的时间结算进总时长和每日统计。
     *
     * <p>自动保存会周期性调用它，所以正常关服之外的异常退出也只会损失最后一个
     * 自动保存周期，而不是整段会话。</p>
     */
    public synchronized long checkpointSession() {
        return checkpointSession(System.currentTimeMillis());
    }

    /** 使用指定时间结算会话，便于时间边界测试。 */
    public synchronized long checkpointSession(long now) {
        if (sessionStart <= 0) {
            return 0L;
        }
        long from = sessionCheckpoint > 0 ? sessionCheckpoint : sessionStart;
        if (now <= from) {
            return 0L;
        }

        recordActivity(from, now);
        long elapsed = now - from;
        playtime += elapsed;
        sessionPlaytime += elapsed;
        sessionCheckpoint = now;
        markDirty();
        return elapsed;
    }

    public synchronized void endSession() {
        if (sessionStart > 0) {
            checkpointSession(System.currentTimeMillis());
            sessionStart = 0;
            sessionCheckpoint = 0;
            sessionPlaytime = 0;
        }
    }

    /** 把一段时间按本地日期拆分，跨午夜时不会把全部时长记到同一天。 */
    private void recordActivity(long from, long to) {
        ZoneId zone = ZoneId.systemDefault();
        long cursor = from;
        while (cursor < to) {
            LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
            long nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(to, nextDay);
            long duration = end - cursor;
            if (duration > 0) {
                String key = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
                activityByDay.merge(key, duration, Long::sum);
            }
            cursor = end;
        }
    }

    public UUID getReplyTarget() {
        return replyTarget;
    }

    public void setReplyTarget(UUID value) {
        this.replyTarget = value;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    private static String key(String raw) {
        return raw == null ? "home" : raw.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 序列化

    public synchronized Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", uuid.toString());
        map.put("name", name);
        if (nickname != null) {
            map.put("nickname", nickname);
        }
        if (clientLocale != null) {
            map.put("locale", clientLocale);
        }
        map.put("balance", balance);
        map.put("first-join", firstJoin);
        map.put("last-login", lastLogin);
        map.put("last-seen", lastSeen);
        map.put("playtime", playtime);
        if (!activityByDay.isEmpty()) {
            map.put("activity-by-day", new LinkedHashMap<>(activityByDay));
        }

        if (!homes.isEmpty()) {
            map.put("homes", new LinkedHashMap<>(homes));
        }
        if (lastLocation != null) {
            map.put("last-location", lastLocation);
        }
        if (logoutLocation != null) {
            map.put("logout-location", logoutLocation);
        }

        map.put("god-mode", godMode);
        map.put("flight", flightEnabled);
        map.put("vanished", vanished);
        map.put("social-spy", socialSpy);
        map.put("accepting-messages", acceptingMessages);

        if (!ignored.isEmpty()) {
            List<String> list = new ArrayList<>();
            for (UUID id : ignored) {
                list.add(id.toString());
            }
            map.put("ignored", list);
        }
        if (!mails.isEmpty()) {
            map.put("mails", new ArrayList<>(mails));
        }
        if (!kitCooldowns.isEmpty()) {
            map.put("kit-cooldowns", new LinkedHashMap<>(kitCooldowns));
        }

        if (banned) {
            Map<String, Object> ban = new LinkedHashMap<>();
            ban.put("reason", banReason);
            ban.put("source", banSource);
            ban.put("expiry", banExpiry);
            map.put("ban", ban);
        }
        if (muted) {
            Map<String, Object> mute = new LinkedHashMap<>();
            mute.put("reason", muteReason);
            mute.put("source", muteSource);
            mute.put("expiry", muteExpiry);
            map.put("mute", mute);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static UserData deserialize(UUID uuid, Map<String, Object> map) {
        UserData data = new UserData(uuid);
        if (map == null) {
            return data;
        }
        data.name = str(map.get("name"), "");
        data.nickname = map.get("nickname") == null ? null : str(map.get("nickname"), null);
        data.clientLocale = map.get("locale") == null ? null : str(map.get("locale"), null);
        data.balance = num(map.get("balance"), 0D);
        data.firstJoin = (long) num(map.get("first-join"), 0D);
        data.lastLogin = (long) num(map.get("last-login"), 0D);
        data.lastSeen = (long) num(map.get("last-seen"), 0D);
        data.playtime = (long) num(map.get("playtime"), 0D);

        Object activity = map.get("activity-by-day");
        if (activity instanceof Map<?, ?> activityMap) {
            for (Map.Entry<?, ?> entry : activityMap.entrySet()) {
                long duration = (long) num(entry.getValue(), 0D);
                if (duration > 0) {
                    data.activityByDay.put(String.valueOf(entry.getKey()), duration);
                }
            }
        }

        Object homes = map.get("homes");
        if (homes instanceof Map<?, ?> homeMap) {
            for (Map.Entry<?, ?> entry : homeMap.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    data.homes.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT),
                            (Map<String, Object>) value);
                }
            }
        }
        if (map.get("last-location") instanceof Map<?, ?> last) {
            data.lastLocation = (Map<String, Object>) last;
        }
        if (map.get("logout-location") instanceof Map<?, ?> logout) {
            data.logoutLocation = (Map<String, Object>) logout;
        }

        data.godMode = bool(map.get("god-mode"), false);
        data.flightEnabled = bool(map.get("flight"), false);
        data.vanished = bool(map.get("vanished"), false);
        data.socialSpy = bool(map.get("social-spy"), false);
        data.acceptingMessages = bool(map.get("accepting-messages"), true);

        if (map.get("ignored") instanceof List<?> list) {
            for (Object entry : list) {
                try {
                    data.ignored.add(UUID.fromString(String.valueOf(entry)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (map.get("mails") instanceof List<?> list) {
            for (Object entry : list) {
                data.mails.add(String.valueOf(entry));
            }
        }
        if (map.get("kit-cooldowns") instanceof Map<?, ?> kits) {
            for (Map.Entry<?, ?> entry : kits.entrySet()) {
                data.kitCooldowns.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT),
                        (long) num(entry.getValue(), 0D));
            }
        }

        if (map.get("ban") instanceof Map<?, ?> ban) {
            data.banned = true;
            data.banReason = str(((Map<String, Object>) ban).get("reason"), "");
            data.banSource = str(((Map<String, Object>) ban).get("source"), "");
            data.banExpiry = (long) num(((Map<String, Object>) ban).get("expiry"), 0D);
        }
        if (map.get("mute") instanceof Map<?, ?> mute) {
            data.muted = true;
            data.muteReason = str(((Map<String, Object>) mute).get("reason"), "");
            data.muteSource = str(((Map<String, Object>) mute).get("source"), "");
            data.muteExpiry = (long) num(((Map<String, Object>) mute).get("expiry"), 0D);
        }
        data.clearDirty();
        return data;
    }

    /** JSON 反序列化出来的数字一律是 Double，这里统一兜底。 */
    private static double num(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return fallback;
    }

    /** 供存储层判空使用。 */
    public static Set<String> knownKeys() {
        return new HashSet<>(List.of("uuid", "name", "balance"));
    }
}
