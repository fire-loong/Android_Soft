package com.actionloop.api.repository;

import com.actionloop.api.domain.Reminder;
import com.actionloop.api.domain.ReminderState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    List<Reminder> findByTaskIdOrderByTriggerAtAsc(UUID taskId);

    /** 工作区级批量拉取：一次查询返回多个事项的全部提醒，消除客户端 N+1。 */
    List<Reminder> findByTaskIdInOrderByTriggerAtAsc(Collection<UUID> taskIds);

    List<Reminder> findByTaskIdAndState(UUID taskId, ReminderState state);

    @Modifying
    @Query("update Reminder r set r.state = :state where r.taskId = :taskId and r.state = :from")
    int cancelScheduledForTask(@Param("taskId") UUID taskId,
                               @Param("from") ReminderState from,
                               @Param("state") ReminderState state);

    List<Reminder> findByStateAndTriggerAtBefore(ReminderState state, Instant triggerAt);

    /** #16b：事项软删时清理其提醒（Reminder 无软删除字段，直接物理删除调度记录）。 */
    @Modifying
    @Query("delete from Reminder r where r.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") UUID taskId);
}
