package com.actionloop.api.service;

import com.actionloop.api.domain.SyncOperation;
import com.actionloop.api.domain.SyncState;
import com.actionloop.api.domain.Task;
import com.actionloop.api.domain.TaskStatus;
import com.actionloop.api.domain.TimePrecision;
import com.actionloop.api.repository.SyncOperationRepository;
import com.actionloop.api.repository.TaskRepository;
import com.actionloop.api.web.dto.SyncOperationView;
import com.actionloop.api.web.dto.SyncOpInput;
import com.actionloop.api.web.dto.TaskView;
import com.actionloop.api.web.dto.TransitionRequest;
import com.actionloop.api.web.dto.UpdateTaskRequest;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.actionloop.shared.web.CursorPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 离线同步服务（指导书 11.4 / 7.4）。
 *
 * <p>关键保证：
 * <ul>
 *   <li>每个 op 分两个独立事务：先以 opId 幂等占位（PENDING），再执行业务并落最终状态。
 *       即使业务回滚或进程崩溃，已占位的 op 重试时返回首次结果，绝不重复执行。</li>
 *   <li>baseVersion 不一致 → CONFLICT（VERSION_CONFLICT），不自动覆盖，由用户在冲突页解决。</li>
 *   <li>业务异常在此捕获并映射为 CONFLICT/FAILED，配合 TaskService 的 noRollbackFor，
 *       保证最终状态能写回 sync_operations。</li>
 *   <li>pull 按 opId（UUIDv7，时间有序）游标分页，支持断点补洞。</li>
 * </ul>
 */
@Service
public class SyncService {

    private static final int MAX_LIMIT = 100;

    private final SyncOperationRepository repository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public SyncService(SyncOperationRepository repository, TaskRepository taskRepository,
                       TaskService taskService, ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<SyncOperationView> push(UUID userId, UUID deviceId, List<SyncOpInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次同步操作不能超过 " + MAX_LIMIT + " 条");
        }
        // 逐条独立处理：单条意外失败返回 FAILED，不中断整批（指导书 11.4）。
        return inputs.stream().map(in -> {
            try {
                return applyOne(userId, deviceId, in);
            } catch (RuntimeException re) {
                return recordFailure(in.opId(), re);
            }
        }).toList();
    }

    private SyncOperationView recordFailure(UUID opId, RuntimeException re) {
        try {
            return transaction.execute(s -> {
                SyncOperation op = repository.findById(opId).orElse(null);
                if (op != null) {
                    op.setState(SyncState.FAILED);
                    op.setErrorCode(ErrorCode.INTERNAL_ERROR.name());
                    repository.save(op);
                    return SyncOperationView.of(op);
                }
                return new SyncOperationView(opId, null, null, null, null, null,
                        SyncState.FAILED.name(), ErrorCode.INTERNAL_ERROR.name(), null);
            });
        } catch (Exception e) {
            return new SyncOperationView(opId, null, null, null, null, null,
                    SyncState.FAILED.name(), ErrorCode.INTERNAL_ERROR.name(), null);
        }
    }

    private SyncOperationView applyOne(UUID userId, UUID deviceId, SyncOpInput in) {
        RecordResult recorded = transaction.execute(status -> recordPending(userId, deviceId, in));
        if (recorded.existed()) {
            return SyncOperationView.of(recorded.op());
        }
        return transaction.execute(status -> applyAndPersist(recorded.op().getOpId()));
    }

    private record RecordResult(boolean existed, SyncOperation op) {
    }

