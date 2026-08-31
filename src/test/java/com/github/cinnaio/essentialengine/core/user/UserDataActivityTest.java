package com.github.cinnaio.essentialengine.core.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserDataActivityTest {

    @Test
    void 会话跨午夜时按日期拆分并可持久化() {
        ZoneId zone = ZoneId.systemDefault();
        long start = ZonedDateTime.of(
                LocalDate.of(2026, 1, 1), java.time.LocalTime.of(23, 59, 59), zone)
                .toInstant().toEpochMilli();
        long end = ZonedDateTime.of(
                LocalDate.of(2026, 1, 2), java.time.LocalTime.of(0, 0, 1), zone)
                .toInstant().toEpochMilli();

        UserData data = new UserData(UUID.randomUUID(), "Tester");
        data.startSession(start);
        assertEquals(2_000L, data.checkpointSession(end));
        assertEquals(2_000L, data.getPlaytime());

        Map<String, Long> activity = data.getActivityByDay();
        assertEquals(1_000L, activity.get("2026-01-01"));
        assertEquals(1_000L, activity.get("2026-01-02"));

        UserData restored = UserData.deserialize(data.getUuid(), data.serialize());
        assertEquals(activity, restored.getActivityByDay());
        assertEquals(2_000L, restored.getPlaytime());
    }
}
