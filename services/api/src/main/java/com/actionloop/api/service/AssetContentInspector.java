package com.actionloop.api.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 上传内容类型嗅探（指导书 11.6「服务端对文件做类型 sniffing，不能信任扩展名」/ 15.4）。
 * 只读取对象开头魔数与 OOXML 包内条目名，不解压正文、不执行宏、不落临时文件。
 */
public final class AssetContentInspector {

    /** 嗅探结论。detectedMime 为 null 表示无法识别的二进制。 */
    public record Inspection(String detectedMime, String rejection) {
        public boolean rejected() {
            return rejection != null;
        }
    }

    /** 允许的内容类型（与指导书 10.x「PDF/DOCX/TXT」及截图/OCR 需求一致）。 */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/zip");

    /** 声明即拒绝的类型：可执行文件与启用宏的 Office 容器。 */
    private static final Set<String> DECLARED_BLOCKED_MIME = Set.of(
            "application/x-msdownload", "application/x-msdos-program", "application/x-executable",
            "application/x-elf", "application/x-sharedlib", "application/java-vm", "application/x-java-applet",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/vnd.ms-excel.sheet.macroenabled.12",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "application/vnd.ms-office.vba",
            "application/vnd.ms-word", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/msword", "application/vnd.ms-htmlhelp");

    private static final int HEADER_LIMIT = 64 * 1024;
    /** 文本判定只看开头 512 字节；末尾可能截断多字节字符，解码时按 0-3 字节回退重试。 */
    private static final int TEXT_SAMPLE = 512;
    private static final int MAX_ZIP_ENTRIES = 2048;

    private AssetContentInspector() {
    }

    /** 只按内容开头魔数判断类型与拦截项，不比较声明类型（zip 需二次识别，由调用方统一比较）。 */
    public static Inspection inspect(InputStream raw) throws IOException {
        try (InputStream in = new BufferedInputStream(raw, HEADER_LIMIT)) {
            in.mark(HEADER_LIMIT);
            byte[] head = in.readNBytes(TEXT_SAMPLE);
            in.reset();

            String detected = detectHead(head);
            if (detected == null) {
                return new Inspection(null, null);
            }
            if (isBlockedDetected(detected)) {
                return new Inspection(detected, blockedReason(detected));
            }
            if (!ALLOWED_MIME.contains(detected)) {
                return new Inspection(detected, "不支持的内容类型：" + detected);
            }
            return new Inspection(detected, null);
        }
    }

    /**
     * 比较声明类型与实际类型。声明为通用二进制（application/octet-stream / 空）时以实际类型为准；
     * 未知内容只有声明为通用二进制才放行。
     */
    public static String matchDeclared(String declaredMime, String detectedMime) {
        String declared = normalize(declaredMime);
        boolean generic = declared == null || declared.isEmpty() || "application/octet-stream".equals(declared);
        if (detectedMime == null) {
            return generic ? null : "内容无法识别为 " + declared + "，已拒绝";
        }
        if (generic || declared.equals(detectedMime)) {
            return null;
        }
        return "声明类型 " + declared + " 与实际内容 " + detectedMime + " 不一致，已拒绝";
    }

    /** init 阶段快速拒绝明显危险的声明类型，避免创建上传会话。 */
    public static String rejectDeclared(String declaredMime) {
        String declared = normalize(declaredMime);
        if (declared != null && DECLARED_BLOCKED_MIME.contains(declared)) {
            return "不支持的文件类型：" + declared;
        }
        return null;
    }

    public static String normalize(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
        // 常见别名归一
        return switch (lower) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "text/plain;charset=utf-8" -> "text/plain";
            default -> lower;
        };
    }

