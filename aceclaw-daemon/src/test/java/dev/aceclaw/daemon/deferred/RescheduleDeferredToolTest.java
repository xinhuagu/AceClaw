package dev.aceclaw.daemon.deferred;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RescheduleDeferredTool}.
 */
class RescheduleDeferredToolTest {

    private RescheduleDeferredTool tool;

    @BeforeEach
    void setUp() {
        tool = new RescheduleDeferredTool();
    }

    @Test
    void nameIsRescheduleCheck() {
        assertEquals("reschedule_check", tool.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void successfulRescheduleStoresPendingRequest() throws Exception {
        var result = tool.execute("{\"delaySeconds\": 120, \"reason\": \"CodeRabbit review not ready yet\"}");

        assertFalse(result.isError());
        assertTrue(result.output().contains("120 seconds"));
        assertTrue(result.output().contains("CodeRabbit review not ready yet"));

        var request = tool.pendingRequest();
        assertNotNull(request);
        assertEquals(120, request.delaySeconds());
        assertEquals("CodeRabbit review not ready yet", request.reason());
    }

    @Test
    void missingDelaySecondsReturnsError() throws Exception {
        var result = tool.execute("{\"reason\": \"not ready\"}");

        assertTrue(result.isError());
        assertTrue(result.output().contains("delaySeconds"));
        assertNull(tool.pendingRequest());
    }

    @Test
    void delaySecondsTooLowReturnsError() throws Exception {
        var result = tool.execute("{\"delaySeconds\": 2}");

        assertTrue(result.isError());
        assertTrue(result.output().contains("between"));
        assertNull(tool.pendingRequest());
    }

    @Test
    void delaySecondsTooHighReturnsError() throws Exception {
        var result = tool.execute("{\"delaySeconds\": 7200}");

        assertTrue(result.isError());
        assertTrue(result.output().contains("between"));
        assertNull(tool.pendingRequest());
    }

    @Test
    void delaySecondsBoundsAccepted() throws Exception {
        // Minimum bound
        var resultMin = tool.execute("{\"delaySeconds\": 5}");
        assertFalse(resultMin.isError());
        assertEquals(5, tool.pendingRequest().delaySeconds());

        // Reset
        tool = new RescheduleDeferredTool();

        // Maximum bound
        var resultMax = tool.execute("{\"delaySeconds\": 3600}");
        assertFalse(resultMax.isError());
        assertEquals(3600, tool.pendingRequest().delaySeconds());
    }

    @Test
    void missingReasonUsesDefault() throws Exception {
        var result = tool.execute("{\"delaySeconds\": 60}");

        assertFalse(result.isError());
        var request = tool.pendingRequest();
        assertNotNull(request);
        assertEquals("condition not met yet", request.reason());
    }

    @Test
    void blankReasonUsesDefault() throws Exception {
        var result = tool.execute("{\"delaySeconds\": 60, \"reason\": \"   \"}");

        assertFalse(result.isError());
        assertEquals("condition not met yet", tool.pendingRequest().reason());
    }

    @Test
    void lastCallWins() throws Exception {
        tool.execute("{\"delaySeconds\": 60, \"reason\": \"first\"}");
        tool.execute("{\"delaySeconds\": 300, \"reason\": \"second\"}");

        var request = tool.pendingRequest();
        assertNotNull(request);
        assertEquals(300, request.delaySeconds());
        assertEquals("second", request.reason());
    }

    @Test
    void noPendingRequestBeforeExecute() {
        assertNull(tool.pendingRequest());
    }

    @Test
    void invalidJsonThrowsException() {
        // Jackson throws JsonParseException for unparseable input
        assertThrows(Exception.class, () -> tool.execute("not json"));
        assertNull(tool.pendingRequest());
    }

    @Test
    void inputSchemaHasRequiredDelaySeconds() {
        var schema = tool.inputSchema();
        assertNotNull(schema);
        assertTrue(schema.has("required"));
        assertTrue(schema.get("required").toString().contains("delaySeconds"));
    }
}