    private RecordResult recordPending(UUID userId, UUID deviceId, SyncOpInput in) {
        Optional<SyncOperation> existing = repository.findById(in.opId());
        if (existing.isPresent()) {
            SyncOperation op = existing.get();
            if (!op.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED, "无权访问该同步操作");
            }
            return new RecordResult(true, op);
        }
        SyncOperation op = new SyncOperation();
        op.setOpId(in.opId());
        op.setDeviceId(deviceId);
        op.setUserId(userId);
        op.setAggregateId(in.aggregateId());
        op.setAggregateType(in.aggregateType());
        op.setOperationType(in.operationType());
        op.setBaseVersion(in.baseVersion() == null ? 0 : in.baseVersion());
        op.setPayload(serializePayload(in.payload()));
        op.setState(SyncState.PENDING);
        return new RecordResult(false, repository.save(op));
    }

    private SyncOperationView applyAndPersist(UUID opId) {
        SyncOperation op = repository.findById(opId)
                .orElseThrow(() -> new IllegalStateException("sync op missing: " + opId));
        try {
            applyBusiness(op);
        } catch (BusinessException be) {
            op.setState(be.code() == ErrorCode.VERSION_CONFLICT ? SyncState.CONFLICT : SyncState.FAILED);
            op.setErrorCode(be.code().name());
        } catch (RuntimeException re) {
            op.setState(SyncState.FAILED);
            op.setErrorCode(ErrorCode.INTERNAL_ERROR.name());
        }
        repository.save(op);
        return SyncOperationView.of(op);
    }

    private void applyBusiness(SyncOperation op) {
        Map<String, Object> payload = parsePayload(op.getPayload());
        switch (op.getAggregateType()) {
            case "TASK" -> applyTask(op, payload);
            default -> throw new BusinessException(ErrorCode.UNSUPPORTED_SYNC_OPERATION,
                    "不支持的同步聚合类型: " + op.getAggregateType());
        }
    }

    private void applyTask(SyncOperation op, Map<String, Object> payload) {
        Task task = taskRepository.findById(op.getAggregateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "同步目标事项不存在"));
        UUID workspaceId = task.getWorkspaceId();
        switch (op.getOperationType()) {
            case "TASK_UPDATE" -> {
                TaskView view = taskService.update(workspaceId, op.getAggregateId(), op.getUserId(),
                        new UpdateTaskRequest(op.getBaseVersion(), str(payload, "title"), uuid(payload, "assigneeId"),
                                instantOrNull(payload, "dueAt"), dateOrNull(payload, "dueDate"),
                                str(payload, "dueTimezone"), timePrecisionOrNull(payload, "timePrecision"),
                                intOrNull(payload, "priority"), str(payload, "description")));
                op.setState(SyncState.SYNCED);
                op.setServerVersion(view.version());
            }
            case "TASK_TRANSITION" -> {
                TaskStatus target = TaskStatus.valueOf(str(payload, "status"));
                TaskView view = taskService.transition(workspaceId, op.getAggregateId(), op.getUserId(),
                        new TransitionRequest(target, op.getBaseVersion(), str(payload, "reason")));
                op.setState(SyncState.SYNCED);
                op.setServerVersion(view.version());
            }
            default -> throw new BusinessException(ErrorCode.UNSUPPORTED_SYNC_OPERATION,
                    "不支持的任务同步操作: " + op.getOperationType());
        }
    }

    public CursorPage<SyncOperationView> pull(UUID userId, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Pageable pageable = PageRequest.of(0, size + 1, Sort.by(Sort.Direction.ASC, "opId"));
        Page<SyncOperation> page;
        if (cursor == null || cursor.isBlank()) {
            page = repository.findByUserIdOrderByOpIdAsc(userId, pageable);
        } else {
            UUID after;
            try {
                after = UUID.fromString(cursor);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同步游标格式不正确");
            }
            page = repository.findByUserIdAndOpIdGreaterThanOrderByOpIdAsc(userId, after, pageable);
        }
        List<SyncOperationView> items = page.getContent().stream()
                .limit(size)
                .map(SyncOperationView::of)
                .toList();
        boolean hasMore = page.getNumberOfElements() > size;
        String nextCursor = hasMore ? items.get(items.size() - 1).opId().toString() : null;
        return CursorPage.of(items, nextCursor, hasMore);
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同步 payload 序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer intOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(v.toString());
    }

    private static UUID uuid(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : UUID.fromString(s);
    }

    private static Instant instantOrNull(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : Instant.parse(s);
    }

    private static LocalDate dateOrNull(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : LocalDate.parse(s);
    }

    private static TimePrecision timePrecisionOrNull(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : TimePrecision.valueOf(s);
    }
}
