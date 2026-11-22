package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * 流程步骤↔证据引用。发布带事实断言的步骤时必须至少有一条有效引用（指导书 6.2）。
 */
@Entity
@Table(name = "process_step_sources")
@IdClass(ProcessStepSource.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class ProcessStepSource {

    @Id
    @Column(name = "process_step_id", columnDefinition = "uuid")
    private UUID processStepId;

    @Id
    @Column(name = "evidence_ref_id", columnDefinition = "uuid")
    private UUID evidenceRefId;

    @Id
    @Column(name = "claim_type")
    private String claimType;

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID processStepId;
        private UUID evidenceRefId;
        private String claimType;
    }
}
