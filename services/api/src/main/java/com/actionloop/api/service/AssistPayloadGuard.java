package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 协助信令负载守卫（指导书 8.7 / 13.5）。
 *
 * 允许只读标注需要的最小字段（frameId/pageHash/packageName/bounds/expiresAt/label/text 等），
 * 但拒绝任何可能携带原始屏幕帧、图片或像素数据的键与值。
 * 注意：frameId 是“当前帧标识”，必须放行；真正的像素数据键（frame/image/screenshot/base64…）才拒绝。
 */
public final class AssistPayloadGuard {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_LIST = 200;
    private static final int MAX_KEYS = 64;
    private static final int MAX_TEXT = 2000;
    private static final int MAX_FRAME_ID = 128;

    private AssistPayloadGuard() {
    }

    public static void validate(Map<String, Object> payload) {
        if (payload == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载不能为空");
        }
        validateNode(payload, 0);
    }

    private static void validateNode(Object node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载嵌套过深");
        }
        if (node instanceof Map<?, ?> map) {
            if (map.size() > MAX_KEYS) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载字段过多");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (isForbiddenKey(key)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "信令不得携带原始屏幕帧或图片数据");
                }
                if ("frameid".equals(key)) {
                    validateFrameId(entry.getValue());
                }
                validateNode(entry.getValue(), depth + 1);
            }
        } else if (node instanceof List<?> list) {
            if (list.size() > MAX_LIST) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令数组过大");
            }
            for (Object item : list) {
                validateNode(item, depth + 1);
            }
        } else if (node instanceof String value) {
            // 防止把图片/屏幕帧塞进任意合法键（如 annotation）后落库
            if (value.length() > MAX_TEXT) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令文本过长");
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("data:") || lower.contains("base64,")
                    || (value.length() > 256 && value.matches("^[A-Za-z0-9+/=\\s]+$"))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "信令不得携带原始屏幕帧或图片数据");
            }
        }
    }

    /** frameId 只允许短标识（UUID/哈希），不允许借道夹带像素或长文本。 */
    private static void validateFrameId(Object value) {
        if (!(value instanceof String frameId) || frameId.isBlank()
                || frameId.length() > MAX_FRAME_ID
                || !frameId.matches("[A-Za-z0-9._:-]+")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "frameId 格式非法");
        }
    }

    /**
     * frameId 是帧绑定的必要标识，明确放行；其余含 frame/image/screenshot/base64/dataurl/pixels
     * 的键仍视为疑似像素数据载荷。
     */
    static boolean isForbiddenKey(String lowerKey) {
        if ("frameid".equals(lowerKey)) return false;
        return lowerKey.contains("frame") || lowerKey.contains("image") || lowerKey.contains("screenshot")
                || lowerKey.contains("base64") || lowerKey.contains("dataurl")
                || lowerKey.equals("pixels") || lowerKey.contains("bitmap");
    }
}
