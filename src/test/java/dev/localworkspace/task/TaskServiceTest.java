package dev.localworkspace.task;

import static dev.localworkspace.task.TaskModels.*;
import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.NONE;
import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TaskServiceTest {
  @TempDir Path root;

  private WorkspaceFixture fixture() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    return f;
  }

  @Test
  void createsThreeDocumentsForOneTask() {
    var f = fixture();
    assertThatCode(() -> f.taskService.create(WorkspaceFixture.task("登录")))
        .doesNotThrowAnyException();
    var t = f.taskService.create(WorkspaceFixture.task("支付"));
    assertThat(t.state()).isEqualTo(TaskState.ACTIVE);
    assertThat(t.documents().keySet())
        .containsExactlyInAnyOrder(DocumentKind.TASK, DocumentKind.STATUS, DocumentKind.HANDOFF);
    assertThat(t.documents().get(DocumentKind.HANDOFF).body()).contains("尚无交接记录");
  }

  @Test
  void doesNotMixTwoTasks() {
    var f = fixture();
    var a = f.taskService.create(WorkspaceFixture.task("登录"));
    var b = f.taskService.create(WorkspaceFixture.task("支付"));
    f.taskService.update(
        WorkspaceFixture.update(a, WorkspaceFixture.status(TaskState.ACTIVE, List.of("刷新令牌"))));
    assertThat(f.tasks.read(b.taskId()).revision()).isEqualTo(b.revision());
    assertThat(f.tasks.read(a.taskId()).documents().get(DocumentKind.STATUS).body())
        .contains("刷新令牌")
        .doesNotContain("支付");
  }

  @Test
  void rejectsStaleTaskRevisionAfterAnyDocumentChanges() throws Exception {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    Files.writeString(
        f.paths.task(t.taskId()).resolve("handoff.md"), "\n人工补充", StandardOpenOption.APPEND);
    assertThatThrownBy(
            () ->
                f.taskService.update(
                    WorkspaceFixture.update(
                        t, WorkspaceFixture.status(TaskState.ACTIVE, List.of("下一步")))))
        .hasFieldOrPropertyWithValue("code", ErrorCode.REVISION_CONFLICT);
  }

  @Test
  void requiresNextStepsForActiveAndBlocked() {
    var f = fixture();
    assertThatThrownBy(
            () ->
                f.taskService.create(
                    new CreateTaskRequest(WorkspaceFixture.task("登录").task(), List.of())))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatThrownBy(
            () ->
                f.taskService.update(
                    WorkspaceFixture.update(
                        t, WorkspaceFixture.status(TaskState.ACTIVE, List.of()))))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
  }

  @Test
  void requiresBlockerForBlocked() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatThrownBy(
            () ->
                f.taskService.update(
                    WorkspaceFixture.update(
                        t, WorkspaceFixture.status(TaskState.BLOCKED, List.of("等待确认")))))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
  }

  @Test
  void hidesArchivedByDefault() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    f.taskService.update(
        WorkspaceFixture.update(t, WorkspaceFixture.status(TaskState.ARCHIVED, List.of())));
    assertThat(f.taskService.list(new TaskQuery(null, null, null, null)).items()).isEmpty();
    assertThat(f.taskService.list(new TaskQuery(TaskState.ARCHIVED, null, 0, 20)).total())
        .isEqualTo(1);
  }

  @Test
  void rejectsDamagedMetadataWithoutOverwriting() throws Exception {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    Path file = f.paths.task(t.taskId()).resolve("STATUS.md");
    Files.writeString(file, "damaged");
    assertThatThrownBy(
            () ->
                f.taskService.update(
                    WorkspaceFixture.update(
                        t, WorkspaceFixture.status(TaskState.ACTIVE, List.of("下一步")))))
        .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
    assertThat(Files.readString(file)).isEqualTo("damaged");
  }

  @Test
  void validatesCompleteReplacementsAndPagination() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatThrownBy(
            () ->
                f.taskService.update(
                    new UpdateTaskRequest(
                        t.taskId(),
                        DocumentKind.STATUS,
                        t.revision(),
                        WorkspaceFixture.task("错").task(),
                        WorkspaceFixture.status(TaskState.ACTIVE, List.of("下一步")),
                        false)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    assertThatThrownBy(() -> f.taskService.list(new TaskQuery(null, null, -1, 20)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
  }

  private WorkspaceFixture largeLimits() {
    var f =
        new WorkspaceFixture(
            root,
            s -> s,
            "LocalWorkspace",
            new dev.localworkspace.config.WorkspaceProperties.Limits(300000, 8192, 16384, 24000));
    f.workspace.initialize(NONE);
    return f;
  }

  private TaskContent oversizedTask() {
    return new TaskContent("超限", "x".repeat(270000), null, null, null, null, null);
  }

  @Test
  void rejectsByteOversizedTaskBeforePublishingDirectory() {
    var f = largeLimits();
    Throwable failure =
        catchThrowable(
            () -> f.taskService.create(new CreateTaskRequest(oversizedTask(), List.of("下一步"))));
    assertThat(f.store.list(f.paths.workspaceRoot().resolve("tasks"))).isEmpty();
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
  }

  @Test
  void rejectsByteOversizedUpdateBeforeCheckpointOrReplacement() throws Exception {
    var f = largeLimits();
    var task = f.taskService.create(WorkspaceFixture.task("原文"));
    Path file = f.paths.task(task.taskId()).resolve("task.md");
    byte[] before = Files.readAllBytes(file);
    Throwable failure =
        catchThrowable(
            () ->
                f.taskService.update(
                    new UpdateTaskRequest(
                        task.taskId(),
                        DocumentKind.TASK,
                        task.revision(),
                        oversizedTask(),
                        null,
                        true)));
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThat(f.tasks.read(task.taskId()).revision()).isEqualTo(task.revision());
    assertThat(
            f.history
                .read(
                    new dev.localworkspace.history.HistoryModels.HistoryRequest(
                        task.taskId(), null, null, null, null, null))
                .total())
        .isZero();
  }

  @Test
  void rejectsComplexTaskMetadataBeforePublishing() {
    var f = largeLimits();
    var input =
        new TaskContent("标签", "目标", null, null, null, null, Collections.nCopies(4100, "tag"));
    Throwable failure =
        catchThrowable(() -> f.taskService.create(new CreateTaskRequest(input, List.of("下一步"))));
    assertThat(f.store.list(f.paths.workspaceRoot().resolve("tasks"))).isEmpty();
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
  }
}
