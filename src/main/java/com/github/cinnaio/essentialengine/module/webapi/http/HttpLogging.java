package com.github.cinnaio.essentialengine.module.webapi.http;

import fi.iki.elonen.NanoHTTPD;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Filter;
import java.util.logging.Logger;

/**
 * 抑制 NanoHTTPD 的「客户端断开」日志。
 *
 * <p>浏览器刷新、关标签页、OAuth 跳走，都会让服务端在写响应写到一半时收到
 * {@code Connection reset by peer} 或 {@code Broken pipe}。这是完全正常的，
 * 但 NanoHTTPD 会按 {@code SEVERE} 打完整堆栈——运营中控制台会被这些红字刷满，
 * 真正的故障反而淹在里面看不见。</p>
 *
 * <p>只过滤异常链里带 socket 异常的记录，其余照常输出；{@code debug: true} 时
 * 完全不过滤，方便排查确实是网络层的问题。</p>
 */
public final class HttpLogging {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /**
     * 强引用住那个 logger。
     *
     * <p>{@code java.util.logging} 对 Logger 只持弱引用，一旦被回收，
     * 挂在上面的 Filter 也就跟着没了。</p>
     */
    private static Logger target;

    private HttpLogging() {
    }

    /**
     * 装上过滤器。重复调用无害，两个 HTTP 模块各调各的即可。
     *
     * @param verbose 返回 true 时不过滤（对应配置里的 {@code debug}）
     */
    public static synchronized void quietClientDisconnects(BooleanSupplier verbose) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        // 类名会被 shadow 重定位，所以从类对象取，不能写死字符串
        target = Logger.getLogger(NanoHTTPD.class.getName());
        Filter previous = target.getFilter();
        target.setFilter(record -> {
            if (verbose.getAsBoolean()) {
                return previous == null || previous.isLoggable(record);
            }
            if (isClientDisconnect(record.getThrown())) {
                return false;
            }
            return previous == null || previous.isLoggable(record);
        });
    }

    /** 判断异常链里是不是 socket 层的断开／超时。 */
    private static boolean isClientDisconnect(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof SocketException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
