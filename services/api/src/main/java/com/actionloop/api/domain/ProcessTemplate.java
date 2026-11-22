package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 办事流程模板（指导书 6.x）。版本化：每个模板可有多个不可变版本，current_version_id 指向已发布版本。
 */
@Entity
@Table(name = "process_templates")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class ProcessTemplate extends VersionedAggregate {

    @Column(name = "owner_workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID ownerWorkspaceId;

    @Column(nullable = false)
    private String title;

    @Column(name = "region_code")
    private String regionCode;

    @Column(name = "scope_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String scopeJson;

    @Column(name = "current_version_id", columnDefinition = "uuid")
    private UUID currentVersionId;
}
