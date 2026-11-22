package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 信令负载守卫单测（指导书 8.7 / 13.5）。
 * 关键契约：frameId 必须放行（帧绑定），像素/图片类键与值必须拒绝。
 */
class AssistPayloadGuardTest {

    @Test
    void allowsFrameBoundAnnotationPayload() {
        Map<String, Object> bounds = new HashMap<>();
        bounds.put("x", 0.1);
        bounds.put("y", 0.2);
        bounds.put("width", 0.3);
        bounds.put("height", 0.1);

        Map<String, Object> payload = new HashMap<>();
        payload.put("frameId", "frame-123");
        payload.put("pageHash", "abc");
        payload.put("packageName", "com.actionloop.guidancetest");
        payload.put("bounds", bounds);
        payload.put("expiresAt", System.currentTimeMillis() + 60_000);
        payload.put("label", "下一步");

        assertDoesNotThrow(() -> AssistPayloadGuard.validate(payload));
    }

    @Test
    void rejectsRawFrameAndImageKeys() {
        for (String key : List.of("frame", "frameData", "image", "imageData", "screenshot", "base64", "dataUrl", "pixels", "bitmap")) {
            Map<String, Object> payload = new HashMap<>();
            payload.put(key, "x");
            assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(payload), key);
        }
    }

    @Test
    void rejectsPixelLikeStringValues() {
        Map<String, Object> dataUrl = new HashMap<>();
        dataUrl.put("annotation", "data:image/png;base64,AAAA");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(dataUrl));

        Map<String, Object> longBase64 = new HashMap<>();
        longBase64.put("annotation", "A".repeat(300));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(longBase64));
    }

    @Test
    void rejectsTooDeepAndTooLargePayloads() {
        Map<String, Object> deep = new HashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 8; i++) {
            Map<String, Object> next = new HashMap<>();
            cursor.put("n", next);
            cursor = next;
        }
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(deep));

        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 201; i++) big.add(i);
        Map<String, Object> wide = new HashMap<>();
        wide.put("points", big);
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(wide));
    }

    @Test
    void rejectsInvalidFrameIdAndTooManyKeys() {
        Map<String, Object> badFrame = new HashMap<>();
        badFrame.put("frameId", "A".repeat(300));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(badFrame));

        Map<String, Object> injection = new HashMap<>();
        injection.put("frameId", "abc/../../etc");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(injection));

        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < 65; i++) tooMany.put("k" + i, i);
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(tooMany));
    }

    @Test
    void rejectsEmptyPayload() {
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(null));
    }
}
