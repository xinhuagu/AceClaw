package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only daily activity journal stored as markdown files.
 *
 * <p>Storage: {@code {memoryDir}/journal/YYYY-MM-DD.md}
 *
 * <p>Each journal file is capped at {@value #MAX_LINES_PER_FILE} lines.
 * Entries beyond the cap are silently dropped to prevent unbounded growth.
 */
public final class DailyJournal {

    private static final Logger log = LoggerFactory.getLogger(DailyJournal.class);

    static final int MAX_LINES_PER_FILE = 500;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path journalDir;

    /**
     * Creates a journal rooted at the given memory directory.
     *
     * @param memoryDir the memory directory (e.g. ~/.aceclaw/memory/ or workspace memory dir)
     * @throws IOException if the journal directory cannot be created
     */
    public DailyJournal(Path memoryDir) throws IOException {
        this.journalDir = memoryDir.resolve("journal");
        Files.createDirectories(journalDir);
    }

    /**
     * Appends a timestamped entry to today's journal file.
     *
     * @param entry the journal entry text (single or multi-line)
     */
    public void append(String entry) {
        if (entry == null || entry.isBlank()) return;

        Path file = fileForDate(LocalDate.now());
        try {
            // Check line count before appending
            long lineCount = 0;
            if (Files.isRegularFile(file)) {
                lineCount = Files.lines(file).count();
            }
            if (lineCount >= MAX_LINES_PER_FILE) {
                log.debug("Journal file {} at max capacity ({} lines), skipping append",
                        file.getFileName(), MAX_LINES_PER_FILE);
                return;
            }

            String timestamp = Instant.now().toString();
            String formatted = "- [" + timestamp + "] " + entry.strip() + "\n";
            Files.writeString(file, formatted,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write journal entry: {}", e.getMessage());
        }
    }

    /**
     * Loads all entries from a specific day's journal.
     *
     * @param date the date to load
     * @return list of journal lines, or empty list if no journal exists for that day
     */
    public List<String> loadDay(LocalDate date) {
        Path file = fileForDate(date);
        if (!Files.isRegularFile(file)) return List.of();

        try {
            return Files.readAllLines(file).stream()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to read journal for {}: {}", date, e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads entries from today and yesterday (the "recent window").
     *
     * @return combined entries from today and yesterday, today first
     */
    public List<String> loadRecentWindow() {
        var result = new ArrayList<String>();
        var today = LocalDate.now();
        result.addAll(loadDay(today));
        result.addAll(loadDay(today.minusDays(1)));
        return result;
    }

    /**
     * Returns the journal directory path.
     */
    public Path journalDir() {
        return journalDir;
    }

    /**
     * Returns the file path for a given date's journal.
     */
    Path fileForDate(LocalDate date) {
        return journalDir.resolve(date.format(DATE_FORMAT) + ".md");
    }
}
