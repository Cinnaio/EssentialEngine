package com.github.cinnaio.essentialengine.module.husktowns;

import net.william278.husktowns.town.Town;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownServiceTest {

    private static final UUID OFFLINE_PLAYER =
            UUID.fromString("5dab173d-246a-4f25-8590-8d05f3d50b71");

    @Test
    void 离线玩家可以通过城镇成员表找到所属城镇() {
        Town town = Town.builder()
                .id(7)
                .name("测试城镇")
                .members(Map.of(OFFLINE_PLAYER, 3))
                .build();

        Optional<TownService.TownMembership> result =
                TownService.findTownMembership(OFFLINE_PLAYER, List.of(town));

        assertTrue(result.isPresent());
        assertSame(town, result.get().town());
        assertEquals(3, result.get().roleWeight());
    }

    @Test
    void 不在任何城镇的玩家返回空结果() {
        Town town = Town.builder()
                .id(7)
                .name("测试城镇")
                .members(Map.of(UUID.randomUUID(), 3))
                .build();

        assertTrue(TownService.findTownMembership(OFFLINE_PLAYER, List.of(town)).isEmpty());
    }
}
