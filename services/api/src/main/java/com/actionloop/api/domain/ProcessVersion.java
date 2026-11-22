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

import java.time.Instant;
import java.util.UUID;

/**
 * 流程版本：发布后不可修改。DRAFT 可编辑；VERIFIED 需引用完整且复核人与创建人不同。
 */
@Entity
@Table(name = "process_versions")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class ProcessVersion extends VersionedAggregate {

    @Column(name = "template_id", columnDefinition = "uuid", nullable = false)
    private UUID templateId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_state", nullable = false)
    private ProcessReviewState reviewState = ProcessReviewState.DRAFT;

    @Column(name = "source_snapshot_hash")
    private String sourceSnapshotHash;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "published_by", columnDefinition = "uuid")
    private UUID publishedBy;

    /** VERIFIED 复核人（必须与创建人不同，且为发布操作人本人，避免伪造他人复核）。 */
    @Column(name = "reviewer_id", columnDefinition = "uuid")
    private UUID reviewerId;

    @Column(name = "published_at")
    private Instant publishedAt;
}
