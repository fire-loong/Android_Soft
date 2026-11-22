package com.actionloop.shared.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidV7Test {

    @Test
    void generatesVersion7AndIsTimeOrdered() throws InterruptedException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(UuidV7.generate());
            if (i == 100) {
                Thread.sleep(2);
            }
        }
        for (UUID id : ids) {
            assertEquals(7, id.version(), "must be version 7");
            assertEquals(2, id.variant(), "must be RFC 4122 variant");
        }
        // 时间戳单调不下降
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1).compareTo(ids.get(i)) <= 0, "uuid v7 should be time-ordered");
        }
    }
}
