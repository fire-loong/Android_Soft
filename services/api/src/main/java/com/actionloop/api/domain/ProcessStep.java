package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "process_steps")
@Getter
@Setter
@NoArgsConstructor
public class ProcessStep {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "version_id", columnDefinition = "uuid", nullable = false)
    private UUID versionId;

    @Column(name = "parent_step_id", columnDefinition = "uuid")
    private UUID parentStepId;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false)
    private String instruction;

    @Column(name = "condition_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String conditionJson;

    @Column(nullable = false)
    private Boolean required = true;

    @Column(name = "official_entry_url")
    private String officialEntryUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