    private static String detectHead(byte[] h) {
        if (startsWith(h, new byte[]{'%', 'P', 'D', 'F', '-'})) {
            return "application/pdf";
        }
        if (startsWith(h, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) {
            return "image/png";
        }
        if (startsWith(h, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(h, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(h, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return "image/gif";
        }
        if (startsWith(h, "RIFF".getBytes(StandardCharsets.US_ASCII)) && h.length >= 12
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        // 可执行与可加载文件
        if (startsWith(h, new byte[]{'M', 'Z'})) {
            return "application/x-msdownload";
        }
        if (startsWith(h, new byte[]{0x7F, 'E', 'L', 'F'})) {
            return "application/x-elf";
        }
        if (startsWith(h, new byte[]{(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE})
                || startsWith(h, new byte[]{(byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCF})) {
            return "application/x-mach-binary";
        }
        if (startsWith(h, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})) {
            return "application/java-vm";
        }
        if (startsWith(h, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1})) {
            return "application/x-ole-storage";
        }
        if (startsWith(h, new byte[]{'P', 'K', 3, 4})) {
            return "application/zip";
        }
        if (looksLikeText(h)) {
            return "text/plain";
        }
        return null;
    }

    private static boolean isBlockedDetected(String detected) {
        return switch (detected) {
            case "application/x-msdownload", "application/x-elf", "application/x-mach-binary",
                 "application/java-vm", "application/x-ole-storage" -> true;
            default -> false;
        };
    }

    private static String blockedReason(String detected) {
        return switch (detected) {
            case "application/x-ole-storage" -> "旧版 Office/OLE 文档可能包含宏，请另存为 docx/xlsx/pptx 或 PDF 后上传";
            default -> "可执行文件不允许上传（" + detected + "）";
        };
    }

    /**
     * 扫描 OOXML/zip 包内条目名：区分 docx/xlsx/pptx，并拒绝含宏工程 vbaProject.bin 的包。
     * 只遍历本地文件头并按声明长度跳过压缩数据，绝不解压正文（避免压缩炸弹占用 CPU/内存）；
     * 使用流式大小（data descriptor）的 zip 因无法安全跳过，直接拒绝并提示重新打包。
     */
    public static Inspection inspectZipContainer(InputStream raw) throws IOException {
        try (InputStream in = new BufferedInputStream(raw, 8192)) {
            boolean hasWord = false;
            boolean hasExcel = false;
            boolean hasPpt = false;
            int entries = 0;
            while (true) {
                int sig;
                try {
                    sig = readIntLE(in);
                } catch (java.io.EOFException e) {
                    break;
                }
                if (sig != SIG_LOCAL_HEADER) {
                    // 中央目录或结束记录，条目扫描结束
                    break;
                }
                if (++entries > MAX_ZIP_ENTRIES) {
                    return new Inspection("application/zip", "压缩包条目过多，已拒绝");
                }
                skipFully(in, 2);                       // version needed
                int flags = readU16(in);
                int method = readU16(in);
                skipFully(in, 2);                       // last mod time
                skipFully(in, 2);                       // last mod date
                skipFully(in, 4);                       // crc32
                long compressedSize = readU32(in);
                readU32(in);                            // uncompressed size（不需要）
                int nameLen = readU16(in);
                int extraLen = readU16(in);
                byte[] nameBytes = in.readNBytes(nameLen);
                if (nameBytes.length < nameLen) {
                    throw new java.io.EOFException("zip 条目名不完整");
                }
                skipFully(in, extraLen);
                String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin")) {
                    return new Inspection("application/zip", "压缩包内含宏工程（vbaProject.bin），已拒绝");
                }
                if (name.equals("word/document.xml")) {
                    hasWord = true;
                } else if (name.equals("xl/workbook.xml")) {
                    hasExcel = true;
                } else if (name.equals("ppt/presentation.xml")) {
                    hasPpt = true;
                }
                boolean hasDataDescriptor = (flags & 0x08) != 0;
                if (hasDataDescriptor || compressedSize == 0) {
                    if (method != 0 || compressedSize == 0) {
                        return new Inspection("application/zip",
                                "压缩包未在条目头声明压缩大小，无法安全扫描，请用标准方式重新打包后上传");
                    }
                }
                skipFully(in, compressedSize);
            }
            if (hasWord) {
                return new Inspection(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null);
            }
            if (hasExcel) {
                return new Inspection(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null);
            }
            if (hasPpt) {
                return new Inspection(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation", null);
            }
            return new Inspection("application/zip", null);
        }
    }

    private static final int SIG_LOCAL_HEADER = 0x04034b50;

    private static int readIntLE(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            throw new java.io.EOFException();
        }
        int b1 = readByte(in);
        int b2 = readByte(in);
        int b3 = readByte(in);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readU16(InputStream in) throws IOException {
        int b0 = readByte(in);
        int b1 = readByte(in);
        return b0 | (b1 << 8);
    }

    private static long readU32(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v |= ((long) readByte(in)) << (8 * i);
        }
        return v;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new java.io.EOFException();
        }
        return b;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (in.read() < 0) {
                throw new java.io.EOFException();
            } else {
                remaining -= 1;
            }
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeText(byte[] head) {
        if (head.length == 0) {
            return false;
        }
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        // 采样末尾可能截断一个多字节字符：依次去掉 0-3 个尾字节重试，避免中文 txt 被误判为二进制。
        for (int trim = 0; trim <= 3 && trim < head.length; trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(head, 0, head.length - trim));
                return true;
            } catch (CharacterCodingException ignored) {
                // 继续尝试去掉更多尾字节
            }
        }
        return false;
    }
}
