package com.github.cinnaio.essentialengine.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDataBalanceTest {

    private UserData account(double balance) {
        UserData data = new UserData(UUID.randomUUID(), "Tester");
        data.setBalance(balance);
        return data;
    }

    @Nested
    @DisplayName("基本语义")
    class Basics {

        @Test
        void 余额不会变成负数() {
            UserData data = account(10);
            data.setBalance(-50);
            assertEquals(0, data.getBalance());
        }

        @Test
        void 存取都保留两位小数() {
            UserData data = account(0);
            data.setBalance(10.005);
            assertEquals(10.01, data.getBalance());

            // 0.1 + 0.2 这类浮点误差不能随着交易次数累积下去
            UserData other = account(0.1);
            other.addBalance(0.2);
            assertEquals(0.3, other.getBalance());
        }

        @Test
        void 余额够时扣款成功并返回前后快照() {
            UserData data = account(100);
            UserData.BalanceChange change = data.tryWithdraw(30);
            assertNotNull(change);
            assertEquals(100, change.before());
            assertEquals(70, change.after());
            assertEquals(-30, change.delta());
            assertEquals(70, data.getBalance());
        }

        @Test
        void 余额不足时扣款失败且余额不变() {
            UserData data = account(20);
            assertNull(data.tryWithdraw(20.01));
            assertEquals(20, data.getBalance(), "失败的扣款不能动余额");
        }

        @Test
        void 刚好够时可以扣完() {
            UserData data = account(20);
            assertNotNull(data.tryWithdraw(20));
            assertEquals(0, data.getBalance());
        }

        @Test
        void updateBalance返回的是本次变动的结果() {
            UserData data = account(50);
            UserData.BalanceChange change = data.updateBalance(current -> current * 2);
            assertEquals(50, change.before());
            assertEquals(100, change.after());
            assertEquals(50, change.delta());
        }
    }

    @Nested
    @DisplayName("并发")
    class Concurrency {

        private static final int THREADS = 64;
        private static final double PRICE = 10D;
        private static final double START = 100D;   // 只够买 10 次

        /**
         * 回归测试：并发扣款不能超卖。
         *
         * <p>本插件是 Vault 的经济提供者，任何插件都可能从异步线程扣钱，
         * REST 接口更是直接跑在 HTTP 工作线程上。历史上余额是个裸 double，
         * 「查余额 → 扣款」分两步，同一笔钱能被花两次；用这个用例的场景实测，
         * 200 轮里有 185 轮账目对不上。</p>
         *
         * <p>判据是账目自洽：扣掉的总额必须正好等于成功次数 × 单价，且不透支。</p>
         */
        @RepeatedTest(20)
        void 并发扣款不会超卖() throws Exception {
            UserData data = account(START);
            AtomicInteger succeeded = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);

            for (int i = 0; i < THREADS; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        go.await();
                        if (data.tryWithdraw(PRICE) != null) {
                            succeeded.incrementAndGet();
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "有线程没跑完");

            assertEquals(succeeded.get() * PRICE, START - data.getBalance(),
                    "扣掉的钱必须正好等于成功的扣款次数");
            assertTrue(data.getBalance() >= 0, "余额被扣成了负数");
            assertEquals(START / PRICE, succeeded.get(), "成功次数应当正好是余额能支撑的次数");
        }

        /** 并发存款不能丢更新。 */
        @RepeatedTest(20)
        void 并发存款不会丢更新() throws Exception {
            UserData data = account(0);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);

            for (int i = 0; i < THREADS; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        go.await();
                        data.updateBalance(current -> current + 1);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }

            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "有线程没跑完");
            assertEquals(THREADS, data.getBalance(), "每笔存款都应该落在余额上");
        }
    }
}
