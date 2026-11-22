package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class Task extends VersionedAggregate {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.DRAFT;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false)
    private UUID ownerId;

    @Column(name = "assignee_id", columnDefinition = "uuid")
    private UUID assigneeId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_timezone")
    private String dueTimezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_precision")
    private TimePrecision timePrecision = TimePrecision.NONE;

    @Column(nullable = false)
    private Integer priority = 0;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Derivation derivation = Derivation.USER;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;
}
