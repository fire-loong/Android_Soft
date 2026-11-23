package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 协助信令负载守卫（指导书 8.7 / 13.5）。
 *
 * 允许只读标注需要的最小字段（frameId/pageHash/packageName/bounds/expiresAt/label/text 等），
 * 以及 WebRTC 建连所需的 SDP / ICE candidate；但拒绝任何可能携带原始屏幕帧、图片或像素数据的键与值。
 * 注意：frameId 是“当前帧标识”，必须放行；真正的像素数据键（frame/image/screenshot/base64…）才拒绝。
 */
public final class AssistPayloadGuard {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_LIST = 200;
    private static final int MAX_KEYS = 64;
    private static final int MAX_TEXT = 2000;
    private static final int MAX_FRAME_ID = 128;

    /** WebRTC 协商文本上限：真实视频 SDP 约 2–6KB，12KB 足够且能防止借 SDP 大字段滥用存储。 */
    static final int MAX_SDP_CHARS = 12_000;
    private static final int MAX_SDP_LINES = 400;
    private static final int MAX_SDP_LINE_CHARS = 512;
    private static final int MAX_CANDIDATE_CHARS = 1_024;
    private static final int MAX_SDP_MID_CHARS = 64;

    /** 任意单段超长 base64 字母表片段都视为疑似图片/像素夹带。 */
    private static final Pattern BASE64_RUN = Pattern.compile("[A-Za-z0-9+/=]{256,}");

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
                switch (key) {
                    case "frameid" -> {
                        validateFrameId(entry.getValue());
                        continue;
                    }
                    case "sdp" -> {
                        validateSdp(entry.getValue());
                        continue;
                    }
                    case "candidate" -> {
                        validateCandidate(entry.getValue());
                        continue;
                    }
                    case "sdpmid" -> {
                        validateShortString(entry.getValue(), MAX_SDP_MID_CHARS, "sdpMid");
                        continue;
                    }
                    default -> {
                        // 继续递归校验普通字段
                    }
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
            rejectPixelLikeValue(value);
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
     * SDP 只允许标准协商结构：必须为 v= 开头的文本会话描述，且含 m= 媒体段。
     * 逐行限长 + 禁止图片/像素夹带，避免把“sdp”当成任意大文本通道。
     */
    private static void validateSdp(Object value) {
        if (!(value instanceof String sdp) || sdp.isBlank() || sdp.length() > MAX_SDP_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SDP 为空或过长");
        }
        String trimmed = sdp.strip();
        if (!trimmed.startsWith("v=") || !trimmed.contains("m=")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SDP 结构非法");
        }
        String[] lines = trimmed.split("\\r?\\n", -1);
        if (lines.length > MAX_SDP_LINES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SDP 行数过多");
        }
        for (String line : lines) {
            if (line.length() > MAX_SDP_LINE_CHARS) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "SDP 单行过长");
            }
        }
        rejectPixelLikeValue(sdp);
    }

    /** ICE candidate 为短文本（约 100–300 字符），不允许包含图片/像素数据。 */
    private static void validateCandidate(Object value) {
        if (!(value instanceof String candidate) || candidate.isBlank()
                || candidate.length() > MAX_CANDIDATE_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "ICE candidate 格式非法");
        }
        if (candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "ICE candidate 格式非法");
        }
        rejectPixelLikeValue(candidate);
    }

    private static void validateShortString(Object value, int maxChars, String field) {
        if (!(value instanceof String text) || text.length() > maxChars) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " 格式非法");
        }
    }

    /** 拒绝 data: URL、显式 base64 载荷以及超长 base64 字母表片段。 */
    private static void rejectPixelLikeValue(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.contains("base64,") || BASE64_RUN.matcher(value).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "信令不得携带原始屏幕帧或图片数据");
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
