package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Reads file contents with optional line range.
 *
 * <p>Returns file contents prefixed with line numbers. Supports offset and
 * limit parameters for reading portions of large files.
 *
 * <p>Integrates with {@link WriteFileTool} to track which files have been read,
 * enforcing the read-before-write safety requirement.
 */
public final class ReadFileTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    /** Maximum lines to return when no limit is specified. */
    private static final int DEFAULT_LIMIT = 2000;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    /** Maximum characters per line before truncation. */
    private static final int MAX_LINE_LENGTH = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    /** Shared set with WriteFileTool tracking which files have been read. */
    private final Set<Path> readFiles;

    public ReadFileTool(Path workingDir) {
        this(workingDir, null);
    }

    /**
     * Creates a ReadFileTool with a shared read-tracking set.
     *
     * @param workingDir the working directory for resolving relative paths
     * @param readFiles  shared set tracking which files have been read (may be null)
     */
    public ReadFileTool(Path workingDir, Set<Path> readFiles) {
        this.workingDir = workingDir;
        this.readFiles = readFiles;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("file_path", SchemaBuilder.string(
                        "Absolute or relative path to the file to read"))
                .optionalProperty("offset", SchemaBuilder.integer(
                        "Line number to start reading from (1-based). Defaults to 1."))
                .optionalProperty("limit", SchemaBuilder.integer(
                        "Maximum number of lines to read. Defaults to " + DEFAULT_LIMIT + "."))
                .optionalProperty("encoding", SchemaBuilder.string(
                        "Charset name (e.g. \"ISO-8859-1\", \"Shift_JIS\"). Defaults to UTF-8 with auto-detection fallback."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("file_path") || input.get("file_path").isNull()
                || input.get("file_path").asText().isBlank()) {
            return new ToolResult("Missing required parameter: file_path", true);
        }

        var filePath = resolveFilePath(input);
        int offset = getIntParam(input, "offset", 1);
        int limit = getIntParam(input, "limit", DEFAULT_LIMIT);
        var encodingParam = getStringParam(input, "encoding", null);

        if (offset < 1) {
            return new ToolResult("offset must be >= 1", true);
        }
        if (limit < 1) {
            return new ToolResult("limit must be >= 1", true);
        }

        Charset charset;
        boolean explicitEncoding = encodingParam != null;
        if (explicitEncoding) {
            try {
                charset = Charset.forName(encodingParam);
            } catch (UnsupportedCharsetException e) {
                return new ToolResult("Unsupported charset: " + encodingParam, true);
            }
        } else {
            charset = StandardCharsets.UTF_8;
        }

        if (!Files.exists(filePath)) {
            return new ToolResult("File not found: " + filePath, true);
        }
        if (Files.isDirectory(filePath)) {
            return new ToolResult("Path is a directory, not a file: " + filePath, true);
        }
        if (!Files.isReadable(filePath)) {
            return new ToolResult("File is not readable: " + filePath, true);
        }

        log.debug("Reading file: {} (offset={}, limit={}, charset={})", filePath, offset, limit, charset);

        try {
            var result = readFileContents(filePath, offset, limit, charset);
            trackRead(filePath, result);
            return result;
        } catch (MalformedInputException e) {
            if (explicitEncoding) {
                return new ToolResult("Failed to decode file with charset " + charset + ": " + e.getMessage(), true);
            }
            // Auto-detect charset using `file -bi`
            var detected = detectCharset(filePath);
            if (detected == null) {
                return new ToolResult("File is not valid UTF-8 and charset auto-detection failed", true);
            }
            log.info("Auto-detected charset {} for file {}", detected, filePath);
            try {
                var result = readFileContents(filePath, offset, limit, detected);
                var prefixed = new ToolResult(
                        "[Detected encoding: " + detected.name() + "]\n" + result.output(),
                        result.isError());
                trackRead(filePath, prefixed);
                return prefixed;
            } catch (IOException retryEx) {
                log.error("Failed to read file {} with detected charset {}: {}", filePath, detected, retryEx.getMessage());
                return new ToolResult("Error reading file with detected charset " + detected + ": " + retryEx.getMessage(), true);
            }
        } catch (IOException e) {
            log.error("Failed to read file {}: {}", filePath, e.getMessage());
            return new ToolResult("Error reading file: " + e.getMessage(), true);
        }
    }

    private void trackRead(Path filePath, ToolResult result) {
        if (!result.isError() && readFiles != null) {
            readFiles.add(filePath.toAbsolutePath().normalize());
        }
    }

    private ToolResult readFileContents(Path filePath, int offset, int limit, Charset charset) throws IOException {
        var allLines = Files.readAllLines(filePath, charset);
        int totalLines = allLines.size();

        if (totalLines == 0) {
            return new ToolResult("(empty file)", false);
        }

        // offset is 1-based
        int startIndex = Math.min(offset - 1, totalLines);
        int endIndex = Math.min(startIndex + limit, totalLines);

        if (startIndex >= totalLines) {
            return new ToolResult("offset " + offset + " exceeds file length (" + totalLines + " lines)", true);
        }

        var sb = new StringBuilder();
        int lineNumWidth = String.valueOf(endIndex).length();

        for (int i = startIndex; i < endIndex; i++) {
            var line = allLines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
            }
            sb.append(String.format("%" + lineNumWidth + "d\t%s%n", i + 1, line));
        }

        // Append a summary if not all lines were returned
        if (endIndex < totalLines || startIndex > 0) {
            sb.append(String.format("%n(Showing lines %d-%d of %d total)", offset, endIndex, totalLines));
        }

        return new ToolResult(sb.toString(), false);
    }

    /**
     * Detects the charset of a file. On Unix, uses {@code file -bi} command.
     * On Windows, uses {@link Files#probeContentType} and BOM detection as fallback
     * (the {@code file} command is not available on Windows).
     *
     * @return the detected Charset, or null if detection fails
     */
    private Charset detectCharset(Path filePath) {
        if (IS_WINDOWS) {
            return detectCharsetWindows(filePath);
        }
        return detectCharsetUnix(filePath);
    }

    private Charset detectCharsetUnix(Path filePath) {
        try {
            var pb = new ProcessBuilder("file", "-bi", filePath.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output == null) {
                log.debug("file -bi exited with code {} for {}", exitCode, filePath);
                return null;
            }
            // Output format: "text/plain; charset=iso-8859-1" or similar
            var parts = output.split("charset=");
            if (parts.length < 2) {
                log.debug("No charset field in file -bi output: {}", output);
                return null;
            }
            var charsetName = parts[1].strip();
            if (charsetName.isEmpty() || "binary".equalsIgnoreCase(charsetName)
                    || "unknown-8bit".equalsIgnoreCase(charsetName)) {
                return null;
            }
            return Charset.forName(charsetName);
        } catch (UnsupportedCharsetException e) {
            log.debug("Detected charset not supported by JVM: {}", e.getCharsetName());
            return null;
        } catch (IOException | InterruptedException e) {
            log.debug("Charset detection failed for {}: {}", filePath, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Windows charset detection: checks BOM (byte order mark) at start of file.
     * Returns null (fail-safe) for files without a BOM, including binary files
     * and files in non-Latin encodings (GBK, Shift_JIS, etc.). This matches
     * the Unix behavior where {@code file -bi} returning "binary" or failing
     * causes the caller to report an error rather than silently corrupt content.
     */
    private Charset detectCharsetWindows(Path filePath) {
        try {
            byte[] header = new byte[4];
            try (var is = Files.newInputStream(filePath)) {
                int read = is.read(header);
                if (read >= 3 && header[0] == (byte) 0xEF
                        && header[1] == (byte) 0xBB && header[2] == (byte) 0xBF) {
                    return StandardCharsets.UTF_8;
                }
                if (read >= 2 && header[0] == (byte) 0xFF && header[1] == (byte) 0xFE) {
                    return StandardCharsets.UTF_16LE;
                }
                if (read >= 2 && header[0] == (byte) 0xFE && header[1] == (byte) 0xFF) {
                    return StandardCharsets.UTF_16BE;
                }
            }
            // No BOM found — return null to fail safely rather than guess an encoding
            // that may silently corrupt non-Latin or binary content
            log.debug("No BOM detected for {}; cannot determine charset on Windows", filePath);
            return null;
        } catch (IOException e) {
            log.debug("Windows charset detection failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private Path resolveFilePath(JsonNode input) {
        return PathResolver.resolve(input.get("file_path").asText(), workingDir);
    }

    private static int getIntParam(JsonNode input, String field, int defaultValue) {
        if (input.has(field) && !input.get(field).isNull()) {
            return input.get(field).asInt(defaultValue);
        }
        return defaultValue;
    }

    private static String getStringParam(JsonNode input, String field, String defaultValue) {
        if (input.has(field) && !input.get(field).isNull() && !input.get(field).asText().isBlank()) {
            return input.get(field).asText();
        }
        return defaultValue;
    }

    /**
     * #480 PR 3: structured {@link Capability.FileRead}. Path resolution
     * matches {@link #execute(String)} so policy and execution see the same
     * absolute path; otherwise a "deny /etc reads" rule could approve the
     * relative-path form and refuse only the absolute form, opening a trivial
     * bypass. Offset/limit args do NOT change the capability — reading bytes
     * 100-200 of a file is still a {@code FileRead}; range-level policy is
     * out of scope for the structural variant.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("file_path") || args.get("file_path").asText().isBlank()) {
            throw new IllegalArgumentException("read_file requires a non-blank file_path");
        }
        return new Capability.FileRead(
                PathResolver.resolve(args.get("file_path").asText(), workingDir));
    }
}
