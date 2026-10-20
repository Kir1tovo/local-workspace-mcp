package dev.localworkspace.mcp;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.localworkspace.support.WorkspaceFixture;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRoutingContractTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  McpSyncClient connect(Path fixed, Path advertised) {
    var args =
        new ArrayList<>(
            List.of(
                "-Dfile.encoding=UTF-8",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                "dev.localworkspace.LocalWorkspaceApplication"));
    if (fixed != null) args.add("--local-workspace.project-root=" + fixed);
    var params =
        ServerParameters.builder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString())
            .args(args)
            .build();
    var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(json));
    var stderr = new StringBuilder();
    transport.setStdErrorHandler(
        s -> {
          synchronized (stderr) {
            if (stderr.length() < 64000) stderr.append(s).append('\n');
          }
        });
    var builder =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(20))
            .initializationTimeout(Duration.ofSeconds(15));
    if (advertised != null)
      builder
          .capabilities(ClientCapabilities.builder().roots(true).build())
          .roots(new Root(advertised.toUri().toString(), "project"));
    var client = builder.build();
    try {
      client.initialize();
      return client;
    } catch (RuntimeException e) {
      client.close();
      throw new AssertionError("MCP startup failed: " + stderr, e);
    }
  }

  JsonNode body(CallToolResult result) throws Exception {
    try {
      return json.readTree(((TextContent) result.content().get(0)).text());
    } catch (Exception e) {
      throw new AssertionError("Result boundary: " + json.writeValueAsString(result), e);
    }
  }

  CallToolResult call(McpSyncClient c, String name, Map<String, Object> args) {
    return c.callTool(new CallToolRequest(name, args));
  }

  @Test
  void autoStartupSchemasAndUniqueRootRouting() throws Exception {
    assertThat(java.nio.charset.Charset.defaultCharset())
        .as("SDK STDIO transport uses the client JVM default charset")
        .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    Path project = Files.createDirectory(temp.resolve("中文 项目"));
    try (var client = connect(null, project)) {
      var tools = client.listTools().tools();
      assertThat(tools).hasSize(10);
      for (var tool : tools) {
        var schema = json.valueToTree(tool.inputSchema());
        assertThat(schema.path("properties").has("projectRoot")).as(tool.name()).isTrue();
        assertThat(schema.path("properties").has("context")).isFalse();
        assertThat(schema.path("required").toString()).doesNotContain("projectRoot");
        if (tool.name().equals("task_update"))
          assertThat(schema.at("/properties/request/properties").has("expectedRevision")).isTrue();
        if (tool.name().equals("handoff_save"))
          assertThat(schema.path("properties").has("expectedRevision")).isTrue();
      }
      assertThat(Files.exists(project.resolve("LocalWorkspace"))).isFalse();
      var status = body(call(client, "workspace_status", Map.of()));
      assertThat(status.at("/data/resolution/source").asText()).isEqualTo("MCP_ROOTS");
      assertThat(status.at("/data/workspace/initialized").asBoolean()).isFalse();
      var init = body(call(client, "workspace_init", Map.of("guidanceTarget", "none")));
      assertThat(init.at("/data/resolution/mode").asText()).isEqualTo("AUTO");
      assertThat(init.at("/data/workspace/schemaVersion").asInt()).isEqualTo(1);
      assertThat(init.at("/data/workspace/projectRoot").asText())
          .isEqualTo(project.toRealPath().toString());
    }
  }

  @Test
  void missingRootsRecoverWithArgumentAndContextBudgetRemainsExact() throws Exception {
    try (var client = connect(null, null)) {
      var missing = call(client, "workspace_status", Map.of());
      assertThat(missing.isError()).isTrue();
      assertThat(body(missing).at("/error/code").asText()).isEqualTo("PROJECT_REQUIRED");
      assertThat(Files.exists(temp.resolve("LocalWorkspace"))).isFalse();
      var init =
          body(
              call(
                  client,
                  "workspace_init",
                  Map.of("projectRoot", temp.toString(), "guidanceTarget", "none")));
      assertThat(init.at("/data/resolution/source").asText()).isEqualTo("AGENT_ARGUMENT");
      var created =
          body(
              call(
                  client,
                  "task_create",
                  Map.of(
                      "projectRoot",
                      temp.toString(),
                      "request",
                      json.convertValue(WorkspaceFixture.task("预算验证"), Map.class))));
      String id = created.at("/data/taskId").asText();
      var result =
          call(
              client,
              "context_load",
              Map.of(
                  "projectRoot",
                  temp.toString(),
                  "request",
                  Map.of("taskId", id, "maxChars", 24000)));
      String text = ((TextContent) result.content().get(0)).text();
      var context = json.readTree(text);
      assertThat(result.isError()).isFalse();
      assertThat(context.at("/data/totalChars").asInt())
          .isEqualTo(text.codePointCount(0, text.length()));
      assertThat(context.path("data").has("resolution")).isFalse();
      Path guide = temp.resolve("LocalWorkspace/AGENT_GUIDE.md");
      byte[] edited = "# 用户指南\r\n保留修改".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(guide, edited);
      call(
          client,
          "workspace_init",
          Map.of("projectRoot", temp.toString(), "guidanceTarget", "both"));
      assertThat(Files.readAllBytes(guide)).isEqualTo(edited);
    }
  }

  @Test
  void fixedModeKeepsConfiguredProjectAndRejectsDifferentArgument() throws Exception {
    Path a = Files.createDirectory(temp.resolve("A"));
    Path b = Files.createDirectory(temp.resolve("B"));
    try (var client = connect(a, b)) {
      var status = body(call(client, "workspace_status", Map.of()));
      assertThat(status.at("/data/resolution/source").asText()).isEqualTo("CONFIG");
      assertThat(status.at("/data/workspace/projectRoot").asText())
          .isEqualTo(a.toRealPath().toString());
      var rejected = call(client, "workspace_init", Map.of("projectRoot", b.toString()));
      assertThat(rejected.isError()).isTrue();
      assertThat(body(rejected).at("/error/code").asText()).isEqualTo("PATH_NOT_ALLOWED");
      assertThat(Files.exists(b.resolve("LocalWorkspace"))).isFalse();
    }
  }
}
