package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reminders")
@Getter
@Setter
@NoArgsConstructor
public class Reminder extends TimestampedEntity {

    @Column(name = "task_id", columnDefinition = "uuid", nullable = false)
    private UUID taskId;

    @Column(name = "trigger_at", nullable = false)
    private Instant triggerAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderChannel channel = ReminderChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderState state = ReminderState.SCHEDULED;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;
}
