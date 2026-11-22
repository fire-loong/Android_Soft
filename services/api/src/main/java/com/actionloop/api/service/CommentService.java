package com.actionloop.api.service;

import com.actionloop.api.domain.Comment;
import com.actionloop.api.domain.Role;
import com.actionloop.api.domain.Task;
import com.actionloop.api.repository.CommentRepository;
import com.actionloop.api.web.dto.CommentView;
import com.actionloop.shared.audit.AuditAction;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 多人协同评论（指导书 7.x / WP07）：成员可读可写，作者或管理员可删。 */
@Service
@Transactional(noRollbackFor = BusinessException.class)
public class CommentService {

    private final CommentRepository commentRepository;
    private final TaskGuard taskGuard;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final EventService eventService;

    public CommentService(CommentRepository commentRepository, TaskGuard taskGuard,
                          PermissionService permissionService, AuditService auditService,
                          EventService eventService) {
        this.commentRepository = commentRepository;
        this.taskGuard = taskGuard;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.eventService = eventService;
    }

    public List<CommentView> list(UUID workspaceId, UUID taskId, UUID userId) {
        Task task = taskGuard.loadForRead(workspaceId, taskId, userId);
        return commentRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                .map(CommentView::of).toList();
    }

    @Transactional
    public CommentView create(UUID workspaceId, UUID taskId, UUID userId, String body) {
        Task task = taskGuard.loadForRead(workspaceId, taskId, userId);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() || text.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评论内容需为 1-2000 字");
        }
        Comment comment = new Comment();
        comment.setWorkspaceId(workspaceId);
        comment.setTaskId(task.getId());
        comment.setAuthorId(userId);
        comment.setBody(text);
        commentRepository.saveAndFlush(comment);
        auditService.record(userId, workspaceId, AuditAction.COMMENT_CREATE, "comment",
                comment.getId().toString(), AuditAction.RESULT_SUCCESS, Map.of());
        eventService.emit(workspaceId, task.getId(), "TASK", "COMMENT_CREATED", task.getVersion(),
                Map.of("taskId", task.getId().toString(), "commentId", comment.getId().toString()));
        return CommentView.of(comment);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID taskId, UUID commentId, UUID userId) {
        Task task = taskGuard.loadForRead(workspaceId, taskId, userId);
        Comment comment = commentRepository.findById(commentId)
                .filter(c -> task.getId().equals(c.getTaskId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在"));
        boolean isAuthor = comment.getAuthorId().equals(userId);
        boolean isAdmin = permissionService.hasAtLeastRole(workspaceId, userId, Role.ADMIN);
        if (!isAuthor && !isAdmin) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "只能删除自己的评论");
        }
        comment.setDeletedAt(Instant.now());
        commentRepository.save(comment);
        auditService.record(userId, workspaceId, AuditAction.COMMENT_DELETE, "comment",
                commentId.toString(), AuditAction.RESULT_SUCCESS, Map.of());
    }
}
