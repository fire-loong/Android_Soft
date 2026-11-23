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

import java.time.Instant;
import java.util.UUID;

/** 长截图单段 OCR 结果：保留段序与纵向偏移，便于回到原图定位（指导书 B-01）。 */
@Entity
@Table(name = "ocr_segments")
@Getter
@Setter
@NoArgsConstructor
public class OcrSegment {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "result_id", columnDefinition = "uuid", nullable = false)
    private UUID resultId;

    @Column(name = "segment_index", nullable = false)
    private Integer segmentIndex;

    @Column(name = "y_offset", nullable = false)
    private Integer yOffset;

    @Column(nullable = false)
    private Integer height;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
