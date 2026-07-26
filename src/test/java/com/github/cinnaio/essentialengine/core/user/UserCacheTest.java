package com.github.cinnaio.essentialengine.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserCache} 的唯一职责是「同一 UUID 至多一个活实例」。
 * 余额锁挂在实例上，出现第二个实例就意味着锁形同虚设、后写盘的覆盖先写盘的。
 */
class UserCacheTest {

    private final UUID uuid = UUID.randomUUID();

    /** 每次调用都从「磁盘」造一个新实例——正确的缓存应当最多调它一次。 */
    private Function<UUID, UserData> freshReader() {
        return key -> new UserData(key, "Tester");
    }

    @Nested
    @DisplayName("基本转移")
    class Transitions {

        @Test
        void 离线读取后登录_沿用同一实例() {
            UserCache cache = new UserCache();
            UserData offline = cache.loadOffline(uuid, 1L, freshReader());
            UserData online = cache.promote(uuid, "Tester", freshReader());
            assertSame(offline, online, "登录必须沿用离线缓存里的实例，否则未落盘的改动会被读盘覆盖");
        }

        @Test
        void 退出后再读取_沿用同一实例() {
            UserCache cache = new UserCache();
            UserData online = cache.promote(uuid, "Tester", freshReader());
            assertSame(online, cache.demote(uuid, 1L));
            assertSame(online, cache.loadOffline(uuid, 2L, freshReader()),
                    "退出后的读取必须拿到刚转入离线的实例，而不是从盘上读旧数据");
        }

        @Test
        void 存储里没有的玩家返回null且不缓存() {
            UserCache cache = new UserCache();
            assertNull(cache.loadOffline(uuid, 1L, key -> null));
            assertEquals(0, cache.offlineSize());
        }

        @Test
        void 本来就不在线时退出是空操作() {
            UserCache cache = new UserCache();
            assertNull(cache.demote(uuid, 1L));
        }
    }

    @Nested
    @DisplayName("竞态复现")
    class Races {

        /**
         * 回归测试：离线读取的读盘空档里玩家登录。
         *
         * <p>修复前的交错：loadOffline 查完两张表、正在读盘 → 登录路径此刻
         * 看不到任何缓存，自己也去读盘 → 两边各造一个实例。之后离线那份被转账、
         * 又被自动保存整份写盘，直接覆盖在线实例已保存的余额和时长。</p>
         *
         * <p>用闩把「正在读盘」这个瞬间钉死，交错必然发生而不是碰运气。
         * 修复后登录会在分段锁上等读盘结束，两边拿到同一个实例。</p>
         */
        @Test
        @Timeout(10)
        void 读盘空档里登录_不会产生第二个实例() throws Exception {
            UserCache cache = new UserCache();
            CountDownLatch readerEntered = new CountDownLatch(1);
            CountDownLatch loginDone = new CountDownLatch(1);

            Function<UUID, UserData> slowReader = key -> {
                readerEntered.countDown();
                // 给登录线程留出整整一秒去「插队」。修复后它会被锁挡住，
                // 这里等不到 loginDone 是正常的——超时后继续即可
                try {
                    loginDone.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new UserData(key, "FromDisk");
            };

            AtomicReference<UserData> offlineResult = new AtomicReference<>();
            Thread offlineReader = new Thread(() ->
                    offlineResult.set(cache.loadOffline(uuid, 1L, slowReader)));
            offlineReader.start();

            assertTrue(readerEntered.await(5, TimeUnit.SECONDS), "读盘线程没有启动");
            // 此刻 loadOffline 正卡在读盘中——这正是修复前出事的窗口
            UserData online = cache.promote(uuid, "Tester", freshReader());
            loginDone.countDown();
            offlineReader.join();

            assertNotNull(offlineResult.get());
            assertSame(online, offlineResult.get(),
                    "读盘空档里登录后，两条路径必须收敛到同一个实例");
        }

        /**
         * 压力测试：登录 / 退出 / 离线读取随机并发，reader 每次都造新实例。
         * 只要实现正确，reader 至多被调用一次——之后实例只在两张表之间转移，
         * 永远不会被第二次读盘替换掉。
         */
        @RepeatedTest(10)
        @Timeout(30)
        void 并发转移永远只有一个活实例() throws Exception {
            UserCache cache = new UserCache();
            Set<UserData> seen = ConcurrentHashMap.newKeySet();
            AtomicInteger reads = new AtomicInteger();
            Function<UUID, UserData> countingReader = key -> {
                reads.incrementAndGet();
                return new UserData(key, "Tester");
            };

            int threads = 24;
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final int role = i % 3;
                Thread worker = new Thread(() -> {
                    try {
                        go.await();
                        for (int round = 0; round < 200; round++) {
                            switch (role) {
                                case 0 -> seen.add(cache.promote(uuid, "Tester", countingReader));
                                case 1 -> {
                                    UserData data = cache.demote(uuid, round);
                                    if (data != null) {
                                        seen.add(data);
                                    }
                                }
                                default -> {
                                    UserData data = cache.loadOffline(uuid, round, countingReader);
                                    if (data != null) {
                                        seen.add(data);
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            go.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "有线程没跑完");

            assertEquals(1, seen.size(), "出现了多个实例：读-改-写的锁已经锁不住这个账户");
            assertEquals(1, reads.get(), "读盘超过一次，说明缓存在某个交错下丢过实例");
        }
    }

    @Nested
    @DisplayName("淘汰")
    class Sweep {

        @Test
        void 干净且过期的条目被移除() {
            UserCache cache = new UserCache();
            UserData data = cache.loadOffline(uuid, 1L, freshReader());
            data.clearDirty();
            assertEquals(0, cache.sweepOffline(100L, ignored -> true));
            assertEquals(0, cache.offlineSize());
        }

        @Test
        void 脏条目先落盘且这一轮不淘汰() {
            UserCache cache = new UserCache();
            UserData data = cache.loadOffline(uuid, 1L, freshReader());
            data.setBalance(42);   // markDirty
            assertEquals(1, cache.sweepOffline(100L, ignored -> true));
            assertEquals(1, cache.offlineSize(), "脏条目必须等下一轮确认干净了再淘汰");
        }

        @Test
        void 未过期的条目不动() {
            UserCache cache = new UserCache();
            UserData data = cache.loadOffline(uuid, 50L, freshReader());
            data.clearDirty();
            cache.sweepOffline(30L, ignored -> true);
            assertEquals(1, cache.offlineSize());
        }
    }
}
