package com.actionloop.api.web.dto;

import com.actionloop.api.domain.Comment;

import java.time.Instant;
import java.util.UUID;

public record CommentView(UUID id, UUID taskId, UUID authorId, String body, Instant createdAt, Integer version) {
    public static CommentView of(Comment c) {
        return new CommentView(c.getId(), c.getTaskId(), c.getAuthorId(), c.getBody(), c.getCreatedAt(), c.getVersion());
    }
}
