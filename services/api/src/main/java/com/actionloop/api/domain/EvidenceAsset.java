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

import java.util.UUID;

@Entity
@Table(name = "evidence_assets")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class EvidenceAsset extends VersionedAggregate {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_state", nullable = false)
    private ScanState scanState = ScanState.PENDING;

    @Column(name = "ocr_failure_code")
    private String ocrFailureCode;

    @Column(name = "retention_class", nullable = false)
    private String retentionClass = "USER_CONTROLLED";
}
