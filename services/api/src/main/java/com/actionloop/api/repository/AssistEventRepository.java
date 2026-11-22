package com.actionloop.api.repository;

import com.actionloop.api.domain.AssistEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AssistEventRepository extends JpaRepository<AssistEvent, Long> {

    @Query("select coalesce(max(e.seq), 0) from AssistEvent e where e.sessionId = :sessionId")
    long maxSeq(@Param("sessionId") UUID sessionId);

    List<AssistEvent> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(UUID sessionId, long afterSeq);
}
