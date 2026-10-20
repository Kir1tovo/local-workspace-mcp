package dev.localworkspace.task;

import static dev.localworkspace.task.TaskModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.workspace.*;
import java.util.*;

public class TaskService {
  private final WorkspaceRepository workspace;
  private final TaskRepository repository;
  private final ProjectLock lock;
  private final CheckpointWriter checkpoints;

  public TaskService(WorkspaceRepository w, TaskRepository r, ProjectLock l, CheckpointWriter c) {
    workspace = w;
    repository = r;
    lock = l;
    checkpoints = c;
  }

  public TaskSnapshot create(CreateTaskRequest r) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          if (r == null) throw Checks.invalid("request is required");
          TaskRepository.validateTask(r.task());
          if (Checks.strings(r.nextSteps()).isEmpty())
            throw Checks.invalid("nextSteps is required");
          return repository.create(r);
        });
  }

  public TaskSnapshot update(UpdateTaskRequest r) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          if (r == null) throw Checks.invalid("request is required");
          if (r.document() != DocumentKind.TASK && r.document() != DocumentKind.STATUS)
            throw Checks.invalid("document must be TASK or STATUS");
          if (r.document() == DocumentKind.TASK
              ? (r.task() == null || r.status() != null)
              : (r.status() == null || r.task() != null))
            throw Checks.invalid("Provide exactly the selected document content");
          var current = repository.read(r.taskId());
          TaskRepository.requireRevision(current, r.expectedRevision());
          byte[] bytes =
              r.document() == DocumentKind.TASK
                  ? repository.renderTask(
                      r.task(), repository.metadataForUpdate(current, r.document()))
                  : repository.renderStatus(
                      r.status(), repository.metadataForUpdate(current, r.document()));
          String checkpoint = r.checkpoint() ? checkpoints.save(current, "milestone") : null;
          try {
            return repository.replace(current, r.document(), bytes);
          } catch (WorkspaceException e) {
            if (checkpoint != null)
              throw new WorkspaceException(
                  e.code, e.getMessage(), Map.of("checkpointId", checkpoint));
            throw e;
          }
        });
  }

  public TaskPage list(TaskQuery query) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          TaskQuery q = query == null ? new TaskQuery(null, null, null, null) : query;
          int offset = Checks.range(q.offset(), 0, 0, Integer.MAX_VALUE, "offset"),
              limit = Checks.range(q.limit(), 20, 1, 100, "limit");
          String term = q.query() == null ? "" : q.query().toLowerCase(Locale.ROOT);
          List<TaskSummary> all =
              repository.list().stream()
                  .filter(
                      t ->
                          q.state() == null
                              ? t.state() != TaskState.ARCHIVED
                              : t.state() == q.state())
                  .map(
                      t ->
                          new TaskSummary(
                              t.taskId(),
                              t.title(),
                              t.state(),
                              t.revision(),
                              t.updatedAt(),
                              TaskRepository.metadataStrings(
                                  t.documents().get(DocumentKind.TASK).metadata().get("tags"))))
                  .filter(
                      t ->
                          t.title().toLowerCase(Locale.ROOT).contains(term)
                              || t.tags().stream()
                                  .anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(term)))
                  .sorted(
                      Comparator.comparing(TaskSummary::updatedAt)
                          .reversed()
                          .thenComparing(TaskSummary::taskId))
                  .toList();
          return new TaskPage(
              all.stream().skip(offset).limit(limit).toList(), offset, limit, all.size());
        });
  }
}
