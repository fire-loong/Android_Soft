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
@Table(name = "materials")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class Material extends VersionedAggregate {

    @Column(name = "task_id", columnDefinition = "uuid", nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private String name;

    @Column(name = "required_condition_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String requiredConditionJson;

    @Column(name = "format_rule_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String formatRuleJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaterialState state = MaterialState.MISSING;

    @Column(name = "evidence_ref_id", columnDefinition = "uuid")
    private UUID evidenceRefId;

    @Column(name = "asset_id", columnDefinition = "uuid")
    private UUID assetId;
}
