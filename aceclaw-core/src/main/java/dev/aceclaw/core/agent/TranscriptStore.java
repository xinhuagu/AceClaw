package dev.aceclaw.core.agent;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists sub-agent conversation transcripts as JSONL files.
 *
 * <p>File layout: {@code ~/.aceclaw/transcripts/{sessionId}/{taskId}.jsonl}
 *
 * <p>Each transcript is a single JSON object per file containing the full
 * conversation history of a sub-agent execution.
 */
public final class TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(TranscriptStore.class);
    private static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);

    private final Path baseDir;
    private final ObjectMapper mapper;

    /**
     * Creates a transcript store rooted at the given directory.
     *
     * @param baseDir the base directory for transcript storage
     *                (typically {@code ~/.aceclaw/transcripts/})
     */
    public TranscriptStore(Path baseDir) {
        this.baseDir = baseDir;
        this.mapper = createMapper();
    }

    /**
     * Saves a transcript for the given session.
     *
     * @param sessionId  the session that spawned this sub-agent
     * @param transcript the transcript to persist
     * @throws IOException if writing fails
     */
    public void save(String sessionId, SubAgentTranscript transcript) throws IOException {
        Path sessionDir = baseDir.resolve(sessionId);
        Files.createDirectories(sessionDir);

        Path file = sessionDir.resolve(transcript.taskId() + ".jsonl");
        String json = mapper.writeValueAsString(transcript);
        Files.writeString(file, json + "\n");

        log.debug("Saved transcript: session={}, task={}, messages={}",
                sessionId, transcript.taskId(), transcript.messages().size());
    }

    /**
     * Loads a transcript by task ID, searching across all sessions.
     *
     * @param taskId the task ID to find
     * @return the transcript, or empty if not found
     */
    public Optional<SubAgentTranscript> load(String taskId) {
        if (!Files.isDirectory(baseDir)) {
            return Optional.empty();
        }

        String fileName = taskId + ".jsonl";
        try (Stream<Path> sessions = Files.list(baseDir)) {
            return sessions
                    .filter(Files::isDirectory)
                    .map(sessionDir -> sessionDir.resolve(fileName))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .flatMap(this::readTranscript);
        } catch (IOException e) {
            log.warn("Failed to search transcripts for task {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes transcript files older than the specified max age.
     *
     * @param maxAge maximum age of transcripts to keep
     * @return the number of files deleted
     */
    public int cleanup(Duration maxAge) {
        if (!Files.isDirectory(baseDir)) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(maxAge);
        int deleted = 0;

        try (Stream<Path> sessions = Files.list(baseDir)) {
            var sessionDirs = sessions.filter(Files::isDirectory).toList();
            for (Path sessionDir : sessionDirs) {
                deleted += cleanupSessionDir(sessionDir, cutoff);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup transcripts: {}", e.getMessage());
        }

        if (deleted > 0) {
            log.info("Cleaned up {} transcript files older than {} days", deleted, maxAge.toDays());
        }
        return deleted;
    }

    /**
     * Deletes transcript files older than 7 days.
     *
     * @return the number of files deleted
     */
    public int cleanup() {
        return cleanup(DEFAULT_MAX_AGE);
    }

    private int cleanupSessionDir(Path sessionDir, Instant cutoff) {
        int deleted = 0;
        try (Stream<Path> files = Files.list(sessionDir)) {
            var transcriptFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();

            for (Path file : transcriptFiles) {
                try {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(cutoff)) {
                        Files.delete(file);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("Failed to delete transcript {}: {}", file, e.getMessage());
                }
            }

            // Remove empty session dirs
            try (Stream<Path> remaining = Files.list(sessionDir)) {
                if (remaining.findAny().isEmpty()) {
                    Files.delete(sessionDir);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to clean session dir {}: {}", sessionDir, e.getMessage());
        }
        return deleted;
    }

    private Optional<SubAgentTranscript> readTranscript(Path file) {
        try {
            String json = Files.readString(file).trim();
            return Optional.of(mapper.readValue(json, SubAgentTranscript.class));
        } catch (IOException e) {
            log.warn("Failed to read transcript {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static ObjectMapper createMapper() {
        var ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("dev.aceclaw.")
                .allowIfBaseType("java.util.")
                .allowIfBaseType("java.time.")
                .build();

        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // EVERYTHING typing is needed because Message/ContentBlock are sealed
        // interfaces with record implementations (which are final — NON_FINAL skips them).
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
