package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
