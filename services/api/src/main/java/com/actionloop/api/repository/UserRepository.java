package com.actionloop.api.repository;

import com.actionloop.api.domain.User;
import com.actionloop.api.domain.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    boolean existsByEmailIgnoreCase(String email);

    /** 注销清除任务的行锁：多 worker 并发扫描时同一账户只处理一次。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithLockById(@Param("id") UUID id);

    /** 到期待清除账户（状态仍为 ACTIVE 且已过冷却期）。 */
    @Query("select u from User u where u.status = :active and u.deletionScheduledAt is not null "
            + "and u.deletionScheduledAt <= :now order by u.deletionScheduledAt asc")
    List<User> findDueForDeletion(@Param("active") UserStatus active, @Param("now") Instant now);
}
