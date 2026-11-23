package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongScreenshotOcrTest {

    private static final OcrProperties PROPS = new OcrProperties();

    @Test
    void longScreenshotIsSlicedRecognizedAndMergedInOrder() throws Exception {
        byte[] image = png(400, 2400);
        AtomicInteger calls = new AtomicInteger();
        List<Integer> receivedHeights = new ArrayList<>();
        OcrProvider provider = input -> {
            BufferedImage slice = read(input.content());
            receivedHeights.add(slice.getHeight());
            int call = calls.getAndIncrement();
            return call == 0 ? "第一段标题\n重叠行" : "重叠行\n第二段正文";
        };

        List<String> progress = new ArrayList<>();
        LongScreenshotOcr.Result result = LongScreenshotOcr.recognize(image, "image/png", "xoppaddleocrv16",
                provider, PROPS, new LongScreenshotOcr.Progress() {
                    @Override
                    public void onPlanned(int total) {
                        progress.add("plan:" + total);
                    }

                    @Override
                    public void onSegmentDone(int done, int total) {
                        progress.add(done + "/" + total);
                    }
                });

        assertTrue(result.segmented());
        assertEquals(400, result.imageWidth());
        assertEquals(2400, result.imageHeight());
        assertEquals(2, result.segments().size());
        assertEquals(0, result.segments().get(0).yOffset());
        assertEquals(1220, result.segments().get(1).yOffset());
        assertEquals("第一段标题\n重叠行\n第二段正文", result.text());
        assertEquals(1, result.droppedLines());
        assertEquals(List.of("plan:2", "1/2", "2/2"), progress);
        assertEquals(2, calls.get(), "每段只调用一次 OCR");
        assertEquals(List.of(1400, 1180), receivedHeights);
    }

    @Test
    void ordinaryImageIsSentWholeWithoutSlicing() throws Exception {
        byte[] image = png(900, 1200);
        List<byte[]> received = new ArrayList<>();
        OcrProvider provider = input -> {
            received.add(input.content());
            return "普通图片文本";
        };

        LongScreenshotOcr.Result result = LongScreenshotOcr.recognize(image, "image/png", "m", provider, PROPS, null);

        assertFalse(result.segmented());
        assertTrue(result.segments().isEmpty());
        assertEquals("普通图片文本", result.text());
        assertEquals(1, received.size());
        assertEquals(image.length, received.get(0).length);
        assertEquals(900, result.imageWidth());
        assertEquals(1200, result.imageHeight());
    }

    @Test
    void undecodableContentFallsBackToSingleProviderCall() {
        byte[] broken = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        OcrProvider provider = input -> "降级文本";

        LongScreenshotOcr.Result result = LongScreenshotOcr.recognize(broken, "image/png", "m", provider, PROPS, null);

        assertFalse(result.segmented());
        assertNull(result.imageWidth());
        assertEquals("降级文本", result.text());
    }

    @Test
    void tooManySegmentsIsRejectedWithActionableMessage() throws Exception {
        byte[] image = png(100, 24000);

        BusinessException failure = assertThrows(BusinessException.class, () ->
                LongScreenshotOcr.recognize(image, "image/png", "m", input -> "x", PROPS, null));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.code());
        assertTrue(failure.getMessage().contains("超过上限"));
    }

    @Test
    void oversizedPixelCountIsRejectedBeforeDecoding() throws Exception {
        OcrProperties strict = new OcrProperties();
        strict.setMaxImagePixels(1000);
        byte[] image = png(100, 200);

        BusinessException failure = assertThrows(BusinessException.class, () ->
                LongScreenshotOcr.recognize(image, "image/png", "m", input -> "x", strict, null));

        assertEquals(ErrorCode.VALIDATION_FAILED, failure.code());
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static BufferedImage read(byte[] content) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
