package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "task_steps")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class TaskStep extends VersionedAggregate {

    @Column(name = "task_id", columnDefinition = "uuid", nullable = false)
    private UUID taskId;

    @Column(name = "process_step_id", columnDefinition = "uuid")
    private UUID processStepId;

    @Column(nullable = false)
    private String title;

    private String instruction;

    @Column(nullable = false)
    private Integer position;

    @Column(name = "assignee_id", columnDefinition = "uuid")
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStepStatus status = TaskStepStatus.TODO;

    @Column(name = "completion_rule_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String completionRuleJson = "{\"type\":\"SELF_CONFIRM\"}";
}
