package com.actionloop.api.service;

import com.actionloop.api.domain.Reminder;
import com.actionloop.api.domain.ReminderChannel;
import com.actionloop.api.domain.ReminderState;
import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.ReminderRepository;
import com.actionloop.api.web.dto.ReminderInput;
import com.actionloop.api.web.dto.ReminderView;
import com.actionloop.api.web.dto.ReplaceRemindersRequest;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 事项提醒（指导书 13.2 GET/PUT /tasks/{id}/reminders）。
 * PUT 整体替换：先取消该事项全部未发送计划，再按 dedupeKey 去重建。
 */
@Service
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final TaskGuard taskGuard;
    private final AuditService auditService;

    public ReminderService(ReminderRepository reminderRepository, TaskGuard taskGuard, AuditService auditService) {
        this.reminderRepository = reminderRepository;
        this.taskGuard = taskGuard;
        this.auditService = auditService;
    }

    public List<ReminderView> list(UUID workspaceId, UUID taskId, UUID userId) {
        taskGuard.loadForRead(workspaceId, taskId, userId);
        return reminderRepository.findByTaskIdOrderByTriggerAtAsc(taskId).stream()
                .map(ReminderView::of).toList();
    }

    /** 工作区级批量读取：一次返回空间内全部事项的提醒，供客户端单请求调度（消除 N+1）。 */
    public List<ReminderView> listForWorkspace(UUID workspaceId, UUID userId) {
        List<UUID> taskIds = taskGuard.visibleTaskIds(workspaceId, userId);
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return reminderRepository.findByTaskIdInOrderByTriggerAtAsc(taskIds).stream()
                .map(ReminderView::of).toList();
    }

    @Transactional
    public List<ReminderView> replace(UUID workspaceId, UUID taskId, UUID userId, ReplaceRemindersRequest req) {
        Task task = taskGuard.loadInWorkspace(workspaceId, taskId);
        taskGuard.requireCanModify(workspaceId, userId, task);
        // 旧计划取消（指导书：替换时旧计划取消）
        reminderRepository.cancelScheduledForTask(taskId, ReminderState.SCHEDULED, ReminderState.CANCELLED);

        Set<String> seen = new HashSet<>();
        List<Reminder> created = new ArrayList<>();
        for (ReminderInput input : req.reminders()) {
            if (input.triggerAt() == null || input.channel() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "提醒时间与渠道不能为空");
            }
            ReminderChannel channel = input.channel();
            String key = taskId + "|" + input.triggerAt().getEpochSecond() + "|" + channel;
            if (!seen.add(key)) {
                continue; // 同请求内去重
            }
            Reminder r = new Reminder();
            r.setTaskId(taskId);
            r.setTriggerAt(input.triggerAt());
            r.setChannel(channel);
            r.setState(ReminderState.SCHEDULED);
            r.setDedupeKey(key);
            created.add(reminderRepository.save(r));
        }
        auditService.record(userId, workspaceId, AuditAction.REMINDER_REPLACE, "task",
                taskId.toString(), AuditAction.RESULT_SUCCESS, Map.of("count", created.size()));
        return created.stream().map(ReminderView::of).toList();
    }
}
