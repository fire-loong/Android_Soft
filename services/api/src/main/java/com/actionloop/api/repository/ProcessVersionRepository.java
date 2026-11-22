package com.actionloop.api.repository;

import com.actionloop.api.domain.ProcessVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessVersionRepository extends JpaRepository<ProcessVersion, UUID> {

    List<ProcessVersion> findByTemplateIdOrderByVersionNoDesc(UUID templateId);

    /** 列表页批量取版本元数据，避免逐个模板 N+1 查询。*/
    List<ProcessVersion> findByTemplateIdInOrderByVersionNoDesc(java.util.Collection<UUID> templateIds);

    Optional<ProcessVersion> findFirstByTemplateIdOrderByVersionNoDesc(UUID templateId);
}
