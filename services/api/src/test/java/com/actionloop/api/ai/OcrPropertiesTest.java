package com.actionloop.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrPropertiesTest {

    @Test
    void configuredAllowlistCanOnlyShrinkFixedOcrModels() {
        OcrProperties props = new OcrProperties();
        props.setAllowedModels(List.of("spark-x2.6", "xoppaddleocrv16"));

        assertTrue(props.isModelAllowed("xoppaddleocrv16"));
        assertFalse(props.isModelAllowed("xopdeepseekocr"), "配置可缩小集合");
        assertFalse(props.isModelAllowed("xophunyuanocr"), "配置可缩小集合");
        assertFalse(props.isModelAllowed("spark-x2.6"), "配置不能扩张到通用 LLM");
    }
}
