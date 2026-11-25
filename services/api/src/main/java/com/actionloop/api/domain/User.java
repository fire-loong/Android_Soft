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

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class User extends VersionedAggregate {

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    @Column(nullable = false)
    private String locale = "zh-CN";

    /** 注销请求时间；冷却期内可取消（指导书 15.5 账户注销冷却与取消）。 */
    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    /** 计划清除时间 = 请求时间 + 冷却天数；到期由 worker 执行匿名化与数据清除。 */
    @Column(name = "deletion_scheduled_at")
    private Instant deletionScheduledAt;
}
