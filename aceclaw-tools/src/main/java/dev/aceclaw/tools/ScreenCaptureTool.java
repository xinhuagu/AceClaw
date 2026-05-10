package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Captures a screenshot on macOS using {@code screencapture} and optionally
 * performs OCR using the macOS Vision framework via a Swift subprocess.
 *
 * <p>macOS only. Supports optional region capture and text extraction.
 */
public final class ScreenCaptureTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(ScreenCaptureTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int TIMEOUT_SECONDS = 30;

    private final Path workingDir;

    public ScreenCaptureTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * Returns true if the current platform is macOS.
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    @Override
    public String name() {
        return "screen_capture";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .optionalProperty("region", SchemaBuilder.string(
                        "Screen region to capture as 'x,y,width,height' (e.g. '0,0,800,600'). " +
                        "If omitted, captures the entire screen."))
                .optionalProperty("ocr", SchemaBuilder.bool(
                        "If true, extract text from the screenshot using OCR. Defaults to false."))
                .optionalProperty("output_path", SchemaBuilder.string(
                        "File path to save the screenshot. Defaults to a temp file."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        // Determine output path
        Path outputPath;
        if (input.has("output_path") && !input.get("output_path").isNull()
                && !input.get("output_path").asText().isBlank()) {
            outputPath = PathResolver.resolve(input.get("output_path").asText(), workingDir);
        } else {
            outputPath = Files.createTempFile("aceclaw-screenshot-", ".png");
        }

        // Parse region
        String region = null;
        if (input.has("region") && !input.get("region").isNull()
                && !input.get("region").asText().isBlank()) {
            region = input.get("region").asText();
            if (!region.matches("\\d+,\\d+,\\d+,\\d+")) {
                return new ToolResult(
                        "Invalid region format. Expected 'x,y,width,height' (e.g. '0,0,800,600')", true);
            }
        }

        boolean doOcr = input.has("ocr") && input.get("ocr").asBoolean(false);

        log.debug("Screen capture: output={}, region={}, ocr={}", outputPath, region, doOcr);

        try {
            // Capture screenshot
            var captureResult = captureScreen(outputPath, region);
            if (captureResult.isError()) {
                return captureResult;
            }

            var sb = new StringBuilder();
            sb.append("Screenshot saved to: ").append(outputPath);

            // OCR if requested
            if (doOcr) {
                var ocrText = performOcr(outputPath);
                if (ocrText != null) {
                    sb.append("\n\nExtracted text:\n").append(ocrText);
                } else {
                    sb.append("\n\nOCR: no text detected or OCR failed.");
                }
            }

            return new ToolResult(sb.toString(), false);

        } catch (IOException e) {
            log.error("Screen capture failed: {}", e.getMessage());
            return new ToolResult("Screen capture failed: " + e.getMessage(), true);
        }
    }

    private ToolResult captureScreen(Path outputPath, String region) throws IOException {
        var command = new ArrayList<String>();
        command.add("/usr/sbin/screencapture");
        command.add("-x"); // no sound

        if (region != null) {
            command.add("-R");
            command.add(region);
        }

        command.add(outputPath.toString());

        var process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();

        String output;
        try (var reader = process.getInputStream()) {
            output = new String(reader.readAllBytes());
        }

        boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ToolResult("Screen capture interrupted", true);
        }

        if (!completed) {
            process.destroyForcibly();
            return new ToolResult("Screen capture timed out after " + TIMEOUT_SECONDS + " seconds", true);
        }

        if (process.exitValue() != 0) {
            return new ToolResult("Screen capture failed: " + output, true);
        }

        if (!Files.exists(outputPath)) {
            return new ToolResult("Screen capture completed but output file not found", true);
        }

        return new ToolResult("OK", false);
    }

    /**
     * Performs OCR on an image file using the macOS Vision framework via a Swift subprocess.
     * Returns the extracted text, or null if OCR fails or no text is found.
     */
    private String performOcr(Path imagePath) {
        // Swift code that uses the Vision framework for text recognition
        var swiftCode = """
                import Foundation
                import Vision
                import AppKit

                let imagePath = CommandLine.arguments[1]
                guard let image = NSImage(contentsOfFile: imagePath),
                      let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
                    fputs("Failed to load image", stderr)
                    exit(1)
                }

                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = true

                let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
                try handler.perform([request])

                guard let observations = request.results else {
                    exit(0)
                }

                for observation in observations {
                    if let candidate = observation.topCandidates(1).first {
                        print(candidate.string)
                    }
                }
                """;

        try {
            var process = new ProcessBuilder("/usr/bin/swift", "-", imagePath.toString())
                    .redirectErrorStream(true)
                    .start();

            // Write the Swift code to stdin
            try (var writer = process.getOutputStream()) {
                writer.write(swiftCode.getBytes());
                writer.flush();
            }

            String output;
            try (var reader = process.getInputStream()) {
                output = new String(reader.readAllBytes());
            }

            boolean completed;
            try {
                completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return null;
            }

            if (!completed) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) {
                log.warn("OCR Swift subprocess failed (exit {}): {}", process.exitValue(), output);
                return null;
            }

            return output.isBlank() ? null : output.strip();

        } catch (IOException e) {
            log.warn("OCR failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * #480 PR 3: structured {@link Capability.ScreenCapture}. Reason is
     * derived from the args ({@code region} for partial captures, OCR
     * marker for text-extraction captures) so the audit log and prompt
     * tell the operator at a glance whether the agent took a full-screen
     * snapshot for OCR or a small region — surface for downstream privacy
     * review without inflating the variant payload.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        var reason = new StringBuilder();
        if (args != null && args.has("region") && !args.get("region").isNull()
                && !args.get("region").asText().isBlank()) {
            reason.append("region ").append(args.get("region").asText());
        }
        if (args != null && args.has("ocr") && args.get("ocr").asBoolean(false)) {
            if (reason.length() > 0) reason.append(", ");
            reason.append("ocr");
        }
        return new Capability.ScreenCapture(reason.toString());
    }
}
