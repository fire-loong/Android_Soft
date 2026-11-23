package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 长截图分段 OCR：切片 → 逐段调用同一 Provider → 重叠带去重合并。
 * 普通图片完全保持原来的单次识别路径；无法解码的图片也不猜测切片位置，退回整图识别。
 */
public final class LongScreenshotOcr {

    public record SegmentOcr(int index, int yOffset, int height, String text) {
    }

    /** imageWidth/imageHeight 在图片头信息不可读时为 null；segments 仅在真正分段时非空。 */
    public record Result(Integer imageWidth, Integer imageHeight, boolean segmented, String text,
                         int droppedLines, List<SegmentOcr> segments) {
    }

    public interface Progress {
        void onPlanned(int total);

        void onSegmentDone(int done, int total);
    }

    private static final Progress NO_PROGRESS = new Progress() {
        @Override
        public void onPlanned(int total) {
        }

        @Override
        public void onSegmentDone(int done, int total) {
        }
    };

    private LongScreenshotOcr() {
    }

    public static Result recognize(byte[] content, String mime, String model, OcrProvider provider,
                                   OcrProperties props, Progress progress) {
        Progress listener = progress == null ? NO_PROGRESS : progress;
        ImageMeta meta = probe(content, props.getMaxImagePixels());
        OcrSegmentPlanner.Settings settings = settings(props);
        if (meta == null || !OcrSegmentPlanner.isLongImage(meta.width(), meta.height(), settings)) {
            return single(content, mime, model, provider, meta);
        }

        List<OcrSegmentPlanner.Segment> plan = OcrSegmentPlanner.plan(meta.width(), meta.height(), settings);
        if (plan.size() > Math.max(1, props.getMaxSegments())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "长截图需要 " + plan.size() + " 段识别，超过上限 " + props.getMaxSegments()
                            + " 段；请裁剪或分段上传后再识别");
        }
        if (plan.size() <= 1) {
            return single(content, mime, model, provider, meta);
        }

        BufferedImage image = readImage(content);
        if (image == null || image.getWidth() != meta.width() || image.getHeight() != meta.height()) {
            return single(content, mime, model, provider, meta);
        }

        listener.onPlanned(plan.size());
        List<SegmentOcr> segments = new ArrayList<>(plan.size());
        List<OcrTextMerger.SegmentText> texts = new ArrayList<>(plan.size());
        for (OcrSegmentPlanner.Segment segment : plan) {
            BufferedImage slice = image.getSubimage(0, segment.yOffset(), image.getWidth(), segment.height());
            byte[] png = encodePng(slice);
            String text = provider.recognize(new OcrProvider.OcrInput(png, "image/png", model));
            segments.add(new SegmentOcr(segment.index(), segment.yOffset(), segment.height(), text));
            texts.add(new OcrTextMerger.SegmentText(text, segment.height(), segment.overlapWithPrevious()));
            listener.onSegmentDone(segment.index() + 1, plan.size());
        }
        OcrTextMerger.Merged merged = OcrTextMerger.merge(texts);
        return new Result(meta.width(), meta.height(), true, merged.text(), merged.droppedLines(), List.copyOf(segments));
    }

    private static Result single(byte[] content, String mime, String model, OcrProvider provider, ImageMeta meta) {
        String text = provider.recognize(new OcrProvider.OcrInput(content, mime, model));
        return new Result(meta == null ? null : meta.width(), meta == null ? null : meta.height(),
                false, text, 0, List.of());
    }

    private static OcrSegmentPlanner.Settings settings(OcrProperties props) {
        return new OcrSegmentPlanner.Settings(props.isSegmentEnabled(), props.getLongImageMinHeight(),
                props.getLongImageAspectRatio(), props.getSegmentHeight(), props.getSegmentOverlap());
    }

    /** 只读图片头信息；像素总量设上限，防止超大图在解码阶段耗尽内存。 */
    private static ImageMeta probe(byte[] content, long maxPixels) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                if ((long) width * height > maxPixels) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "图片像素过大（" + width + "×" + height + "），已拒绝云端识别");
                }
                return new ImageMeta(width, height);
            } catch (IOException | IndexOutOfBoundsException e) {
                return null;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage readImage(byte[] content) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return ImageIO.read(input);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "长截图切片编码失败");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "长截图切片编码失败");
        }
    }

    private record ImageMeta(int width, int height) {
    }
}
