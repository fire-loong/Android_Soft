package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 待确认候选（指导书 4.3）：AI/规则提出但尚未被用户确认的内容。
 * 候选本身永远不产生提醒；只有 confirm 事务内创建 Task 后才会调度提醒。
 */
@Entity
@Table(name = "candidate_tasks")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class CandidateTask extends VersionedAggregate {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(name = "source_item_id", columnDefinition = "uuid", nullable = false)
    private UUID sourceItemId;

    @Column(nullable = false)
    private String title;

    private String summary;

    private String category;

    @Column(name = "raw_due_text")
    private String rawDueText;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_timezone")
    private String dueTimezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_precision")
    private TimePrecision timePrecision;

    private String location;

    @Column(name = "materials_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String materialsJson = "[]";

    @Column(name = "next_action")
    private String nextAction;

    private BigDecimal confidence;

    @Column(name = "field_confidence_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String fieldConfidenceJson = "{}";

    @Column(name = "reasons_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String reasonsJson = "[]";

    @Column(name = "evidence_quotes_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidenceQuotesJson = "[]";

    @Column(name = "uncertainties_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String uncertaintiesJson = "[]";

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "needs_user_confirmation", nullable = false)
    private boolean needsUserConfirmation = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Derivation derivation = Derivation.RULE;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateStatus status = CandidateStatus.PENDING;

    @Column(name = "confirmed_task_id", columnDefinition = "uuid")
    private UUID confirmedTaskId;

    @Column(name = "dismissed_reason")
    private String dismissedReason;
}
