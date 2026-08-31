package com.github.cinnaio.essentialengine.module.playerstats;

import com.github.cinnaio.essentialengine.EssentialEngine;
import com.github.cinnaio.essentialengine.core.module.EngineModule;

/** 玩家中心的数据统计模块。 */
public class PlayerStatsModule extends EngineModule {

    private final PlayerStatsService service;

    public PlayerStatsModule(EssentialEngine plugin) {
        super(plugin, "playerstats", "玩家统计");
        this.service = new PlayerStatsService(plugin);
    }

    public PlayerStatsService service() {
        return service;
    }

    @Override
    protected void setup() {
        // 数据生命周期由 core.user.UserManager 统一管理；本模块只提供查询能力。
    }
}
