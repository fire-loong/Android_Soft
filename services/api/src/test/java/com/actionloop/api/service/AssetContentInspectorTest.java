package com.actionloop.api.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传内容嗅探：假扩展名、可执行文件、宏文档与多形态放行（指导书 17.3 B-04）。 */
class AssetContentInspectorTest {

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void detectsCommonAllowedTypes() throws Exception {
        assertThat(AssetContentInspector.inspect(stream("%PDF-1.7\nrest")).detectedMime())
                .isEqualTo("application/pdf");
        assertThat(AssetContentInspector.inspect(stream(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1}))
                .detectedMime()).isEqualTo("image/png");
        assertThat(AssetContentInspector.inspect(stream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1}))
                .detectedMime()).isEqualTo("image/jpeg");
        assertThat(AssetContentInspector.inspect(stream("hello world")).detectedMime()).isEqualTo("text/plain");
    }

    @Test
    void rejectsExecutablesAndLegacyOfficeContainers() throws Exception {
        assertThat(AssetContentInspector.inspect(stream(new byte[]{'M', 'Z', (byte) 0x90, 0, 3}))
                .rejection()).contains("可执行文件");
        assertThat(AssetContentInspector.inspect(stream(new byte[]{0x7F, 'E', 'L', 'F', 2, 1}))
                .rejection()).contains("可执行文件");
        assertThat(AssetContentInspector.inspect(stream(new byte[]{(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE}))
                .rejection()).contains("可执行文件");
        assertThat(AssetContentInspector.inspect(stream(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0}))
                .rejection()).contains("宏");
    }

    @Test
    void fakeExtensionIsRejectedByDeclaredTypeMismatch() throws Exception {
        var png = AssetContentInspector.inspect(stream(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertThat(AssetContentInspector.matchDeclared("application/pdf", png.detectedMime()))
                .contains("不一致");
        assertThat(AssetContentInspector.matchDeclared("image/png", png.detectedMime())).isNull();
        assertThat(AssetContentInspector.matchDeclared("application/octet-stream", png.detectedMime())).isNull();
    }

    @Test
    void unknownBinaryRequiresGenericDeclaration() throws Exception {
        var unknown = AssetContentInspector.inspect(stream(new byte[]{0x00, 0x01, 0x02, 0x03, (byte) 0xFF}));
        assertThat(unknown.detectedMime()).isNull();
        assertThat(unknown.rejected()).isFalse();
        assertThat(AssetContentInspector.matchDeclared("application/pdf", null)).contains("无法识别");
        assertThat(AssetContentInspector.matchDeclared("application/octet-stream", null)).isNull();
    }

    @Test
    void zipContainerDistinguishesOoxmlAndRejectsMacros() throws Exception {
        var docx = AssetContentInspector.inspectZipContainer(
                stream(zip("word/document.xml", "content")));
        assertThat(docx.detectedMime()).isEqualTo(DOCX);

        var macro = AssetContentInspector.inspectZipContainer(
                stream(zip("word/document.xml", "x", "word/vbaProject.bin", "macro")));
        assertThat(macro.rejected()).isTrue();
        assertThat(macro.rejection()).contains("宏");

        var plainZip = AssetContentInspector.inspectZipContainer(stream(zip("notes/readme.txt", "hi")));
        assertThat(plainZip.detectedMime()).isEqualTo("application/zip");
    }

    @Test
    void initRejectsDangerousDeclarationsUpFront() {
        assertThat(AssetContentInspector.rejectDeclared("application/x-msdownload")).isNotNull();
        assertThat(AssetContentInspector.rejectDeclared(
                "application/vnd.ms-word.document.macroEnabled.12")).isNotNull();
        assertThat(AssetContentInspector.rejectDeclared(DOCX)).isNull();
        assertThat(AssetContentInspector.rejectDeclared("image/png")).isNull();
    }

    private static ByteArrayInputStream stream(String text) {
        return stream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] zip(String... nameAndBody) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i + 1 < nameAndBody.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndBody[i]));
                zip.write(nameAndBody[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
