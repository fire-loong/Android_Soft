package com.actionloop.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiResultCacheServiceTest {

    private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    @Test
    void storeRunsOnlyAfterMainTransactionCommits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiResultCacheService service = new AiResultCacheService(jdbc, new ObjectMapper(), transactionManager);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.store(request(), extraction(request()));

            verifyNoInteractions(jdbc);
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void cacheReadAndWriteFailuresDoNotEscape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataIntegrityViolationException("cache write failure"))
                .when(jdbc).update(anyString(), any(Object[].class));
        doThrow(new DataAccessResourceFailureException("cache read failure"))
                .when(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        AiResultCacheService service = new AiResultCacheService(jdbc, new ObjectMapper(), transactionManager);
        ExtractionRequest request = request();

        assertDoesNotThrow(() -> service.store(request, extraction(request)));
        assertTrue(service.find(request, "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                QwenAiProvider.SCHEMA_VERSION).isEmpty());
    }

    private static ExtractionRequest request() {
        return new ExtractionRequest("2026年11月30日18:00前提交竞赛报名表。",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "测试应用", "通知",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-cache-test");
    }

    private static CandidateExtraction extraction(ExtractionRequest request) {
        CandidateExtraction rule = new RuleExtractor().extract(request);
        return new CandidateExtraction(rule.candidates(), "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                QwenAiProvider.SCHEMA_VERSION, "AI", List.of());
    }
}
