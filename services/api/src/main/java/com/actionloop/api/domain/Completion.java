package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 步骤完成凭证（指导书 3.1 Completion）。追加写，审核通过可驳回重做。
 */
@Entity
@Table(name = "completions")
@Getter
@Setter
@NoArgsConstructor
public class Completion {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "task_step_id", columnDefinition = "uuid", nullable = false)
    private UUID taskStepId;

    @Column(name = "submitter_id", columnDefinition = "uuid", nullable = false)
    private UUID submitterId;

    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_state", nullable = false)
    private ReviewState reviewState = ReviewState.PENDING;

    @Column(name = "reviewer_id", columnDefinition = "uuid")
    private UUID reviewerId;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
