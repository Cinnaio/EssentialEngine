package com.github.cinnaio.essentialengine.module.panel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面板的登录与会话管理。
 *
 * <p>登录成功后签发一个随机 token，浏览器把它放在 {@code Authorization: Bearer} 头里
 * 带回来——<b>刻意不用 Cookie</b>：Cookie 会被浏览器自动附加到跨站请求上，
 * 那样就必须再做一层 CSRF 防护，而放在请求头里天然只有同源脚本能读写。</p>
 *
 * <p>密码支持两种写法：明文，或 {@code sha256:<十六进制摘要>}。
 * 后者让服主不必把明文密码留在 config.yml 里。</p>
 */
public class SessionStore {

    /** 会话空闲多久后失效（毫秒），由构造参数决定。 */
    private final long ttlMillis;
    /** 同一 IP 连续失败多少次后锁定。 */
    private final int maxAttempts;
    /** 锁定时长（毫秒）。 */
    private final long lockoutMillis;

    private final String password;
    private final boolean hashed;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public SessionStore(String password, int ttlMinutes, int maxAttempts, int lockoutMinutes) {
        String raw = password == null ? "" : password.trim();
        this.hashed = raw.toLowerCase(Locale.ROOT).startsWith("sha256:");
        this.password = hashed ? raw.substring("sha256:".length()).trim().toLowerCase(Locale.ROOT) : raw;
        this.ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.lockoutMillis = Math.max(1, lockoutMinutes) * 60_000L;
    }

    /** 密码是否可用；为空时面板必须拒绝启动。 */
    public boolean hasPassword() {
        return !password.isEmpty();
    }

    // ------------------------------------------------------------------ 登录

    /** 该 IP 是否处于锁定期。 */
    public boolean isLocked(String ip) {
        Long until = lockedUntil.get(ip);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            lockedUntil.remove(ip);
            failures.remove(ip);
            return false;
        }
        return true;
    }

    /** 锁定还剩多少秒。 */
    public long lockRemainingSeconds(String ip) {
        Long until = lockedUntil.get(ip);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    /**
     * 校验密码并签发会话 token。
     *
     * @return 成功返回 token，失败返回 null
     */
    public String login(String candidate, String ip) {
        if (candidate == null || !hasPassword() || isLocked(ip)) {
            return null;
        }
        if (!matches(candidate)) {
            int count = failures.computeIfAbsent(ip, key -> new AtomicInteger()).incrementAndGet();
            if (count >= maxAttempts) {
                lockedUntil.put(ip, System.currentTimeMillis() + lockoutMillis);
            }
            return null;
        }
        failures.remove(ip);
        lockedUntil.remove(ip);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(token, System.currentTimeMillis() + ttlMillis);
        return token;
    }

    private boolean matches(String candidate) {
        String actual = hashed ? sha256(candidate) : candidate;
        return constantTimeEquals(actual, password);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            // 所有 JVM 都必须支持 SHA-256，走不到这里
            return "";
        }
    }

    /** 定长比较，避免通过响应时间逐字符猜密码。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 会话

    /** 校验 token 并顺延有效期；无效或已过期返回 false。 */
    public boolean validate(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long expiry = sessions.get(token);
        if (expiry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > expiry) {
            sessions.remove(token);
            return false;
        }
        sessions.put(token, now + ttlMillis);
        return true;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 清空全部会话，面板关闭 / 重载时调用。 */
    public void clear() {
        sessions.clear();
        failures.clear();
        lockedUntil.clear();
    }

    public int activeSessions() {
        sessions.values().removeIf(expiry -> System.currentTimeMillis() > expiry);
        return sessions.size();
    }
}
