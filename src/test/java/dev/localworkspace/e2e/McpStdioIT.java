package dev.localworkspace.e2e;

import static dev.localworkspace.e2e.StdioClient.body;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class McpStdioIT {
  @TempDir Path temp;
  private static final Path JAR = Path.of("target/local-workspace-mcp-0.1.0.jar");

  private Path project() throws Exception {
    return Files.createDirectories(temp.resolve("中文 项目"));
  }

  private JsonNode ok(StdioClient c, String name, Map<String, Object> args) throws Exception {
    var result = c.callTool(name, args);
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    var envelope = body(result);
    assertThat(envelope.path("ok").asBoolean()).as(envelope.toString()).isTrue();
    return envelope.path("data");
  }

  private JsonNode create(StdioClient c, String title, String goal) throws Exception {
    return ok(
        c,
        "task_create",
        Map.of(
            "request",
            Map.of(
                "task",
                Map.of(
                    "title",
                    title,
                    "goal",
                    goal,
                    "constraints",
                    List.of("使用 JWT"),
                    "tags",
                    List.of("auth")),
                "nextSteps",
                List.of("实现接口"))));
  }

  private Map<String, Object> update(String id, String revision, String current, String next) {
    return Map.of(
        "request",
        Map.of(
            "taskId",
            id,
            "document",
            "STATUS",
            "expectedRevision",
            revision,
            "status",
            Map.of("state", "ACTIVE", "current", current, "nextSteps", List.of(next))));
  }

  @Test
  void agentBResumesAfterAgentAExits() throws Exception {
    Path project = project();
    String id, knowledgeId, revision;
    StdioClient a = StdioClient.start(JAR, project);
    try (a) {
      ok(a, "workspace_init", Map.of());
      var task = create(a, "认证模块", "实现认证模块");
      id = task.path("taskId").asText();
      create(a, "订单隔离任务", "订单专用目标");
      var updated =
          ok(
              a,
              "task_update",
              update(id, task.path("revision").asText(), "登录完成", "实现刷新令牌接口并补充过期测试"));
      var h =
          ok(
              a,
              "handoff_save",
              Map.of(
                  "taskId",
                  id,
                  "expectedRevision",
                  updated.path("revision").asText(),
                  "content",
                  Map.of(
                      "summary",
                      "登录已完成",
                      "decisions",
                      List.of(Map.of("decision", "使用 Redis", "reason", "令牌可撤销")))));
      revision = h.path("revision").asText();
      var k =
          ok(
              a,
              "knowledge_save",
              Map.of(
                  "request",
                  Map.of(
                      "input",
                      Map.of(
                          "title",
                          "认证约定",
                          "taskIds",
                          List.of(id),
                          "body",
                          "刷新令牌放入 HttpOnly Cookie"))));
      knowledgeId = k.path("id").asText();
      assertThat(a.nonProtocolStdout()).isEmpty();
    }
    assertThat(a.alive()).isFalse();
    try (var b = StdioClient.start(JAR, project)) {
      var tasks = ok(b, "task_list", Map.of("request", Map.of("query", "认证")));
      assertThat(tasks.path("total").asInt()).isEqualTo(1);
      assertThat(tasks.path("items").get(0).path("taskId").asText()).isEqualTo(id);
      var loaded =
          ok(
              b,
              "context_load",
              Map.of("request", Map.of("taskId", id, "knowledgeIds", List.of(knowledgeId))));
      assertThat(loaded.path("revision").asText()).isEqualTo(revision);
      assertThat(loaded.toString())
          .contains("实现刷新令牌接口并补充过期测试", "使用 JWT", "HttpOnly Cookie")
          .doesNotContain("订单专用目标");
      assertThat(loaded.path("authoritativeNextStepsDocument").asText()).isEqualTo("STATUS.md");
      assertThat(b.nonProtocolStdout()).isEmpty();
    }
    assertThat(Files.readString(project.resolve("AGENTS.md")))
        .contains("LocalWorkspace/AGENT_GUIDE.md");
  }

  @Test
  void onlyOneProcessWinsSameRevisionUpdate() throws Exception {
    Path project = project();
    try (var a = StdioClient.start(JAR, project);
        var b = StdioClient.start(JAR, project)) {
      ok(a, "workspace_init", Map.of("guidanceTarget", "none"));
      var task = create(a, "并发任务", "并发目标");
      String id = task.path("taskId").asText();
      String revision = task.path("revision").asText();
      assertThat(
              ok(b, "context_load", Map.of("request", Map.of("taskId", id)))
                  .path("revision")
                  .asText())
          .isEqualTo(revision);
      var start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(2);
      try {
        var first =
            pool.submit(
                () -> {
                  start.await();
                  return a.callTool("task_update", update(id, revision, "A 完成", "A 下一步"));
                });
        var second =
            pool.submit(
                () -> {
                  start.await();
                  return b.callTool("task_update", update(id, revision, "B 完成", "B 下一步"));
                });
        start.countDown();
        List<JsonNode> results =
            List.of(first.get(35, TimeUnit.SECONDS), second.get(35, TimeUnit.SECONDS));
        assertThat(results.stream().filter(r -> !r.path("isError").asBoolean()).count())
            .isEqualTo(1);
        assertThat(
                results.stream()
                    .map(
                        r -> {
                          try {
                            return body(r).path("error").path("code").asText();
                          } catch (Exception e) {
                            throw new RuntimeException(e);
                          }
                        })
                    .filter("REVISION_CONFLICT"::equals)
                    .count())
            .isEqualTo(1);
      } finally {
        pool.shutdownNow();
      }
      assertThat(a.nonProtocolStdout()).isEmpty();
      assertThat(b.nonProtocolStdout()).isEmpty();
    }
  }

  @Test
  void rejectsTraversalAndOversizedWriteWithoutChangingRevision() throws Exception {
    try (var c = StdioClient.start(JAR, project())) {
      ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      var task = create(c, "限额测试", "验证原文保留");
      String id = task.path("taskId").asText(), rev = task.path("revision").asText();
      var failure = c.callTool("task_update", update(id, rev, "x".repeat(8193), "下一步"));
      assertThat(failure.path("isError").asBoolean()).isTrue();
      assertThat(body(failure).path("error").path("code").asText()).isEqualTo("SUMMARY_REQUIRED");
      assertThat(
              ok(c, "context_load", Map.of("request", Map.of("taskId", id)))
                  .path("revision")
                  .asText())
          .isEqualTo(rev);
      var traversal = c.callTool("context_load", Map.of("request", Map.of("taskId", "../outside")));
      assertThat(traversal.path("isError").asBoolean()).isTrue();
      assertThat(body(traversal).path("error").path("code").asText())
          .isIn("INVALID_ARGUMENT", "PATH_NOT_ALLOWED");
      assertThat(c.nonProtocolStdout()).isEmpty();
    }
  }

  @Test
  void readsHistorySeparatelyAndAppliesItsBudget() throws Exception {
    try (var c = StdioClient.start(JAR, project())) {
      ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      var task = create(c, "历史测试", "旧目标" + "x".repeat(5000));
      String id = task.path("taskId").asText();
      var handoff =
          ok(
              c,
              "handoff_save",
              Map.of(
                  "taskId",
                  id,
                  "expectedRevision",
                  task.path("revision").asText(),
                  "content",
                  Map.of("summary", "交接摘要")));
      String cp = handoff.path("checkpointId").asText();
      assertThat(ok(c, "history_read", Map.of("request", Map.of("taskId", id))).path("checkpoints"))
          .hasSize(1);
      var tooSmall =
          c.callTool(
              "history_read",
              Map.of("request", Map.of("taskId", id, "checkpointId", cp, "maxChars", 4000)));
      assertThat(tooSmall.path("isError").asBoolean()).isTrue();
      assertThat(body(tooSmall).path("error").path("code").asText()).isEqualTo("BUDGET_EXCEEDED");
      assertThat(
              ok(
                      c,
                      "history_read",
                      Map.of(
                          "request",
                          Map.of(
                              "taskId",
                              id,
                              "checkpointId",
                              cp,
                              "documents",
                              List.of("TASK"),
                              "maxChars",
                              64000)))
                  .toString())
          .contains("旧目标");
      assertThat(c.nonProtocolStdout()).isEmpty();
    }
  }

  @Test
  void retriesInitializationWithoutChangingExistingRulesOrGuide() throws Exception {
    Path project = project();
    byte[] original = "\ufeff# 项目规则\r\n保留这一行\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("AGENTS.md"), original);
    Files.writeString(project.resolve("task.md"), "用户原有任务");
    try (var c = StdioClient.start(JAR, project)) {
      ok(c, "workspace_init", Map.of());
      Path guide = project.resolve("LocalWorkspace/AGENT_GUIDE.md");
      Files.writeString(guide, "用户补充的指南");
      byte[] rootAfter = Files.readAllBytes(project.resolve("AGENTS.md"));
      ok(c, "workspace_init", Map.of());
      assertThat(Files.readAllBytes(project.resolve("AGENTS.md"))).isEqualTo(rootAfter);
      assertThat(new String(rootAfter, StandardCharsets.UTF_8))
          .startsWith(new String(original, StandardCharsets.UTF_8));
      assertThat(Files.readString(guide)).isEqualTo("用户补充的指南");
      assertThat(Files.readString(project.resolve("task.md"))).isEqualTo("用户原有任务");
      assertThat(c.nonProtocolStdout()).isEmpty();
    }
  }

  @Test
  void drainsMoreThan64KiBStderrWithoutBlockingProtocol() throws Exception {
    try (var client = StdioClient.start(JAR, project(), "--logging.level.root=TRACE")) {
      ok(client, "workspace_status", Map.of());
      assertThat(client.stderrCharacters()).isGreaterThan(65536);
      assertThat(client.nonProtocolStdout()).isEmpty();
    }
  }
}
