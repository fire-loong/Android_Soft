package com.actionloop.api.service;

import com.actionloop.api.domain.AssistEvent;
import com.actionloop.api.domain.AssistSession;
import com.actionloop.api.domain.AssistSessionState;
import com.actionloop.api.domain.Role;
import com.actionloop.api.repository.AssistEventRepository;
import com.actionloop.api.repository.AssistSessionRepository;
import com.actionloop.api.web.dto.AssistEventView;
import com.actionloop.api.web.dto.AssistSessionView;
import com.actionloop.api.web.dto.AssistSignalRequest;
import com.actionloop.api.web.dto.AssistTurnCredentials;
import com.actionloop.api.web.dto.CreateAssistSessionRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 跨 App 引导与远程协助（指导书 8.x / 13.5）。
 * 安全边界：只允许查看/高亮/文字提示/指针；不存在任何点击、滑动、输入、支付执行路径。
 * 原始屏幕帧零落盘；信令只保存最小化标注。
 */
@Service
public class AssistService {

    private static final int DEFAULT_EXPIRES_MINUTES = 30;
    private static final int MAX_EXPIRES_MINUTES = 120;
    private static final int MAX_PAYLOAD_BYTES = 16_384;

    private final AssistSessionRepository sessionRepository;
    private final AssistEventRepository eventRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final EventService eventService;
    private final AssistBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    @Value("${app.turn.urls:}")
    private String turnUrls;

    @Value("${app.turn.secret:}")
    private String turnSecret;

    @Value("${app.turn.ttl-seconds:600}")
    private long turnTtlSeconds;

