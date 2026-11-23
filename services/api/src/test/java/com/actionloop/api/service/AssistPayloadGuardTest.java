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

    // ---- WebRTC 协商（SDP / ICE）----

    private static final String SAMPLE_OFFER = String.join("\r\n",
            "v=0",
            "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=group:BUNDLE 0",
            "a=msid-semantic: WMS actionloop",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "c=IN IP4 0.0.0.0",
            "a=rtcp:9 IN IP4 0.0.0.0",
            "a=ice-ufrag:abcd",
            "a=ice-pwd:1234567890abcdefghijklmnop",
            "a=fingerprint:sha-256 AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89",
            "a=setup:actpass",
            "a=mid:0",
            "a=sendonly",
            "a=rtpmap:96 VP8/90000",
            "");

    @Test
    void allowsSdpOfferAndAnswer() {
        Map<String, Object> offer = new HashMap<>();
        offer.put("sdp", SAMPLE_OFFER);
        offer.put("type", "offer");
        assertDoesNotThrow(() -> AssistPayloadGuard.validate(offer));

        Map<String, Object> answer = new HashMap<>();
        answer.put("sdp", SAMPLE_OFFER.replace("a=sendonly", "a=recvonly"));
        answer.put("type", "answer");
        assertDoesNotThrow(() -> AssistPayloadGuard.validate(answer));
    }

    @Test
    void allowsIceCandidate() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("candidate", "candidate:842163049 1 udp 1677729535 203.0.113.7 54400 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-cost 999");
        payload.put("sdpMid", "0");
        payload.put("sdpMLineIndex", 0);
        assertDoesNotThrow(() -> AssistPayloadGuard.validate(payload));
    }

    @Test
    void rejectsOversizedOrMalformedSdp() {
        Map<String, Object> oversized = new HashMap<>();
        oversized.put("sdp", "v=0\nm=video 9 UDP/TLS/RTP/SAVPF 96\n" + "a=x:1\n".repeat(3000));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(oversized));

        // 不以 v= 开头：防止把任意大文本伪装成 SDP
        Map<String, Object> notSdp = new HashMap<>();
        notSdp.put("sdp", "hello world");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(notSdp));

        // 缺少 m= 媒体段
        Map<String, Object> noMedia = new HashMap<>();
        noMedia.put("sdp", "v=0\r\ns=-\r\n");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(noMedia));

        // 单行过长（疑似把整段像素塞进一条属性）
        Map<String, Object> longLine = new HashMap<>();
        longLine.put("sdp", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=" + "x".repeat(700));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(longLine));
    }

    @Test
    void rejectsPixelDataSmuggledInsideSdpOrCandidate() {
        Map<String, Object> dataUrl = new HashMap<>();
        dataUrl.put("sdp", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=note:data:image/png;base64,AAAA");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(dataUrl));

        Map<String, Object> base64Run = new HashMap<>();
        base64Run.put("sdp", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=note:" + "A".repeat(300));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(base64Run));

        Map<String, Object> badCandidate = new HashMap<>();
        badCandidate.put("candidate", "data:image/png;base64,AAAA");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(badCandidate));

        Map<String, Object> multilineCandidate = new HashMap<>();
        multilineCandidate.put("candidate", "candidate:1 1 udp 1 127.0.0.1 1 typ host\nsdp");
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(multilineCandidate));

        Map<String, Object> oversizedCandidate = new HashMap<>();
        oversizedCandidate.put("candidate", "candidate:" + "1".repeat(1200));
        assertThrows(BusinessException.class, () -> AssistPayloadGuard.validate(oversizedCandidate));
    }
}
