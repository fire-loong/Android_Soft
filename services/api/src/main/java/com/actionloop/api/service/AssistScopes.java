package com.actionloop.api.service;

import java.util.Locale;
import java.util.Set;

/**
 * 协助信令白名单（指导书 8.4/8.6）。服务端是最终裁决者：客户端即使伪造 CLICK 也必须被拒绝并审计。
 * 任何控制类事件都不存在执行处理器，只允许“看/标注/文字提示”。
 */
public final class AssistScopes {

    public static final Set<String> ALLOWED_SCOPES = Set.of(
            "VIEW_SCREEN", "HIGHLIGHT", "TEXT_HINT", "VOICE_HINT", "REMOTE_POINTER");

    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "OFFER", "ANSWER", "ICE_CANDIDATE", "HIGHLIGHT", "HIGHLIGHT_CLEAR",
            "POINTER", "TEXT_HINT", "PAUSE", "RESUME", "RESUME_REQUEST",
            "STOP", "HEARTBEAT", "QUALITY", "META");

    /**
     * 即使大小写混写、加连字符/下划线/空格也必须判为禁止；这些能力在本产品中永久不存在。
     * 集合内保存的是「已剥离分隔符并大写」的规范形式，匹配前统一 normalize，避免
     * COMMIT-PAYMENT / commit_payment 逃逸为“未知事件”而绕过禁止类安全审计。
     */
    private static final Set<String> FORBIDDEN_EVENTS = Set.of(
            "CLICK", "TAP", "SWIPE", "SCROLL", "INPUT", "TYPE", "KEYEVENT",
            "COMMITPAYMENT", "PAYMENT", "DISPATCHGESTURE", "PERFORMACTION", "CONTROL", "REMOTECONTROL");

    private AssistScopes() {
    }

    private static String normalize(String eventType) {
        return eventType.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }

    public static boolean isAllowedScope(String scope) {
        return scope != null && ALLOWED_SCOPES.contains(scope.toUpperCase(Locale.ROOT));
    }

    public static boolean isForbiddenEvent(String eventType) {
        if (eventType == null) {
            return true;
        }
        return FORBIDDEN_EVENTS.contains(normalize(eventType));
    }

    public static boolean isAllowedEvent(String eventType) {
        if (eventType == null || isForbiddenEvent(eventType)) {
            return false;
        }
        return ALLOWED_EVENTS.contains(eventType.toUpperCase(Locale.ROOT));
    }
}