    public AssistService(AssistSessionRepository sessionRepository, AssistEventRepository eventRepository,
                         PermissionService permissionService, AuditService auditService,
                         EventService eventService, AssistBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.eventService = eventService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssistSessionView create(UUID userId, CreateAssistSessionRequest req) {
        permissionService.requireAtLeastRole(req.workspaceId(), userId, Role.MEMBER);
        List<String> scopes = req.scopes() == null ? List.of("VIEW_SCREEN", "HIGHLIGHT", "TEXT_HINT") : req.scopes();
        for (String scope : scopes) {
            if (!AssistScopes.isAllowedScope(scope)) {
                throw new BusinessException(ErrorCode.ASSIST_SCOPE_DENIED, "协助范围包含不允许的能力：" + scope);
            }
        }
        if (req.helperId() != null) {
            permissionService.requireActiveMember(req.workspaceId(), req.helperId());
            if (req.helperId().equals(userId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能邀请自己协助");
            }
        }
        int minutes = req.expiresMinutes() == null ? DEFAULT_EXPIRES_MINUTES
                : Math.min(Math.max(req.expiresMinutes(), 5), MAX_EXPIRES_MINUTES);
        AssistSession session = new AssistSession();
        session.setRequesterId(userId);
        session.setWorkspaceId(req.workspaceId());
        session.setHelperId(req.helperId());
        session.setTaskStepId(req.taskStepId());
        String mode = req.mode() == null || req.mode().isBlank() ? "REMOTE_ASSIST" : req.mode();
        if (!"LOCAL_GUIDANCE".equals(mode) && !"REMOTE_ASSIST".equals(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "mode 只能是 LOCAL_GUIDANCE 或 REMOTE_ASSIST");
        }
        session.setMode(mode);
        // 本机引导只作用于发起人自己的设备，无远程参与者，创建即激活；
        // 远程协助必须由协助者显式接受后才能激活（无指定协助者时为 IDLE 开放邀请）。
        if ("LOCAL_GUIDANCE".equals(mode)) {
            session.setState(AssistSessionState.ACTIVE);
        } else {
            session.setState(req.helperId() == null ? AssistSessionState.IDLE : AssistSessionState.CONSENT_PENDING);
        }
        session.setScopesJson(toJson(scopes));
        session.setExpiresAt(Instant.now().plusSeconds(minutes * 60L));
        sessionRepository.saveAndFlush(session);
        auditService.record(userId, req.workspaceId(), AuditAction.ASSIST_CREATE, "assist_session",
                session.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of("scopes", scopes));
        return AssistSessionView.of(session);
    }

    @Transactional
    public AssistSessionView accept(UUID sessionId, UUID helperId) {
        AssistSession session = lock(sessionId);
        if (session.getWorkspaceId() == null) {
            // 历史会话缺少空间归属，无法验证成员身份，直接拒绝
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "该协助会话缺少空间归属，请重新发起");
        }
        permissionService.requireAtLeastRole(session.getWorkspaceId(), helperId, Role.MEMBER);
        if (session.getRequesterId().equals(helperId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "发起人不能替自己接受协助");
        }
        if (session.getHelperId() != null && !session.getHelperId().equals(helperId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "该协助已指定其他协助者");
        }
        if (session.getState() != AssistSessionState.IDLE
                && session.getState() != AssistSessionState.CONSENT_PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "协助会话状态不允许接受");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            session.setState(AssistSessionState.EXPIRED);
            sessionRepository.save(session);
            throw new BusinessException(ErrorCode.SESSION_EXPIRED, "协助会话已过期");
        }
        session.setHelperId(helperId);
        session.setState(AssistSessionState.ACTIVE);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        auditService.record(helperId, session.getWorkspaceId(), AuditAction.ASSIST_ACCEPT, "assist_session",
                sessionId.toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return AssistSessionView.of(session);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AssistEventView signal(UUID sessionId, UUID userId, AssistSignalRequest req) {
        AssistSession session = lock(sessionId);
        requireParticipant(session, userId);
        if (session.getState() != AssistSessionState.ACTIVE && session.getState() != AssistSessionState.PAUSED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "协助会话未激活");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            session.setState(AssistSessionState.EXPIRED);
            sessionRepository.save(session);
            throw new BusinessException(ErrorCode.SESSION_EXPIRED, "协助会话已过期");
        }
        String eventType = req.eventType().trim().toUpperCase(Locale.ROOT);
        if (AssistScopes.isForbiddenEvent(eventType)) {
            // 伪造 CLICK/SWIPE/INPUT/COMMIT_PAYMENT 等：拒绝且记录安全审计，绝不注册执行逻辑
            auditService.record(userId, session.getWorkspaceId(), AuditAction.ASSIST_SIGNAL_REJECTED, "assist_session",
                    sessionId.toString(), AuditAction.RESULT_FAILURE,
                    Map.of("eventType", eventType, "reason", "FORBIDDEN_EVENT"));
            throw new BusinessException(ErrorCode.ASSIST_SCOPE_DENIED,
                    "该信令类型被禁止：本产品不提供远程控制能力");
        }
        if (!AssistScopes.isAllowedEvent(eventType)) {
            throw new BusinessException(ErrorCode.ASSIST_SCOPE_DENIED, "未知信令类型：" + eventType);
        }
        validatePayload(req.payload());
        String payloadJson = toJson(req.payload());
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载过大");
        }
        if ("PAUSE".equals(eventType)) {
            session.setState(AssistSessionState.PAUSED);
        } else if ("RESUME".equals(eventType)) {
            session.setState(AssistSessionState.ACTIVE);
        } else if ("STOP".equals(eventType)) {
            session.setState(AssistSessionState.STOPPED);
            session.setStoppedReason("参与者主动停止");
        }
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        AssistEvent event = new AssistEvent();
        event.setSessionId(sessionId);
        event.setSeq(eventRepository.maxSeq(sessionId) + 1);
        event.setEventType(eventType);
        event.setPayloadMinimized(payloadJson);
        eventRepository.save(event);
        AssistEventView view = AssistEventView.of(event);
        broadcaster.broadcast(sessionId, view);
        auditService.record(userId, session.getWorkspaceId(), AuditAction.ASSIST_SIGNAL, "assist_session",
                sessionId.toString(), AuditAction.RESULT_SUCCESS, Map.of("eventType", eventType));
        return view;
    }

