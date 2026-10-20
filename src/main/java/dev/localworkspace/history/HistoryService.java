package dev.localworkspace.history;

import static dev.localworkspace.history.HistoryModels.*;
import static dev.localworkspace.task.TaskModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import dev.localworkspace.workspace.*;
import java.util.*;

public class HistoryService {
  private final WorkspaceRepository workspace;
  private final TaskRepository tasks;
  private final CheckpointRepository checkpoints;
  private final ProjectLock lock;

  public HistoryService(
      WorkspaceRepository w, TaskRepository t, CheckpointRepository c, ProjectLock l) {
    workspace = w;
    tasks = t;
    checkpoints = c;
    lock = l;
  }

  public HandoffResult saveHandoff(String taskId, HandoffContent content, String expectedRevision) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          var snapshot = tasks.read(taskId);
          TaskRepository.requireRevision(snapshot, expectedRevision);
          var metadata = tasks.metadataForUpdate(snapshot, DocumentKind.HANDOFF);
          metadata.put(
              "basedOnStatusRevision",
              snapshot.documents().get(DocumentKind.STATUS).fileRevision());
          byte[] next = tasks.renderHandoff(content, metadata);
          String checkpoint = checkpoints.save(snapshot, "handoff");
          try {
            var updated = tasks.replace(snapshot, DocumentKind.HANDOFF, next);
            return new HandoffResult(taskId, updated.revision(), checkpoint);
          } catch (WorkspaceException e) {
            throw new WorkspaceException(
                e.code, e.getMessage(), Map.of("checkpointId", checkpoint));
          }
        });
  }

  public HistoryData read(HistoryRequest request) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          if (request == null) throw Checks.invalid("request is required");
          tasks.requireDirectory(request.taskId());
          Checks.range(request.maxChars(), 24000, 4000, 64000, "maxChars");
          int offset = Checks.range(request.offset(), 0, 0, Integer.MAX_VALUE, "offset"),
              limit = Checks.range(request.limit(), 20, 1, 100, "limit");
          if (request.checkpointId() != null)
            return checkpoints.read(request.taskId(), request.checkpointId(), request.documents());
          var page = checkpoints.list(request.taskId(), offset, limit);
          return new HistoryData(
              request.taskId(), null, page.items(), Map.of(), offset, limit, page.total());
        });
  }
}
