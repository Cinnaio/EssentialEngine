package com.github.cinnaio.essentialengine.core.command;

/**
 * 命令执行中的“可预期错误”。
 *
 * <p>抛出后由 {@link EngineCommand} 捕获并把对应的消息键发给命令发送者，
 * 这样具体命令逻辑里可以直接 {@code throw new CommandError("teleport.home-not-found", "name", name);}
 * 而不用层层返回。</p>
 */
public class CommandError extends RuntimeException {

    private final String key;
    private final Object[] placeholders;

    public CommandError(String key, Object... placeholders) {
        super(key, null, false, false);
        this.key = key;
        this.placeholders = placeholders == null ? new Object[0] : placeholders;
    }

    public String getKey() {
        return key;
    }

    public Object[] getPlaceholders() {
        return placeholders;
    }
}
