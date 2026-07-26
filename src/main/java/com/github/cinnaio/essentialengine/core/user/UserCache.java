package com.github.cinnaio.essentialengine.core.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 在线 / 离线两张玩家数据表的唯一管理者。
 *
 * <p>要维护的不变式只有一条：<b>同一个 UUID 任何时刻至多对应一个活的
 * {@link UserData} 实例</b>。余额、时长这些字段的锁都挂在实例上，
 * 一旦同一账户出现两个实例，锁就形同虚设——一边改、另一边整份写盘，
 * 先写的改动直接被覆盖。</p>
 *
 * <p>单独一张 {@link ConcurrentHashMap} 自己是原子的，但实例会在两张表之间
 * <b>转移</b>（登录时离线 → 在线，退出时在线 → 离线），转移是「查一张、写另一张」
 * 的复合操作，两次原子操作拼不出一次原子转移。曾经就漏过这样一个交错：
 * 离线读取正在读盘的空档里玩家登录，登录路径没等到那份数据，各自造了一个实例。
 * 因此所有涉及两张表的路径都要拿同一把分段锁。</p>
 *
 * <p>锁是固定 64 段、按 UUID 哈希取段：不会像 per-UUID 锁那样随玩家数增长，
 * 两个玩家撞进同一段的代价也只是偶尔互相等一次盘读。读盘放在锁内是有意的——
 * 这正是「查与写之间不许插队」要保护的窗口；这条路径本身低频，卡不住谁。</p>
 *
 * <p>存储读取通过 {@code reader} 传入，这个类不碰插件与磁盘，竞态因此可以在
 * 单测里用假 reader 复现（见 UserCacheTest）。</p>
 */
final class UserCache {

    private static final int STRIPES = 64;

    /** 带最近访问时间的离线条目。 */
    static final class OfflineEntry {
        final UserData data;
        volatile long touched;

        OfflineEntry(UserData data, long now) {
            this.data = data;
            this.touched = now;
        }
    }

    private final Map<UUID, UserData> online = new ConcurrentHashMap<>();
    private final Map<UUID, OfflineEntry> offline = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[STRIPES];

    UserCache() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(UUID uuid) {
        return locks[Math.floorMod(uuid.hashCode(), STRIPES)];
    }

    // ------------------------------------------------------------------ 读取

    UserData getOnline(UUID uuid) {
        return online.get(uuid);
    }

    Collection<UserData> onlineValues() {
        return online.values();
    }

    /**
     * 登录：把这名玩家提升为在线实例。
     *
     * <p>离线缓存里如果已经有实例（可能压着没落盘的改动，比如离线时被转了账），
     * 必须沿用它——重新读盘会把那笔改动整个抹掉。</p>
     */
    UserData promote(UUID uuid, String name, Function<UUID, UserData> reader) {
        synchronized (lockFor(uuid)) {
            // 已经在线就沿用在线实例——重复登录（或退出事件没来得及跑）时
            // 重新读盘会把内存里未落盘的改动整个顶掉
            UserData data = online.get(uuid);
            if (data == null) {
                OfflineEntry pending = offline.get(uuid);
                data = pending != null ? pending.data : reader.apply(uuid);
            }
            if (data == null) {
                data = new UserData(uuid, name);
            } else if (name != null) {
                data.setName(name);
            }
            // 先进在线表、再出离线表：颠倒过来会出现两张表都查不到的瞬间，
            // 那一瞬间进来的 loadOffline 就会去读盘造出第二个实例
            online.put(uuid, data);
            offline.remove(uuid);
            return data;
        }
    }

    /**
     * 退出：把在线实例转入离线缓存。
     *
     * <p>刚退出的玩家仍可能马上被转账 / 发奖励，异步写盘也未必已落地，
     * 实例必须原样保留，不能让下一次读取从磁盘拿旧数据。</p>
     *
     * @return 被转移的实例；本来就不在线时返回 null
     */
    UserData demote(UUID uuid, long now) {
        synchronized (lockFor(uuid)) {
            UserData data = online.get(uuid);
            if (data == null) {
                return null;
            }
            // 同上：先进离线表再出在线表，保证转移途中始终查得到
            offline.put(uuid, new OfflineEntry(data, now));
            online.remove(uuid);
            return data;
        }
    }

    /**
     * 读离线玩家：优先在线表、其次离线缓存，都没有才读盘并缓存。
     *
     * <p>reader 在锁内执行。同段的其他访问会等这次盘读，这是特意换来的——
     * 「查缓存」与「写缓存」之间不许被登录 / 退出插队。</p>
     *
     * @return 存储里也没有这名玩家时返回 null
     */
    UserData loadOffline(UUID uuid, long now, Function<UUID, UserData> reader) {
        UserData cached = online.get(uuid);
        if (cached != null) {
            return cached;
        }
        synchronized (lockFor(uuid)) {
            // 拿锁期间可能刚好登录了，锁内必须重查
            cached = online.get(uuid);
            if (cached != null) {
                return cached;
            }
            OfflineEntry entry = offline.get(uuid);
            if (entry == null) {
                UserData loaded = reader.apply(uuid);
                if (loaded == null) {
                    return null;
                }
                entry = new OfflineEntry(loaded, now);
                offline.put(uuid, entry);
            }
            entry.touched = now;
            return entry.data;
        }
    }

    // ------------------------------------------------------------------ 维护

    /**
     * 淘汰离线缓存：最近没人访问、且改动已落盘的条目才能移除。
     *
     * <p>脏条目由调用方先写盘、下一轮再淘汰，所以这里见到脏的一律跳过。
     * 移除也拿分段锁——不然刚被 {@code loadOffline} 取到手的实例可能立刻被淘汰，
     * 下一个读取者就会从盘上造出第二个实例。</p>
     *
     * @param deadline 访问时间早于它的才淘汰
     * @param saver    对脏条目执行落盘
     * @return 这一轮写盘的条数
     */
    int sweepOffline(long deadline, Predicate<UserData> saver) {
        int saved = 0;
        for (Map.Entry<UUID, OfflineEntry> candidate : offline.entrySet()) {
            OfflineEntry entry = candidate.getValue();
            if (entry.data.isDirty()) {
                if (saver.test(entry.data)) {
                    saved++;
                }
                continue;
            }
            if (entry.touched >= deadline) {
                continue;
            }
            synchronized (lockFor(candidate.getKey())) {
                OfflineEntry current = offline.get(candidate.getKey());
                if (current != null && !current.data.isDirty() && current.touched < deadline) {
                    offline.remove(candidate.getKey());
                }
            }
        }
        return saved;
    }

    /** 离线缓存的访问时间快照，升序。容量超限时调用方据此抬高淘汰线。 */
    long[] offlineTouchTimes() {
        return offline.values().stream().mapToLong(entry -> entry.touched).sorted().toArray();
    }

    /** 关服清场：取出离线缓存的全部实例并清空。在线表由调用方单独遍历。 */
    List<UserData> drainOffline() {
        List<UserData> all = new ArrayList<>();
        for (OfflineEntry entry : offline.values()) {
            all.add(entry.data);
        }
        offline.clear();
        return all;
    }

    void clearOnline() {
        online.clear();
    }

    int offlineSize() {
        return offline.size();
    }
}
