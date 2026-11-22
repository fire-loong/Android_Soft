package com.actionloop.api.web;

import com.actionloop.api.security.UserPrincipal;
import com.actionloop.api.service.CommentService;
import com.actionloop.api.web.dto.CommentView;
import com.actionloop.shared.web.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 事项评论（指导书 13.2 / WP07）。 */
@RestController
@RequestMapping("/api/v1")
public class CommentController {

    public record CreateCommentRequest(@NotBlank @Size(max = 2000) String body) {
    }

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    private String reqId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "req_unknown";
    }

    @GetMapping("/tasks/{taskId}/comments")
    public ApiResponse<List<CommentView>> list(@AuthenticationPrincipal UserPrincipal me,
                                               @PathVariable UUID taskId,
                                               @RequestParam UUID workspaceId) {
        return ApiResponse.of(commentService.list(workspaceId, taskId, me.userId()), reqId());
    }

    @PostMapping("/tasks/{taskId}/comments")
    public ApiResponse<CommentView> create(@AuthenticationPrincipal UserPrincipal me,
                                           @PathVariable UUID taskId,
                                           @RequestParam UUID workspaceId,
                                           @jakarta.validation.Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.of(commentService.create(workspaceId, taskId, me.userId(), request.body()), reqId());
    }

    @DeleteMapping("/tasks/{taskId}/comments/{commentId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal me,
                                    @PathVariable UUID taskId,
                                    @PathVariable UUID commentId,
                                    @RequestParam UUID workspaceId) {
        commentService.delete(workspaceId, taskId, commentId, me.userId());
        return ApiResponse.of(null, reqId());
    }
}
