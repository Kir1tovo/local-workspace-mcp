package dev.localworkspace.workspace;

import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.*;
import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {
  @TempDir Path root;

  @Test
  void initializationIsIdempotent() throws Exception {
    var f = new WorkspaceFixture(root);
    assertThatCode(() -> f.workspace.initialize(AGENTS)).doesNotThrowAnyException();
    var first = f.workspace.initialize(AGENTS);
    var second = f.workspace.initialize(AGENTS);
    assertThat(first.workspace().workspaceId()).isEqualTo(second.workspace().workspaceId());
    assertThat(Files.readString(root.resolve("AGENTS.md")))
        .containsOnlyOnce("<!-- LocalWorkspace:begin -->");
    assertThat(f.store.list(f.paths.workspaceRoot().resolve("tasks"))).isEmpty();
    assertThat(f.store.list(f.paths.workspaceRoot().resolve("knowledge"))).isEmpty();
  }

  @Test
  void preservesExistingGuideAndRootTask() throws Exception {
    Files.writeString(root.resolve("task.md"), "原有任务");
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    Files.writeString(f.paths.workspaceRoot().resolve("AGENT_GUIDE.md"), "我的流程");
    f.workspace.initialize(NONE);
    assertThat(Files.readString(root.resolve("task.md"))).isEqualTo("原有任务");
    assertThat(Files.readString(f.paths.workspaceRoot().resolve("AGENT_GUIDE.md")))
        .isEqualTo("我的流程");
    assertThat(Files.exists(root.resolve("AGENTS.md"))).isFalse();
  }

  @Test
  void preservesBomAndCrLfOutsideManagedBlock() throws Exception {
    String prefix = "\ufeff# 原有规则\r\nkeep\r\n";
    String suffix = "\r\nfooter\r\n";
    Files.write(
        root.resolve("AGENTS.md"),
        Utf8.encode(
            prefix
                + "<!-- LocalWorkspace:begin -->\r\n旧内容\r\n<!-- LocalWorkspace:end -->"
                + suffix));
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(AGENTS);
    byte[] result = Files.readAllBytes(root.resolve("AGENTS.md"));
    assertThat(result).startsWith(Utf8.encode(prefix)).endsWith(Utf8.encode(suffix));
    assertThat(Utf8.decodeStrict(result).replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void usesExistingAgentsOverride() throws Exception {
    Files.writeString(root.resolve("AGENTS.md"), "base");
    Files.writeString(root.resolve("AGENTS.override.md"), "override\n");
    new WorkspaceFixture(root).workspace.initialize(BOTH);
    assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("base");
    assertThat(Files.readString(root.resolve("AGENTS.override.md")))
        .contains("<!-- LocalWorkspace:begin -->");
    assertThat(Files.exists(root.resolve("CLAUDE.md"))).isTrue();
  }

  @Test
  void rejectsMalformedMarkersAndInvalidUtf8() throws Exception {
    Path entry = root.resolve("AGENTS.md");
    Files.writeString(entry, "keep\n<!-- LocalWorkspace:begin -->\n");
    byte[] original = Files.readAllBytes(entry);
    var f = new WorkspaceFixture(root);
    assertThatThrownBy(() -> f.workspace.initialize(AGENTS))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
    assertThat(Files.readAllBytes(entry)).isEqualTo(original);
    Files.write(entry, new byte[] {(byte) 0xff});
    assertThatThrownBy(() -> f.workspace.initialize(AGENTS))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
    Files.writeString(
        entry,
        "<!-- LocalWorkspace:begin --><!-- LocalWorkspace:end --><!-- LocalWorkspace:begin --><!--"
            + " LocalWorkspace:end -->");
    assertThatThrownBy(() -> f.workspace.initialize(AGENTS))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
  }

  @Test
  void refusesForeignNonemptyWorkspace() throws Exception {
    Files.createDirectory(root.resolve("LocalWorkspace"));
    Files.writeString(root.resolve("LocalWorkspace/mine.md"), "keep");
    assertThatThrownBy(() -> new WorkspaceFixture(root).workspace.initialize(AGENTS))
        .hasFieldOrPropertyWithValue("code", ErrorCode.WORKSPACE_CONFLICT);
    var f = new WorkspaceFixture(root, s -> s, "OtherWorkspace");
    f.workspace.initialize(CLAUDE);
    assertThat(Files.readString(root.resolve("CLAUDE.md")))
        .contains("OtherWorkspace/AGENT_GUIDE.md");
    assertThat(Files.readString(root.resolve("LocalWorkspace/mine.md"))).isEqualTo("keep");
  }

  @Test
  void retriesAfterPartialInitialization() throws Exception {
    var f =
        new WorkspaceFixture(
            root,
            s -> new FailingDocumentStore(s, p -> p.getFileName().toString().equals("AGENTS.md")),
            "LocalWorkspace");
    assertThatThrownBy(() -> f.workspace.initialize(AGENTS))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
    assertThat(f.workspace.status().workspace().initialized()).isTrue();
    assertThat(f.workspace.status().guidanceInstalled().get("AGENTS.md")).isFalse();
    f.workspace.initialize(AGENTS);
    assertThat(f.workspace.status().guidanceInstalled().get("AGENTS.md")).isTrue();
  }

  @Test
  void retriesAfterGuideCreationFailure() {
    var f =
        new WorkspaceFixture(
            root,
            s ->
                new FailingDocumentStore(
                    s, p -> p.getFileName().toString().equals("AGENT_GUIDE.md")),
            "LocalWorkspace");
    assertThatThrownBy(() -> f.workspace.initialize(NONE))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
    assertThat(f.workspace.status().workspace().initialized()).isFalse();
    f.workspace.initialize(NONE);
    assertThat(f.workspace.status().workspace().initialized()).isTrue();
  }

  @Test
  void oversizedManagedEntryPreservesExistingRootFile() throws Exception {
    Path entry = root.resolve("AGENTS.md");
    byte[] before = Utf8.encode("x".repeat(262000));
    Files.write(entry, before);
    var f = new WorkspaceFixture(root);
    Throwable failure = catchThrowable(() -> f.workspace.initialize(AGENTS));
    assertThat(Files.readAllBytes(entry)).isEqualTo(before);
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
    assertThat(((WorkspaceException) failure).details)
        .containsEntry("causeCode", "SUMMARY_REQUIRED");
  }
}
