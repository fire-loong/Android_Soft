package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 带乐观锁版本号的聚合（指导书 3.2：并发更新检查 version）。
 * 修改接口 body 须带 baseVersion，冲突返回 VERSION_CONFLICT（指导书 13.4）。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class VersionedAggregate extends BaseAggregate {

    @Version
    @Column(nullable = false)
    private Integer version;
}
