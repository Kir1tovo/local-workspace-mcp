package dev.localworkspace.mcp;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.localworkspace.config.WorkspaceProperties;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ToolContractTest {
  @TempDir static Path root;
  static McpSyncClient client;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final String CP =
      System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

  @BeforeAll
  static void connect() {
    client = createClient(root);
  }

  static McpSyncClient createClient(Path fixedRoot) {
    var params =
        ServerParameters.builder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString())
            .args(
                "-Dfile.encoding=UTF-8",
                "-cp",
                CP,
                "dev.localworkspace.LocalWorkspaceApplication",
                fixedRoot == null
                    ? "--spring.application.name=auto-test"
                    : "--local-workspace.project-root=" + fixedRoot)
            .build();
    var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(JSON));
    transport.setStdErrorHandler(s -> {});
    var connected =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(15))
            .initializationTimeout(Duration.ofSeconds(15))
            .build();
    connected.initialize();
    return connected;
  }

  @AfterAll
  static void close() {
    if (client != null) client.closeGracefully();
  }

  @Test
  void listsExactlyTenTools() {
    assertThat(client.listTools().tools())
        .extracting(Tool::name)
        .containsExactlyInAnyOrder(
            "workspace_init",
            "workspace_status",
            "task_create",
            "task_list",
            "context_load",
            "task_update",
            "handoff_save",
            "history_read",
            "knowledge_save",
            "knowledge_search");
  }

  @Test
  void schemasContainTaskIdAndExpectedRevision() {
    var tool =
        client.listTools().tools().stream()
            .filter(t -> t.name().equals("task_update"))
            .findFirst()
            .orElseThrow();
    var schema = JSON.valueToTree(tool.inputSchema());
    assertThat(schema.toString()).contains("taskId", "expectedRevision", "status", "nextSteps");
  }

  @Test
  void toolFailuresSetIsError() throws Exception {
    var result = client.callTool(new CallToolRequest("task_list", Map.of("request", Map.of())));
    assertThat(result.isError()).isTrue();
    var body = JSON.readTree(((TextContent) result.content().get(0)).text());
    assertThat(body.path("error").path("code").asText()).isEqualTo("NOT_INITIALIZED");
  }

  @Test
  void malformedArgumentsReturnReadableError() {
    var result = client.callTool(new CallToolRequest("task_update", Map.of("request", "bad-type")));
    assertThat(result.isError()).isTrue();
    assertThat(result.content()).isNotEmpty();
    assertThat(((TextContent) result.content().get(0)).text()).isNotBlank();
  }

  @Test
  void missingProjectRootStartsAutoWithoutCreatingWorkspace() throws Exception {
    try (var auto = createClient(null)) {
      assertThat(auto.listTools().tools()).hasSize(10);
      var result = auto.callTool(new CallToolRequest("workspace_status", Map.of()));
      assertThat(result.isError()).isTrue();
      assertThat(
              JSON.readTree(((TextContent) result.content().get(0)).text())
                  .at("/error/code")
                  .asText())
          .isEqualTo("PROJECT_REQUIRED");
      assertThat(Files.exists(root.resolve("LocalWorkspace"))).isFalse();
    }
  }

  @Test
  void invalidFixedProjectRootFailsStartupWithoutCreatingWorkspace() throws Exception {
    Path log = root.resolve("missing-root.log");
    Process p =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp",
                CP,
                "dev.localworkspace.LocalWorkspaceApplication",
                "--local-workspace.project-root=" + root.resolve("missing-project"))
            .redirectOutput(log.toFile())
            .redirectErrorStream(true)
            .start();
    try {
      assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
      assertThat(p.exitValue()).isNotZero();
      assertThat(Files.exists(root.resolve("LocalWorkspace"))).isFalse();
      assertThat(Files.readString(log)).contains("project-root");
    } finally {
      p.destroyForcibly();
    }
  }

  @Test
  void partialLimitOverrideKeepsOtherDefaults() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "local-workspace.project-root",
                root.toString(),
                "local-workspace.limits.document-chars",
                "9000"));
    var props =
        new Binder(source).bind("local-workspace", Bindable.of(WorkspaceProperties.class)).get();
    assertThat(props.limits().documentChars()).isEqualTo(9000);
    assertThat(props.limits().guideChars()).isEqualTo(8192);
    assertThat(props.limits().knowledgeChars()).isEqualTo(16384);
    assertThat(props.limits().contextChars()).isEqualTo(24000);
  }
}
