package com.actionloop.api.ai;

/** 纯文本 OCR Provider；协议不接收坐标，避免把文本行伪称为布局块。 */
public interface OcrProvider {

    String PROVIDER = "xfyun-maas";

    record OcrInput(byte[] content, String mime, String model) {
    }

    String recognize(OcrInput input);
}