    @Transactional
    public AssistSessionView stop(UUID sessionId, UUID userId, String reason) {
        AssistSession session = lock(sessionId);
        requireParticipant(session, userId);
        if (session.getState() == AssistSessionState.STOPPED) {
            return AssistSessionView.of(session);
        }
        session.setState(AssistSessionState.STOPPED);
        session.setStoppedReason(reason == null || reason.isBlank() ? "参与者停止" : reason);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        AssistEvent event = new AssistEvent();
        event.setSessionId(sessionId);
        event.setSeq(eventRepository.maxSeq(sessionId) + 1);
        event.setEventType("STOP");
        event.setPayloadMinimized("{}");
        eventRepository.save(event);
        broadcaster.broadcast(sessionId, AssistEventView.of(event));
        auditService.record(userId, session.getWorkspaceId(), AuditAction.ASSIST_STOP, "assist_session",
                sessionId.toString(), AuditAction.RESULT_SUCCESS, Map.of());
        return AssistSessionView.of(session);
    }

    public List<AssistEventView> events(UUID sessionId, UUID userId, long afterSeq) {
        AssistSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "协助会话不存在"));
        requireParticipant(session, userId);
        if (session.getState() == AssistSessionState.STOPPED || session.getState() == AssistSessionState.EXPIRED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "协助会话已结束");
        }
        return eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
                .stream().map(AssistEventView::of).toList();
    }

    public List<AssistSessionView> listMine(UUID userId) {
        return sessionRepository.findByRequesterIdOrHelperIdOrderByCreatedAtDesc(userId, userId)
                .stream().map(AssistSessionView::of).toList();
    }

    public boolean canJoin(UUID sessionId, UUID userId) {
        return sessionRepository.findById(sessionId)
                .map(s -> {
                    if (!userId.equals(s.getRequesterId()) && !userId.equals(s.getHelperId())) {
                        return false;
                    }
                    if (s.getState() == AssistSessionState.STOPPED || s.getState() == AssistSessionState.EXPIRED
                            || s.getExpiresAt().isBefore(Instant.now())) {
                        return false;
                    }
                    return s.getWorkspaceId() == null
                            || permissionService.hasAtLeastRole(s.getWorkspaceId(), userId, Role.VIEWER);
                })
                .orElse(false);
    }

    public AssistTurnCredentials turnCredentials(UUID sessionId, UUID userId) {
        AssistSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "协助会话不存在"));
        requireParticipant(session, userId);
        if (session.getState() != AssistSessionState.ACTIVE && session.getState() != AssistSessionState.PAUSED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "协助会话未激活，不能签发媒体凭证");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED, "协助会话已过期");
        }
        if (turnSecret == null || turnSecret.isBlank() || turnUrls == null || turnUrls.isBlank()) {
            throw new BusinessException(ErrorCode.TURN_UNAVAILABLE, "TURN 服务未配置，请使用局域网直连或稍后再试");
        }
        long ttl = Math.min(Math.max(turnTtlSeconds, 60), 3600);
        long expiry = Instant.now().getEpochSecond() + ttl;
        String username = expiry + ":" + userId;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(turnSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String credential = Base64.getEncoder()
                    .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
            List<String> urls = new ArrayList<>();
            for (String url : turnUrls.split(",")) {
                if (!url.isBlank()) {
                    urls.add(url.trim());
                }
            }
            return new AssistTurnCredentials(urls, username, credential, ttl,
                    Instant.ofEpochSecond(expiry));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TURN_UNAVAILABLE, "TURN 凭证生成失败");
        }
    }

    private void requireParticipant(AssistSession session, UUID userId) {
        if (!userId.equals(session.getRequesterId()) && !userId.equals(session.getHelperId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "不是该协助会话的参与者");
        }
    }

    private AssistSession lock(UUID sessionId) {
        return sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "协助会话不存在"));
    }

    @SuppressWarnings("unchecked")
    private void validatePayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载不能为空");
        }
        validateNode(payload, 0);
    }

    private void validateNode(Object node, int depth) {
        if (depth > 6) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令负载嵌套过深");
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.contains("frame") || key.contains("image") || key.contains("screenshot")
                        || key.contains("base64") || key.contains("dataurl") || key.equals("pixels")) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "信令不得携带原始屏幕帧或图片数据");
                }
                validateNode(entry.getValue(), depth + 1);
            }
        } else if (node instanceof List<?> list) {
            if (list.size() > 200) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "信令数组过大");
            }
            for (Object item : list) {
                validateNode(item, depth + 1);
            }
        } else if (node instanceof String value) {
            // 防止把图片/屏幕帧塞进任意合法键（如 annotation）后落库
            if (value.length() > 2000) {
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
