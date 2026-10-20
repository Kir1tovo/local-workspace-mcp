package dev.localworkspace.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.localworkspace.common.ResponseEncoder;
import dev.localworkspace.config.WorkspaceProperties;
import dev.localworkspace.context.ContextService;
import dev.localworkspace.history.*;
import dev.localworkspace.knowledge.*;
import dev.localworkspace.markdown.MarkdownCodec;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import dev.localworkspace.workspace.*;
import java.time.Clock;

/** Creates a request-owned service graph. Shared collaborators contain no project selection. */
public final class WorkspaceRuntimeFactory {
  private final WorkspaceProperties properties;
  private final ObjectMapper json;
  private final Clock clock;
  private final MarkdownCodec codec;
  private final ResponseEncoder encoder;

  public WorkspaceRuntimeFactory(
      WorkspaceProperties properties,
      ObjectMapper json,
      Clock clock,
      MarkdownCodec codec,
      ResponseEncoder encoder) {
    WorkspacePaths.validateDirectoryName(properties.directoryName());
    this.properties = properties;
    this.json = json;
    this.clock = clock;
    this.codec = codec;
    this.encoder = encoder;
  }

  public WorkspaceRuntime create(ProjectResolution resolution) {
    var bound =
        new WorkspaceProperties(
            resolution.projectRoot(), properties.directoryName(), properties.limits());
    var paths = new WorkspacePaths(bound.projectRoot(), bound.directoryName());
    var store = new AtomicDocumentStore(paths);
    var lock = new ProjectLock(paths.projectRoot());
    var workspace = new WorkspaceRepository(paths, store, bound, json, clock);
    var guidance = new GuidanceInstaller(paths, store);
    var tasks = new TaskRepository(paths, store, codec, bound, clock);
    var checkpoints = new CheckpointRepository(paths, store, codec, json, clock);
    var knowledge = new KnowledgeRepository(paths, store, codec, bound, clock);
    return new WorkspaceRuntime(
        resolution,
        new WorkspaceService(workspace, guidance, lock),
        new TaskService(workspace, tasks, lock, checkpoints),
        new ContextService(paths, store, workspace, tasks, knowledge, lock, bound, encoder),
        new HistoryService(workspace, tasks, checkpoints, lock),
        new KnowledgeService(workspace, knowledge, tasks, lock));
  }
}
