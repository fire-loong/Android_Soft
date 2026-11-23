package com.actionloop.api.it;

import com.actionloop.api.domain.Job;
import com.actionloop.api.job.JobDispatcher;
import com.actionloop.api.job.JobExecution;
import com.actionloop.api.job.JobHandler;
import com.actionloop.api.service.JobService;
import com.actionloop.api.test.AbstractIntegrationTest;
import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = {
        "app.worker.jobs.enabled=true",
        "app.worker.jobs.interval-ms=600000",
        "app.worker.jobs.backoff-base-millis=1"
})
@Import(JobDispatcherIntegrationTest.TestJobs.class)
class JobDispatcherIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobDispatcher dispatcher;

    @Autowired
    private TestHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetHandler() {
        handler.reset();
    }

    @Test
    void concurrentDispatchClaimsJobOnlyOnce() throws Exception {
        Job job = newJob(register());
        handler.entered = new CountDownLatch(1);
        handler.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(dispatcher::dispatchOne);
            assertTrue(handler.entered.await(5, TimeUnit.SECONDS));
            Future<Boolean> second = pool.submit(dispatcher::dispatchOne);
            assertFalse(second.get(5, TimeUnit.SECONDS));
            handler.release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
        } finally {
            handler.release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, handler.calls.get());
        assertEquals("SUCCEEDED", state(job.getId()));
        assertEquals(0, payloadCount(job.getId()));
    }

    @Test
    void transientFailureBackoffAndExpiredLeaseAreRecovered() {
        Job job = newJob(register());
        handler.failures.add(new BusinessException(ErrorCode.AI_UNAVAILABLE, "transient"));

        assertTrue(dispatcher.dispatchOne());
        assertEquals("PENDING", state(job.getId()));
        assertEquals("AI_UNAVAILABLE", error(job.getId()));
        assertEquals(1, attempts(job.getId()));

        jdbcTemplate.update("UPDATE jobs SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), job.getId());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", state(job.getId()));
        assertEquals(2, attempts(job.getId()));

        Job crashed = newJob(register());
        jdbcTemplate.update("""
                UPDATE jobs SET state = 'RUNNING', lease_owner = 'crashed', lease_until = ?, attempts = 0
                WHERE id = ?
                """, Timestamp.from(Instant.now().minusSeconds(5)), crashed.getId());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", state(crashed.getId()));
        assertEquals(1, attempts(crashed.getId()));
    }

    @Test
    void permanentFailureIsDeadLetterAndOnlyRequesterCanRetry() {
        var owner = register();
        var member = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("job-acl"));
        invite(owner.get("access"), workspaceId, member.get("email"), "MEMBER");
        accept(member.get("access"), workspaceId);
        Job job = enqueue(owner, workspaceId);
        handler.failures.add(new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "permanent"));

        assertTrue(dispatcher.dispatchOne());
        assertEquals("DEAD_LETTER", state(job.getId()));
        assertEquals("AI_OUTPUT_INVALID", error(job.getId()));
        assertEquals(1, payloadCount(job.getId()), "死信人工重试前保留授权输入");

        Resp forbidden = req("POST", "/api/v1/jobs/" + job.getId() + "/retry?workspaceId=" + workspaceId,
                member.get("access"), null, null);
        assertEquals(403, forbidden.status, forbidden.raw);
        Resp retried = req("POST", "/api/v1/jobs/" + job.getId() + "/retry?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(200, retried.status, retried.raw);
        assertEquals("PENDING", retried.json.path("data").path("state").asText());
        assertEquals(0, retried.json.path("data").path("attempts").asInt());
        assertTrue(dispatcher.dispatchOne());
        assertEquals("SUCCEEDED", state(job.getId()));
    }

    @Test
    void jobStatusUsesWorkspaceAclAndEventsArePersistedBeforeOutbox() {
        var owner = register();
        var outsider = register();
        String workspaceId = createWorkspace(owner.get("access"), unique("job-events"));
        Job job = enqueue(owner, workspaceId);
        assertTrue(dispatcher.dispatchOne());

        Resp read = req("GET", "/api/v1/jobs/" + job.getId() + "?workspaceId=" + workspaceId,
                owner.get("access"), null, null);
        assertEquals(200, read.status, read.raw);
        assertEquals("SUCCEEDED", read.json.path("data").path("state").asText());
        Resp denied = req("GET", "/api/v1/jobs/" + job.getId() + "?workspaceId=" + workspaceId,
                outsider.get("access"), null, null);
        assertEquals(403, denied.status, denied.raw);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT o.server_event_id, s.workspace_id, s.event_type
                FROM outbox_events o JOIN server_events s ON s.id = o.server_event_id
                WHERE o.aggregate_id = ? AND o.event_type = 'JOB_SUCCEEDED'
                """, job.getId());
        assertEquals(1, rows.size());
        assertEquals(UUID.fromString(workspaceId), rows.getFirst().get("workspace_id"));
    }

    private Job newJob(Map<String, String> user) {
        String workspaceId = createWorkspace(user.get("access"), unique("job-worker"));
        return enqueue(user, workspaceId);
    }

    private Job enqueue(Map<String, String> user, String workspaceId) {
        String ref = "test:" + UUID.randomUUID();
        return jobService.enqueue(UUID.fromString(workspaceId), UUID.fromString(user.get("userId")), "TEST_JOB", ref,
                "test:" + UUID.randomUUID(), Map.of("value", "safe"));
    }

    private String state(UUID id) {
        return jdbcTemplate.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, id);
    }

    private String error(UUID id) {
        return jdbcTemplate.queryForObject("SELECT last_error_code FROM jobs WHERE id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        Integer value = jdbcTemplate.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id);
        return value == null ? -1 : value;
    }

    private int payloadCount(UUID id) {
        Integer value = jdbcTemplate.queryForObject("SELECT count(*) FROM job_payloads WHERE job_id = ?",
                Integer.class, id);
        return value == null ? -1 : value;
    }

    @TestConfiguration
    static class TestJobs {
        @Bean
        TestHandler testHandler() {
            return new TestHandler();
        }
    }

    static final class TestHandler implements JobHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final ConcurrentLinkedQueue<RuntimeException> failures = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public String type() {
            return "TEST_JOB";
        }

        @Override
        public void execute(JobExecution execution) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test handler timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
        }

        private void reset() {
            calls.set(0);
            failures.clear();
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
        }
    }
}
