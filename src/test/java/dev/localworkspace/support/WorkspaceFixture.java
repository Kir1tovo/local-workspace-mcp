package dev.localworkspace.support;

import static dev.localworkspace.task.TaskModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.localworkspace.common.*;
import dev.localworkspace.config.*;
import dev.localworkspace.context.*;
import dev.localworkspace.history.*;
import dev.localworkspace.knowledge.*;
import dev.localworkspace.markdown.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import dev.localworkspace.workspace.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;

public class WorkspaceFixture {
  public final WorkspacePaths paths;
  public final DocumentStore store;
  public final ProjectLock lock;
  public final Clock clock = Clock.fixed(Instant.parse("2026-10-16T01:00:00Z"), ZoneOffset.UTC);
  public final WorkspaceProperties properties;
  public final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  public final WorkspaceRepository workspaceRepository;
  public final WorkspaceService workspace;
  public final MarkdownCodec codec = new MarkdownCodec();
  public final TaskRepository tasks;
  public final TaskService taskService;
  public final CheckpointRepository checkpoints;
  public final HistoryService history;
  public final KnowledgeRepository knowledgeRepository;
  public final KnowledgeService knowledge;
  public final ResponseEncoder encoder;
  public final ContextService context;

  public WorkspaceFixture(Path root) {
    this(root, s -> s, "LocalWorkspace");
  }

  public WorkspaceFixture(Path root, UnaryOperator<DocumentStore> decorator, String directory) {
    this(root, decorator, directory, null);
  }

  public WorkspaceFixture(
      Path root,
      UnaryOperator<DocumentStore> decorator,
      String directory,
      WorkspaceProperties.Limits limits) {
    properties = new WorkspaceProperties(root, directory, limits);
    paths = new WorkspacePaths(root, directory);
    store = decorator.apply(new AtomicDocumentStore(paths));
    lock = new ProjectLock(root);
    workspaceRepository = new WorkspaceRepository(paths, store, properties, json, clock);
    workspace =
        new WorkspaceService(workspaceRepository, new GuidanceInstaller(paths, store), lock);
    tasks = new TaskRepository(paths, store, codec, properties, clock);
    checkpoints = new CheckpointRepository(paths, store, codec, json, clock);
    taskService = new TaskService(workspaceRepository, tasks, lock, checkpoints);
    history = new HistoryService(workspaceRepository, tasks, checkpoints, lock);
    knowledgeRepository = new KnowledgeRepository(paths, store, codec, properties, clock);
    knowledge = new KnowledgeService(workspaceRepository, knowledgeRepository, tasks, lock);
    encoder = new ResponseEncoder(json);
    context =
        new ContextService(
            paths,
            store,
            workspaceRepository,
            tasks,
            knowledgeRepository,
            lock,
            properties,
            encoder);
  }

  public static CreateTaskRequest task(String title) {
    return new CreateTaskRequest(
        new TaskContent(
            title,
            "实现" + title,
            List.of("接口"),
            List.of("使用 JWT"),
            List.of("实现接口", "补充测试"),
            List.of("测试通过"),
            List.of("auth")),
        List.of("实现接口"));
  }

  public static StatusContent status(TaskState state, List<String> next) {
    return new StatusContent(
        state,
        "当前工作",
        List.of("登录完成"),
        List.of(),
        next,
        List.of("AuthService.java"),
        List.of("接口测试通过"));
  }

  public static UpdateTaskRequest update(TaskSnapshot task, StatusContent status) {
    return new UpdateTaskRequest(
        task.taskId(), DocumentKind.STATUS, task.revision(), null, status, false);
  }

  public static HandoffContent handoff() {
    return new HandoffContent(
        "登录已完成，接下来实现刷新令牌",
        List.of(new Decision("使用 Redis", "支持令牌失效")),
        List.of("先核对代码"),
        List.of("AuthService.java"),
        List.of("登录接口测试通过"));
  }
}
