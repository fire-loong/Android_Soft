package com.actionloop.api.repository;

import com.actionloop.api.domain.OcrSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OcrSegmentRepository extends JpaRepository<OcrSegment, UUID> {

    List<OcrSegment> findByResultIdOrderBySegmentIndexAsc(UUID resultId);

    void deleteByResultId(UUID resultId);
}
