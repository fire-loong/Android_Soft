package com.actionloop.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 结构化输出契约（指导书 14.x）：
 * /ai/notification-task-v1.schema.json 是服务端与模型之间的唯一线格式，
 * 供应商解析器与规则兜底必须使用同一 schemaVersion；契约收紧后必须能被测试发现。
 */
class AiOutputSchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = AiOutputSchemaContractTest.class
                .getResourceAsStream("/ai/notification-task-v1.schema.json")) {
            assertNotNull(in, "缺少 /ai/notification-task-v1.schema.json");
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    @Test
    void providersShareTheSameSchemaVersion() {
        assertEquals("notification-task-v1", QwenAiProvider.SCHEMA_VERSION,
                "云端供应商必须使用契约 schema 版本");
        assertEquals("notification-task-v1", DeterministicAiProvider.SCHEMA_VERSION,
                "确定性兜底必须与云端供应商输出同一 schema 版本");
    }

    @Test
    void validAiOutputPassesSchema() throws Exception {
        Set<ValidationMessage> errors = schema.validate(validOutput());
        assertTrue(errors.isEmpty(), "合法 AI 输出不得被 Schema 拒绝: " + errors);
    }

    @Test
    void missingUserConfirmationFlagIsRejected() throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("candidates").get(0)).remove("needsUserConfirmation");
        assertFalse(schema.validate(output).isEmpty(), "缺少 needsUserConfirmation 必须被拒绝");
    }

    @Test
    void unknownTopLevelFieldIsRejected() throws Exception {
        ObjectNode output = validOutput();
        output.put("autoSubmit", true);
        assertFalse(schema.validate(output).isEmpty(), "schema 不允许未声明的自动提交字段");
    }

    @Test
    void mismatchedSchemaVersionIsRejected() throws Exception {
        ObjectNode output = validOutput();
        output.put("schemaVersion", "notification-task-v2");
        assertFalse(schema.validate(output).isEmpty(), "schemaVersion 必须与契约常量一致");
    }

    @Test
    void blankEvidenceQuoteIsRejected() throws Exception {
        ObjectNode output = validOutput();
        ArrayNode quotes = MAPPER.createArrayNode();
        quotes.add("");
        ((ObjectNode) output.path("candidates").get(0)).set("evidenceQuotes", quotes);
        assertFalse(schema.validate(output).isEmpty(), "空白证据引用必须被拒绝，AI 不得脱离原文");
    }

    private static ObjectNode validOutput() throws Exception {
        JsonNode parsed = MAPPER.readTree("""
                {
                  "schemaVersion": "notification-task-v1",
                  "candidates": [{
                    "isActionable": true,
                    "title": "提交课程设计",
                    "summary": "11月30日前在课程平台提交 PDF",
                    "category": "SUBMISSION",
                    "due": {
                      "rawText": "11月30日",
                      "resolvedAt": "2027-11-30T00:00:00+08:00",
                      "precision": "DATE",
                      "confidence": 0.7
                    },
                    "location": "教务处",
                    "materials": [{"name": "学生证", "quote": "需要携带学生证"}],
                    "action": "在课程平台上传 PDF",
                    "confidence": 0.8,
                    "fieldConfidence": {
                      "title": 0.9, "due": 0.7, "time": 0.2, "location": 0.6, "materials": 0.6
                    },
                    "evidenceQuotes": ["请于11月30日前提交课程设计PDF"],
                    "needsUserConfirmation": true,
                    "uncertainties": [],
                    "sensitive": false
                  }]
                }
                """);
        assertTrue(parsed.isObject());
        ArrayNode candidates = (ArrayNode) parsed.path("candidates");
        assertEquals(1, candidates.size());
        return (ObjectNode) parsed;
    }
}
