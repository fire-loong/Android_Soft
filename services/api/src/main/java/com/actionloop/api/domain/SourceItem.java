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

@Entity
@Table(name = "source_items")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class SourceItem extends VersionedAggregate {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "source_app")
    private String sourceApp;

    @Column(name = "source_key")
    private String sourceKey;

    @Column(name = "source_title")
    private String sourceTitle;

    @Column(name = "link_url")
    private String linkUrl;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "content_ciphertext")
    private String contentCiphertext;

    @Column(name = "content_preview")
    private String contentPreview;

    @Column(name = "content_hash")
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_level", nullable = false)
    private PrivacyLevel privacyLevel = PrivacyLevel.S2;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false)
    private SourceState sourceState = SourceState.USER_PROVIDED;
}
