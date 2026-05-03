package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRouterTest {

    @TempDir
    Path tempDir;

    @Test
    void sessionCreateCanonicalizesProjectPath() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);

        var params = mapper.createObjectNode();
        params.put("project", project.resolve("..").resolve("project").toString());
        var request = new JsonRpc.Request(JsonRpc.VERSION, "session.create", params, 1);

        Object response = router.route(request);
        assertThat(response).isInstanceOf(JsonRpc.Response.class);
        var result = (JsonRpc.Response) response;
        var node = (com.fasterxml.jackson.databind.JsonNode) result.result();
        assertThat(node.path("project").asText()).isEqualTo(project.toRealPath().toString());
    }

    @Test
    void sessionCreateRequiresProjectParam() {
        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);

        var params = mapper.createObjectNode();
        var request = new JsonRpc.Request(JsonRpc.VERSION, "session.create", params, 1);

        Object response = router.route(request);
        assertThat(response).isInstanceOf(JsonRpc.ErrorResponse.class);
        var err = (JsonRpc.ErrorResponse) response;
        assertThat(err.error().code()).isEqualTo(JsonRpc.INVALID_PARAMS);
        assertThat(err.error().message()).contains("project");
    }

    @Test
    void sessionCreateRejectsBlankProject() {
        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);

        var params = mapper.createObjectNode();
        params.put("project", "");
        var request = new JsonRpc.Request(JsonRpc.VERSION, "session.create", params, 1);

        Object response = router.route(request);
        assertThat(response).isInstanceOf(JsonRpc.ErrorResponse.class);
        var err = (JsonRpc.ErrorResponse) response;
        assertThat(err.error().code()).isEqualTo(JsonRpc.INVALID_PARAMS);
        assertThat(err.error().message()).contains("project");
    }

    @Test
    void healthStatusIncludesMcpSummaryFromSupplier() {
        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);
        var calls = new AtomicInteger();
        router.setMcpStatusSupplier(() -> {
            calls.incrementAndGet();
            var mcp = mapper.createObjectNode();
            mcp.put("configured", 3);
            mcp.put("connected", 2);
            mcp.put("failed", 1);
            mcp.put("tools", 7);
            return mcp;
        });

        var request = new JsonRpc.Request(JsonRpc.VERSION, "health.status", null, 1);
        Object response = router.route(request);

        assertThat(response).isInstanceOf(JsonRpc.Response.class);
        var result = (JsonRpc.Response) response;
        var node = (com.fasterxml.jackson.databind.JsonNode) result.result();
        assertThat(node.path("mcp").path("configured").asInt()).isEqualTo(3);
        assertThat(node.path("mcp").path("connected").asInt()).isEqualTo(2);
        assertThat(node.path("mcp").path("failed").asInt()).isEqualTo(1);
        assertThat(node.path("mcp").path("tools").asInt()).isEqualTo(7);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void healthStatusReportsDashboardInfo() {
        // Pins the wire shape that `aceclaw dashboard` (issue #446) reads to
        // discover the URL and decide what error to print. If a refactor renames
        // these JSON fields without updating the CLI, this test fails fast
        // instead of letting users run a CLI that silently can't find the URL.
        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);
        router.setDashboardInfo(new RequestRouter.DashboardInfo(
                true, "http://localhost:1234", true));

        var request = new JsonRpc.Request(JsonRpc.VERSION, "health.status", null, 1);
        Object response = router.route(request);

        assertThat(response).isInstanceOf(JsonRpc.Response.class);
        var result = (JsonRpc.Response) response;
        var node = (com.fasterxml.jackson.databind.JsonNode) result.result();
        assertThat(node.path("dashboard").path("enabled").asBoolean()).isTrue();
        assertThat(node.path("dashboard").path("bundled").asBoolean()).isTrue();
        assertThat(node.path("dashboard").path("url").asText()).isEqualTo("http://localhost:1234");
    }

    @Test
    void healthStatusOmitsDashboardUrlWhenDisabled() {
        // CLI distinguishes "WS bridge couldn't bind" from "no URL configured"
        // by checking enabled + url presence. Pin that the daemon emits an
        // empty URL when the bridge isn't running so the CLI doesn't end up
        // opening "http://" in the browser.
        var sessionManager = new SessionManager();
        var mapper = new ObjectMapper();
        var router = new RequestRouter(sessionManager, mapper);
        router.setDashboardInfo(new RequestRouter.DashboardInfo(false, "", true));

        var request = new JsonRpc.Request(JsonRpc.VERSION, "health.status", null, 1);
        Object response = router.route(request);

        var node = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) response).result();
        assertThat(node.path("dashboard").path("enabled").asBoolean()).isFalse();
        // url field is omitted when empty (not just empty-string) — CLI must
        // treat missing and empty equivalently.
        assertThat(node.path("dashboard").path("url").asText("")).isEmpty();
    }
}
