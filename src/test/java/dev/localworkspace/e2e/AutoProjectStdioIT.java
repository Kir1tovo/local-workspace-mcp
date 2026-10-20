package dev.localworkspace.e2e;

import static dev.localworkspace.e2e.StdioClient.body;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoProjectStdioIT {
  @Test
  void rootsChangeNotificationsNeverQueryOrDisconnectFixedOrAuto() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    for (boolean fixed : List.of(false, true)) {
      String[] arguments =
          fixed ? new String[] {"--local-workspace.project-root=" + a} : new String[0];
      try (var c = StdioClient.startAuto(JAR, directory, true, List.of(a), arguments)) {
        for (var reply :
            List.of(
                new StdioClient.RootsReply(List.of(), null, -32603, false),
                new StdioClient.RootsReply(List.of(a.toUri().toString()), "repeated", null, false),
                new StdioClient.RootsReply(List.of(), null, null, true))) {
          c.setRootsReply(reply);
          c.notification("notifications/roots/list_changed", Map.of());
          c.request("ping", Map.of());
          assertThat(c.rootsRequestCount()).isZero();
          assertThat(c.alive()).isTrue();
        }
        c.setRoots(List.of(b));
        var status = ok(c, "workspace_status", Map.of());
        assertThat(status.at("/workspace/projectRoot").asText())
            .isEqualTo((fixed ? a : b).toRealPath().toString());
        assertThat(c.rootsRequestCount()).isEqualTo(fixed ? 0 : 1);
        untouched(directory, a, b);
      }
    }
  }

  @TempDir Path temp;
  static final Path JAR = Path.of("target/local-workspace-mcp-0.1.0.jar");

  Path project(String name) throws Exception {
    return Files.createDirectories(temp.resolve(name));
  }

  Path wrong() throws Exception {
    return project("错误 启动目录");
  }

  JsonNode ok(StdioClient c, String tool, Map<String, Object> args) throws Exception {
    var result = c.callTool(tool, args);
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    var envelope = body(result);
    assertThat(envelope.path("ok").asBoolean()).as(envelope.toString()).isTrue();
    assertThat(c.nonProtocolStdout()).isEmpty();
    return envelope.path("data");
  }

  void error(StdioClient c, String tool, Map<String, Object> args, String code) throws Exception {
    var result = c.callTool(tool, args);
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
    assertThat(body(result).at("/error/code").asText()).isEqualTo(code);
    assertThat(c.nonProtocolStdout()).isEmpty();
  }

  void untouched(Path... paths) {
    for (Path path : paths) {
      assertThat(Files.exists(path.resolve("LocalWorkspace"))).as(path.toString()).isFalse();
      assertThat(Files.exists(path.resolve("AGENTS.md"))).isFalse();
    }
  }

  Map<String, Object> task(String title) {
    return Map.of(
        "task",
        Map.of("title", title, "goal", "实现" + title, "constraints", List.of("使用 JWT")),
        "nextSteps",
        List.of("实现接口"));
  }

  @Test
  void jarAutoModeListsToolsWithoutWrites() throws Exception {
    Path processDirectory = wrong();
    StdioClient c = StdioClient.startAuto(JAR, processDirectory, false, List.of());
    try (c) {
      assertThat(c.request("tools/list", Map.of()).path("tools")).hasSize(10);
      assertThat(c.rootsRequestCount()).isZero();
      error(c, "workspace_init", Map.of(), "PROJECT_REQUIRED");
      untouched(processDirectory);
    }
    assertThat(c.alive()).isFalse();
  }

  @Test
  void uniqueRootInitializesWithoutArgument() throws Exception {
    Path directory = wrong(), project = project("中文 空格%项目");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(project))) {
      var init = ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      assertThat(init.at("/resolution/source").asText()).isEqualTo("MCP_ROOTS");
      assertThat(init.at("/resolution/mode").asText()).isEqualTo("AUTO");
      assertThat(init.at("/workspace/projectRoot").asText())
          .isEqualTo(project.toRealPath().toString());
      assertThat(c.rootsRequestCount()).isEqualTo(1);
      assertThat(Files.exists(project.resolve("LocalWorkspace/workspace.json"))).isTrue();
      untouched(directory);
    }
  }

  @Test
  void unsupportedAndEmptyRootsAcceptAgentArgument() throws Exception {
    Path directory = wrong();
    for (boolean supported : List.of(false, true)) {
      Path project = project("项目-" + supported);
      try (var c = StdioClient.startAuto(JAR, directory, supported, List.of())) {
        error(c, "workspace_status", Map.of(), "PROJECT_REQUIRED");
        untouched(directory, project);
        var init =
            ok(
                c,
                "workspace_init",
                Map.of("guidanceTarget", "none", "projectRoot", project.toString()));
        assertThat(init.at("/resolution/source").asText()).isEqualTo("AGENT_ARGUMENT");
        assertThat(init.at("/workspace/initialized").asBoolean()).isTrue();
        assertThat(c.rootsRequestCount()).isEqualTo(supported ? 2 : 0);
        untouched(directory);
      }
    }
  }

  @Test
  void multipleRootsRequireSelectionWithoutWrites() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(b, a))) {
      var result = c.callTool("workspace_init", Map.of());
      assertThat(result.path("isError").asBoolean()).isTrue();
      var envelope = body(result);
      assertThat(envelope.at("/error/code").asText()).isEqualTo("PROJECT_AMBIGUOUS");
      assertThat(envelope.at("/error/details/candidates"))
          .extracting(JsonNode::asText)
          .containsExactly(a.toRealPath().toString(), b.toRealPath().toString());
      untouched(directory, a, b);
      ok(c, "workspace_init", Map.of("guidanceTarget", "none", "projectRoot", a.toString()));
      untouched(directory, b);
    }
  }

  @Test
  void rootsChangeRoutesNextCallToNewProject() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(a))) {
      assertThat(ok(c, "workspace_status", Map.of()).at("/workspace/projectRoot").asText())
          .isEqualTo(a.toRealPath().toString());
      c.setRoots(List.of(b));
      assertThat(
              ok(c, "workspace_init", Map.of("guidanceTarget", "none"))
                  .at("/workspace/projectRoot")
                  .asText())
          .isEqualTo(b.toRealPath().toString());
      assertThat(c.rootsRequestCount()).isEqualTo(2);
      untouched(directory, a);
      assertThat(Files.exists(b.resolve("LocalWorkspace"))).isTrue();
    }
  }

  @Test
  void parallelCallsToTwoRootsStayIsolated() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(a, b))) {
      for (Path p : List.of(a, b))
        ok(c, "workspace_init", Map.of("guidanceTarget", "none", "projectRoot", p.toString()));
      var pool = Executors.newFixedThreadPool(2);
      var start = new CountDownLatch(1);
      try {
        var fa =
            pool.submit(
                () -> {
                  start.await();
                  return ok(
                      c,
                      "task_create",
                      Map.of("projectRoot", a.toString(), "request", task("A独有")));
                });
        var fb =
            pool.submit(
                () -> {
                  start.await();
                  return ok(
                      c,
                      "task_create",
                      Map.of("projectRoot", b.toString(), "request", task("B独有")));
                });
        start.countDown();
        String aid = fa.get(30, TimeUnit.SECONDS).path("taskId").asText();
        String bid = fb.get(30, TimeUnit.SECONDS).path("taskId").asText();
        assertThat(ok(c, "task_list", Map.of("projectRoot", a.toString())).path("items"))
            .extracting(n -> n.path("title").asText())
            .containsExactly("A独有");
        assertThat(ok(c, "task_list", Map.of("projectRoot", b.toString())).path("items"))
            .extracting(n -> n.path("title").asText())
            .containsExactly("B独有");
        error(
            c,
            "context_load",
            Map.of("projectRoot", a.toString(), "request", Map.of("taskId", bid)),
            "NOT_FOUND");
        error(
            c,
            "context_load",
            Map.of("projectRoot", b.toString(), "request", Map.of("taskId", aid)),
            "NOT_FOUND");
      } finally {
        pool.shutdownNow();
      }
      untouched(directory);
    }
  }

  @Test
  void rootsFailureAndIncompleteListNeverUseArgumentOrPreviousProject() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(a))) {
      ok(c, "workspace_status", Map.of());
      for (var reply :
          List.of(
              new StdioClient.RootsReply(List.of(), null, -32603, false),
              new StdioClient.RootsReply(List.of(a.toUri().toString()), "next-page", null, false),
              new StdioClient.RootsReply(
                  List.of(a.toUri().toString(), "https://example.com"), null, null, false))) {
        c.setRootsReply(reply);
        int before = c.rootsRequestCount();
        error(c, "workspace_init", Map.of("projectRoot", b.toString()), "ROOTS_UNAVAILABLE");
        assertThat(c.rootsRequestCount()).isEqualTo(before + 1);
        untouched(directory, a, b);
      }
      c.setRoots(List.of(b));
      ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      untouched(directory, a);
    }
  }

  @Test
  void rootsTimeoutThenRecovers() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(a))) {
      ok(c, "workspace_status", Map.of());
      c.setRootsReply(new StdioClient.RootsReply(List.of(), null, null, true));
      long before = System.nanoTime();
      error(c, "workspace_init", Map.of("projectRoot", a.toString()), "ROOTS_UNAVAILABLE");
      assertThat(Duration.ofNanos(System.nanoTime() - before))
          .isBetween(Duration.ofSeconds(9), Duration.ofSeconds(20));
      untouched(directory, a, b);
      c.setRoots(List.of(b));
      ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      untouched(directory, a);
      assertThat(Files.exists(b.resolve("LocalWorkspace"))).isTrue();
    }
  }

  @Test
  void agentBResumesAutoWorkspace() throws Exception {
    Path directory = wrong(), project = project("接续 项目");
    String id, knowledgeId;
    StdioClient a = StdioClient.startAuto(JAR, directory, true, List.of(project));
    try (a) {
      ok(a, "workspace_init", Map.of("guidanceTarget", "none"));
      var created = ok(a, "task_create", Map.of("request", task("认证模块")));
      id = created.path("taskId").asText();
      var updated =
          ok(
              a,
              "task_update",
              Map.of(
                  "request",
                  Map.of(
                      "taskId",
                      id,
                      "document",
                      "STATUS",
                      "expectedRevision",
                      created.path("revision").asText(),
                      "status",
                      Map.of(
                          "state", "ACTIVE", "current", "登录已完成", "nextSteps", List.of("实现刷新令牌")))));
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
                  "继续刷新令牌",
                  "decisions",
                  List.of(Map.of("decision", "使用 Redis", "reason", "支持令牌撤销")))));
      knowledgeId =
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
                              "使用 HttpOnly Cookie"))))
              .path("id")
              .asText();
    }
    assertThat(a.alive()).isFalse();
    try (var b = StdioClient.startAuto(JAR, directory, true, List.of(project))) {
      assertThat(ok(b, "task_list", Map.of()).path("total").asInt()).isEqualTo(1);
      assertThat(
              ok(
                      b,
                      "context_load",
                      Map.of("request", Map.of("taskId", id, "knowledgeIds", List.of(knowledgeId))))
                  .toString())
          .contains("实现刷新令牌", "使用 Redis", "HttpOnly Cookie");
      assertThat(
              ok(b, "history_read", Map.of("request", Map.of("taskId", id))).path("total").asInt())
          .isEqualTo(1);
      untouched(directory);
    }
  }

  @Test
  void autoModePreservesEditedGuide() throws Exception {
    Path directory = wrong(), project = project("保留指南");
    byte[] prefix = "\ufeff# 原有规则\r\n保留\r\n".getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("AGENTS.md"), prefix);
    Files.writeString(project.resolve("task.md"), "用户任务");
    try (var c = StdioClient.startAuto(JAR, directory, true, List.of(project))) {
      ok(c, "workspace_init", Map.of());
      Path guide = project.resolve("LocalWorkspace/AGENT_GUIDE.md");
      byte[] edited = "# 自定义指南\r\n用户修改".getBytes(StandardCharsets.UTF_8);
      Files.write(guide, edited);
      byte[] entry = Files.readAllBytes(project.resolve("AGENTS.md"));
      ok(c, "workspace_init", Map.of());
      assertThat(Files.readAllBytes(guide)).isEqualTo(edited);
      assertThat(Files.readAllBytes(project.resolve("AGENTS.md"))).isEqualTo(entry);
      assertThat(new String(entry, StandardCharsets.UTF_8))
          .startsWith(new String(prefix, StandardCharsets.UTF_8))
          .contains("projectRoot");
      assertThat(Files.readString(project.resolve("task.md"))).isEqualTo("用户任务");
      untouched(directory);
    }
  }

  @Test
  void fixedModeSkipsAdvertisedRoots() throws Exception {
    Path directory = wrong(), a = project("A"), b = project("B");
    try (var c =
        StdioClient.startAuto(
            JAR, directory, true, List.of(b), "--local-workspace.project-root=" + a)) {
      var init = ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
      assertThat(init.at("/resolution/source").asText()).isEqualTo("CONFIG");
      assertThat(init.at("/workspace/projectRoot").asText()).isEqualTo(a.toRealPath().toString());
      assertThat(c.rootsRequestCount()).isZero();
      untouched(directory, b);
    }
  }
}
