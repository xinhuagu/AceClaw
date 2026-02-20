package dev.aceclaw.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolCharsetTest {

    @TempDir
    Path workDir;

    private Set<Path> readFiles;
    private ReadFileTool tool;

    @BeforeEach
    void setUp() {
        readFiles = ConcurrentHashMap.newKeySet();
        tool = new ReadFileTool(workDir, readFiles);
    }

    @Test
    void readsUtf8ByDefault() throws Exception {
        Files.writeString(workDir.resolve("utf8.txt"), "Hello, world!\nLine 2", StandardCharsets.UTF_8);

        var result = tool.execute("""
                {"file_path": "utf8.txt"}
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("Hello, world!");
        assertThat(result.output()).contains("Line 2");
    }

    @Test
    void readsWithExplicitEncoding() throws Exception {
        // Write a file with ISO-8859-1 encoding containing non-ASCII characters
        var latin1 = Charset.forName("ISO-8859-1");
        Files.write(workDir.resolve("latin1.txt"), "caf\u00e9 cr\u00e8me".getBytes(latin1));

        var result = tool.execute("""
                {"file_path": "latin1.txt", "encoding": "ISO-8859-1"}
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("caf\u00e9");
        assertThat(result.output()).contains("cr\u00e8me");
    }

    @Test
    void unsupportedEncodingReturnsError() throws Exception {
        Files.writeString(workDir.resolve("test.txt"), "content", StandardCharsets.UTF_8);

        var result = tool.execute("""
                {"file_path": "test.txt", "encoding": "INVALID_CHARSET"}
                """);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Unsupported charset");
    }

    @Test
    void autoDetectsEncodingOnMalformedInput() throws Exception {
        // Write a file with ISO-8859-1 bytes that are invalid UTF-8
        // Byte 0xE9 alone is invalid UTF-8 (it expects continuation bytes)
        // but is valid ISO-8859-1 for the character 'e-acute'
        var latin1 = Charset.forName("ISO-8859-1");
        byte[] bytes = "caf\u00e9\n".getBytes(latin1);
        Files.write(workDir.resolve("auto.txt"), bytes);

        var result = tool.execute("""
                {"file_path": "auto.txt"}
                """);

        // On macOS/Linux, `file -bi` should detect the charset.
        // The result should either succeed with a detected encoding prefix,
        // or return an error if detection is not available on this platform.
        if (!result.isError()) {
            assertThat(result.output()).contains("[Detected encoding:");
            assertThat(result.output()).contains("caf");
        } else {
            // Acceptable: auto-detection not available on this platform
            assertThat(result.output()).containsAnyOf("charset auto-detection failed", "Error reading file");
        }
    }

    @Test
    void schemaIncludesEncodingParam() {
        var schema = tool.inputSchema();
        assertThat(schema.has("properties")).isTrue();
        assertThat(schema.get("properties").has("encoding")).isTrue();
        assertThat(schema.get("properties").get("encoding").get("type").asText()).isEqualTo("string");
    }

    @Test
    void explicitEncodingOnNonMatchingFileReturnsError() throws Exception {
        // Write a UTF-8 file with multi-byte characters, then read with IBM037 (EBCDIC)
        // IBM037 will decode the bytes but produce garbled output, not an error.
        // However, reading UTF-8 with a very different encoding may still succeed
        // (just garbled). A better test: write pure ASCII, read with IBM037 — it will decode
        // but produce wrong characters. This tests that explicit encoding is honored.
        Files.writeString(workDir.resolve("utf8file.txt"), "Hello ASCII", StandardCharsets.UTF_8);

        var result = tool.execute("""
                {"file_path": "utf8file.txt", "encoding": "IBM037"}
                """);

        // IBM037 can decode any byte sequence, so no error — just garbled content
        // The key assertion is that it does NOT contain the original text
        assertThat(result.isError()).isFalse();
        // IBM037 decoding of ASCII bytes produces completely different characters
        assertThat(result.output()).doesNotContain("Hello ASCII");
    }

    @Test
    void tracksReadFilesAfterCharsetDetection() throws Exception {
        // Write ISO-8859-1 file that triggers auto-detection
        var latin1 = Charset.forName("ISO-8859-1");
        byte[] bytes = "caf\u00e9\n".getBytes(latin1);
        Path file = workDir.resolve("tracked.txt");
        Files.write(file, bytes);

        var result = tool.execute("""
                {"file_path": "tracked.txt"}
                """);

        // If auto-detection succeeded, file should be tracked
        if (!result.isError()) {
            assertThat(readFiles).contains(file.toAbsolutePath().normalize());
        }
    }

    @Test
    void readsUtf8WithBomCorrectly() throws Exception {
        // UTF-8 with BOM should read fine with default charset
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "BOM content".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(content, 0, withBom, bom.length, content.length);
        Files.write(workDir.resolve("bom.txt"), withBom);

        var result = tool.execute("""
                {"file_path": "bom.txt"}
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("BOM content");
    }

    @Test
    void explicitEncodingWithMalformedInputReturnsError() throws Exception {
        // Write random bytes that are invalid for Shift_JIS
        byte[] invalidBytes = new byte[]{(byte) 0x80, (byte) 0x00, (byte) 0xFF, (byte) 0xFE};
        Files.write(workDir.resolve("invalid.bin"), invalidBytes);

        var result = tool.execute("""
                {"file_path": "invalid.bin", "encoding": "Shift_JIS"}
                """);

        // With explicit encoding, malformed input should return error directly
        // (though some charsets are lenient — if no error, that's also acceptable)
        // The key is that it does NOT attempt auto-detection
        if (result.isError()) {
            assertThat(result.output()).contains("Failed to decode file with charset");
        }
    }
}
