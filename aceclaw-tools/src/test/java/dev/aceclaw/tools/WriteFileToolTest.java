package dev.aceclaw.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WriteFileTool} including read-before-write enforcement.
 */
class WriteFileToolTest {

    @TempDir
    Path workDir;

    private Set<Path> readFiles;
    private WriteFileTool writeTool;

    @BeforeEach
    void setUp() {
        readFiles = ConcurrentHashMap.newKeySet();
        writeTool = new WriteFileTool(workDir, readFiles);
    }

    @Test
    void writesNewFileSuccessfully() throws Exception {
        var result = writeTool.execute("""
                {"file_path": "hello.txt", "content": "Hello, world!"}
                """);
        assertFalse(result.isError());
        assertEquals("Hello, world!", Files.readString(workDir.resolve("hello.txt")));
    }

    @Test
    void createsParentDirectories() throws Exception {
        var result = writeTool.execute("""
                {"file_path": "a/b/c/deep.txt", "content": "deep content"}
                """);
        assertFalse(result.isError());
        assertTrue(Files.exists(workDir.resolve("a/b/c/deep.txt")));
    }

    @Test
    void rejectsOverwriteWithoutPriorRead() throws Exception {
        // Create an existing file
        Files.writeString(workDir.resolve("existing.txt"), "original");

        var result = writeTool.execute("""
                {"file_path": "existing.txt", "content": "overwritten"}
                """);
        assertTrue(result.isError());
        assertTrue(result.output().contains("reading it first"));
        // Original content should be preserved
        assertEquals("original", Files.readString(workDir.resolve("existing.txt")));
    }

    @Test
    void allowsOverwriteAfterRead() throws Exception {
        Path file = workDir.resolve("existing.txt");
        Files.writeString(file, "original");

        // Simulate a read by marking the file
        readFiles.add(file.toAbsolutePath().normalize());

        var result = writeTool.execute("""
                {"file_path": "existing.txt", "content": "updated"}
                """);
        assertFalse(result.isError());
        assertEquals("updated", Files.readString(file));
    }

    @Test
    void newFileDoesNotRequirePriorRead() throws Exception {
        // New files should be writable without prior read
        var result = writeTool.execute("""
                {"file_path": "brand-new.txt", "content": "fresh content"}
                """);
        assertFalse(result.isError());
    }

    @Test
    void writtenFileIsMarkedAsRead() throws Exception {
        writeTool.execute("""
                {"file_path": "first-write.txt", "content": "v1"}
                """);

        // Subsequent overwrite should work because write marks as read
        var result = writeTool.execute("""
                {"file_path": "first-write.txt", "content": "v2"}
                """);
        assertFalse(result.isError());
        assertEquals("v2", Files.readString(workDir.resolve("first-write.txt")));
    }

    @Test
    void missingFilePathReturnsError() throws Exception {
        var result = writeTool.execute("""
                {"content": "orphan content"}
                """);
        assertTrue(result.isError());
        assertTrue(result.output().contains("file_path"));
    }

    @Test
    void missingContentReturnsError() throws Exception {
        var result = writeTool.execute("""
                {"file_path": "test.txt"}
                """);
        assertTrue(result.isError());
        assertTrue(result.output().contains("content"));
    }

    @Test
    void markFileReadIntegration() {
        Path file = workDir.resolve("tracked.txt").toAbsolutePath().normalize();
        writeTool.markFileRead(file);
        assertTrue(readFiles.contains(file));
    }
}
