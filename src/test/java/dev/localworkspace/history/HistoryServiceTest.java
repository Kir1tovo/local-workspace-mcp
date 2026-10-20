package dev.localworkspace.history;

import static dev.localworkspace.history.HistoryModels.*;
import static dev.localworkspace.task.TaskModels.*;
import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.NONE;
import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.support.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HistoryServiceTest {
  @TempDir Path root;

  private HistoryRequest listing(String id) {
    return new HistoryRequest(id, null, null, null, null, null);
  }

  @Test
  void archivesExactPreHandoffBytes() throws Exception {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    Map<DocumentKind, byte[]> before = new EnumMap<>(DocumentKind.class);
    for (var kind : List.of(DocumentKind.TASK, DocumentKind.STATUS, DocumentKind.HANDOFF))
      before.put(
          kind,
          Files.readAllBytes(
              f.paths
                  .task(t.taskId())
                  .resolve(dev.localworkspace.task.TaskRepository.filename(kind))));
    assertThatCode(
            () -> f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision()))
        .doesNotThrowAnyException();
    var cps = f.history.read(listing(t.taskId()));
    String id = cps.checkpoints().get(0).checkpointId();
    for (var entry : before.entrySet())
      assertThat(
              Files.readAllBytes(
                  f.paths
                      .checkpoint(t.taskId(), id)
                      .resolve(dev.localworkspace.task.TaskRepository.filename(entry.getKey()))))
          .isEqualTo(entry.getValue());
    assertThat(f.tasks.read(t.taskId()).revision()).isNotEqualTo(t.revision());
  }

  @Test
  void ordinaryUpdateCreatesNoCheckpoint() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    f.taskService.update(
        WorkspaceFixture.update(t, WorkspaceFixture.status(TaskState.ACTIVE, List.of("刷新令牌"))));
    assertThat(f.history.read(listing(t.taskId())).total()).isZero();
  }

  @Test
  void milestoneUpdateCreatesOneCheckpoint() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var u = WorkspaceFixture.update(t, WorkspaceFixture.status(TaskState.ACTIVE, List.of("刷新令牌")));
    f.taskService.update(
        new UpdateTaskRequest(
            u.taskId(), u.document(), u.expectedRevision(), null, u.status(), true));
    assertThat(f.history.read(listing(t.taskId())).total()).isEqualTo(1);
  }

  @Test
  void failedSnapshotLeavesCurrentDocumentsUntouched() {
    var f =
        new WorkspaceFixture(
            root,
            s ->
                new FailingDocumentStore(
                    s, p -> p.getFileName().toString().equals("checkpoint.json")),
            "LocalWorkspace");
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatThrownBy(
            () -> f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision()))
        .hasFieldOrPropertyWithValue("code", ErrorCode.IO_ERROR);
    assertThat(f.tasks.read(t.taskId()).revision()).isEqualTo(t.revision());
    assertThat(f.history.read(listing(t.taskId())).total()).isZero();
  }

  @Test
  void failedReplacementReportsCreatedCheckpoint() {
    AtomicReference<Path> target = new AtomicReference<>();
    var f =
        new WorkspaceFixture(
            root, s -> new FailingDocumentStore(s, p -> p.equals(target.get())), "LocalWorkspace");
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    target.set(f.paths.task(t.taskId()).resolve("handoff.md"));
    Throwable error =
        catchThrowable(
            () -> f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision()));
    assertThat(error).isInstanceOf(WorkspaceException.class);
    assertThat(((WorkspaceException) error).details).containsKey("checkpointId");
    assertThat(f.tasks.read(t.taskId()).revision()).isEqualTo(t.revision());
    assertThat(f.history.read(listing(t.taskId())).total()).isEqualTo(1);
  }

  @Test
  void checkpointsRemainDistinctWithFixedClock() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var a = f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision());
    var b = f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), a.revision());
    assertThat(a.checkpointId()).isNotEqualTo(b.checkpointId());
    assertThat(f.history.read(listing(t.taskId())).total()).isEqualTo(2);
  }

  @Test
  void readsSelectedHistoryAndDetectsTampering() throws Exception {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var h = f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision());
    var request =
        new HistoryRequest(
            t.taskId(), h.checkpointId(), List.of(DocumentKind.STATUS), null, null, null);
    assertThat(f.history.read(request).documents().keySet()).containsExactly(DocumentKind.STATUS);
    Files.writeString(
        f.paths.checkpoint(t.taskId(), h.checkpointId()).resolve("STATUS.md"), "changed");
    assertThatThrownBy(() -> f.history.read(request))
        .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
  }

  @Test
  void readsIntactHistoryWhenCurrentStateIsDamaged() throws Exception {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var task = f.taskService.create(WorkspaceFixture.task("历史恢复"));
    var saved = f.history.saveHandoff(task.taskId(), WorkspaceFixture.handoff(), task.revision());
    Path current = f.paths.task(task.taskId()).resolve("STATUS.md");
    Files.writeString(current, "损坏的状态");
    assertThat(f.history.read(listing(task.taskId())).total()).isEqualTo(1);
    var history =
        f.history.read(
            new HistoryRequest(
                task.taskId(),
                saved.checkpointId(),
                List.of(DocumentKind.STATUS),
                null,
                null,
                null));
    assertThat(history.documents().get(DocumentKind.STATUS).text()).contains("实现接口");
    assertThat(Files.readString(current)).isEqualTo("损坏的状态");
  }

  @Test
  void readsIntactHistoryWhenCurrentDocumentIsMissing() throws Exception {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    var task = f.taskService.create(WorkspaceFixture.task("历史恢复"));
    var saved = f.history.saveHandoff(task.taskId(), WorkspaceFixture.handoff(), task.revision());
    Files.delete(f.paths.task(task.taskId()).resolve("task.md"));
    assertThat(f.history.read(listing(task.taskId())).total()).isEqualTo(1);
    assertThat(
            f.history
                .read(
                    new HistoryRequest(
                        task.taskId(),
                        saved.checkpointId(),
                        List.of(DocumentKind.TASK),
                        null,
                        null,
                        null))
                .documents()
                .get(DocumentKind.TASK)
                .text())
        .contains("历史恢复");
    assertThatThrownBy(() -> f.history.read(listing("task-00000000-0000-0000-0000-000000000000")))
        .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
  }
}
