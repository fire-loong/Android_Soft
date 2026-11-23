package com.actionloop.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 MaaS 冒烟：必须显式开启，密钥只从本地环境读取，不在 CI 默认运行。 */
@EnabledIfEnvironmentVariable(named = "ACTIONLOOP_AI_LIVE_TEST", matches = "true")
class QwenMaaSLiveIT {

    @Test
    void sparkProducesSchemaValidEvidenceBackedCandidate() {
        AiProperties props = new AiProperties();
        props.setProvider(required("ACTIONLOOP_AI_PROVIDER"));
        props.setBaseUrl(required("ACTIONLOOP_AI_BASE_URL"));
        props.setApiKey(required("ACTIONLOOP_AI_API_KEY"));
        props.setModel(required("ACTIONLOOP_AI_MODEL"));
        props.setTimeoutSeconds(Integer.parseInt(System.getenv().getOrDefault(
                "ACTIONLOOP_AI_TIMEOUT_SECONDS", "45")));
        QwenAiProvider provider = new QwenAiProvider(props, new ObjectMapper(), new RuleExtractor());
        String text = "请各位同学于11月30日18:00前在课程平台提交课程设计PDF，文件命名为学号-姓名。";
        ExtractionRequest request = new ExtractionRequest(text, Instant.parse("2026-11-22T02:00:00Z"),
                "Asia/Shanghai", "班级群", "课程通知", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "live-maas-schema-smoke");

        CandidateExtraction result = provider.extract(request);

        assertEquals(1, result.candidates().size());
        CandidateExtraction.CandidateDraft candidate = result.candidates().getFirst();
        assertEquals("AI", result.derivation());
        assertEquals(QwenAiProvider.SCHEMA_VERSION, result.schemaVersion());
        assertFalse(candidate.evidenceQuotes().isEmpty());
        assertTrue(candidate.evidenceQuotes().stream().allMatch(text::contains));
        assertEquals("2026-11-30T10:00:00Z", candidate.dueAt());
        assertEquals("11月30日18:00前", candidate.rawDueText());
        assertTrue(candidate.needsUserConfirmation());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be supplied by the local environment");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }
}
