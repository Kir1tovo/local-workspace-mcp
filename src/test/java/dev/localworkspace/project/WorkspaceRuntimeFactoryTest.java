package dev.localworkspace.project;

import static dev.localworkspace.support.WorkspaceFixture.*;
import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.NONE;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.localworkspace.common.*;
import dev.localworkspace.config.WorkspaceProperties;
import dev.localworkspace.history.HistoryModels.HistoryRequest;
import dev.localworkspace.knowledge.KnowledgeModels.*;
import dev.localworkspace.markdown.MarkdownCodec;
import dev.localworkspace.storage.ProjectLock;
import dev.localworkspace.task.TaskModels.TaskState;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRuntimeFactoryTest {
  @TempDir Path temp;

  WorkspaceRuntimeFactory factory(String name) {
    var json = new ObjectMapper().findAndRegisterModules();
    return new WorkspaceRuntimeFactory(
        new WorkspaceProperties(temp, name, null),
        json,
        Clock.systemUTC(),
        new MarkdownCodec(),
        new ResponseEncoder(json));
  }

  ProjectResolution auto(Path path) throws Exception {
    return new ProjectResolution(
        path.toRealPath(), ProjectResolution.Mode.AUTO, ProjectResolution.Source.MCP_ROOTS);
  }

  @Test
  void creatingRuntimeDoesNotInitialize() throws Exception {
    var runtime = factory(null).create(auto(temp));
    assertThat(runtime.resolution().projectRoot()).isEqualTo(temp.toRealPath());
    try (var files = Files.list(temp)) {
      assertThat(files).isEmpty();
    }
    assertThat(runtime.workspace().status().workspace().initialized()).isFalse();
  }

  @Test
  void twoProjectsKeepTasksKnowledgeAndHistorySeparate() throws Exception {
    var f = factory(null);
    var a = f.create(auto(Files.createDirectory(temp.resolve("A"))));
    var b = f.create(auto(Files.createDirectory(temp.resolve("B"))));
    a.workspace().initialize(NONE);
    b.workspace().initialize(NONE);
    var task = a.tasks().create(task("A 独有"));
    var handoff = a.history().saveHandoff(task.taskId(), handoff(), task.revision());
    a.knowledge()
        .save(
            new SaveKnowledgeRequest(
                null, new KnowledgeInput("A知识", List.of(), List.of(task.taskId()), "仅A可见"), null));
    assertThat(b.tasks().list(null).total()).isZero();
    assertThat(b.knowledge().search(null)).isEmpty();
    ProjectResolverTest.error(
        () ->
            b.context()
                .load(
                    new dev.localworkspace.context.ContextModels.ContextRequest(
                        task.taskId(), null, null, null)),
        ErrorCode.NOT_FOUND);
    ProjectResolverTest.error(
        () ->
            b.history()
                .read(
                    new HistoryRequest(
                        task.taskId(), handoff.checkpointId(), null, null, null, null)),
        ErrorCode.NOT_FOUND);
    assertThat(
            a.history()
                .read(new HistoryRequest(task.taskId(), null, null, null, null, null))
                .total())
        .isEqualTo(1);
    assertThat(a.knowledge().search(null)).hasSize(1);
  }

  @Test
  void reusingRuntimeAfterAnotherCreationKeepsItsProject() throws Exception {
    var f = factory(null);
    var a = f.create(auto(temp));
    f.create(auto(Files.createDirectory(temp.resolve("B")))).workspace().initialize(NONE);
    a.workspace().initialize(NONE);
    a.tasks().create(task("A"));
    assertThat(a.tasks().list(null).items()).extracting(t -> t.title()).containsExactly("A");
  }

  @Test
  void concurrentProjectsDoNotRetarget() throws Exception {
    var f = factory(null);
    var a = f.create(auto(Files.createDirectory(temp.resolve("A"))));
    var b = f.create(auto(Files.createDirectory(temp.resolve("B"))));
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var fa =
          pool.submit(
              () -> {
                start.await();
                a.workspace().initialize(NONE);
                return a.tasks().create(task("A"));
              });
      var fb =
          pool.submit(
              () -> {
                start.await();
                b.workspace().initialize(NONE);
                return b.tasks().create(task("B"));
              });
      start.countDown();
      assertThat(fa.get(10, TimeUnit.SECONDS).title()).isEqualTo("A");
      assertThat(fb.get(10, TimeUnit.SECONDS).title()).isEqualTo("B");
      assertThat(a.tasks().list(null).total()).isEqualTo(1);
      assertThat(b.tasks().list(null).total()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sameRootRuntimesDetectStaleRevision() throws Exception {
    var f = factory(null);
    var a = f.create(auto(temp));
    var b = f.create(auto(temp));
    a.workspace().initialize(NONE);
    var task = a.tasks().create(task("CAS"));
    a.tasks().update(update(task, status(TaskState.ACTIVE, List.of("下一步"))));
    ProjectResolverTest.error(
        () -> b.tasks().update(update(task, status(TaskState.ACTIVE, List.of("旧版本")))),
        ErrorCode.REVISION_CONFLICT);
  }

  @Test
  void differentProjectIsNotBlockedByOtherProjectLock() throws Exception {
    var f = factory(null);
    Path a = Files.createDirectory(temp.resolve("A"));
    var b = f.create(auto(Files.createDirectory(temp.resolve("B"))));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holder =
          pool.submit(
              () ->
                  new ProjectLock(a)
                      .withLock(
                          () -> {
                            held.countDown();
                            try {
                              if (!release.await(8, TimeUnit.SECONDS))
                                throw new AssertionError("lock release timeout");
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                            return true;
                          }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              pool.submit(() -> b.workspace().initialize(NONE))
                  .get(3, TimeUnit.SECONDS)
                  .workspace()
                  .initialized())
          .isTrue();
      release.countDown();
      assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void invalidDirectoryNameStillRejected() {
    for (String name : List.of("../escape", "CON", "end.", "a/b"))
      ProjectResolverTest.error(() -> factory(name), ErrorCode.INVALID_ARGUMENT);
  }
}
