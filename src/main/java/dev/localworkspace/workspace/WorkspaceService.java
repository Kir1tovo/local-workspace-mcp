package dev.localworkspace.workspace;

import static dev.localworkspace.workspace.WorkspaceModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.ProjectLock;
import java.util.*;

public class WorkspaceService {
  private final WorkspaceRepository repository;
  private final GuidanceInstaller guidance;
  private final ProjectLock lock;

  public WorkspaceService(WorkspaceRepository r, GuidanceInstaller g, ProjectLock l) {
    repository = r;
    guidance = g;
    lock = l;
  }

  public InitResult initialize(GuidanceTarget target) {
    return lock.withLock(
        () -> {
          try {
            var info = repository.initialize();
            var changed = guidance.install(target == null ? GuidanceTarget.AGENTS : target);
            return new InitResult(
                info,
                changed.stream().map(p -> p.getFileName().toString()).toList(),
                repository.instructions());
          } catch (WorkspaceException e) {
            if (e.code == ErrorCode.WORKSPACE_CONFLICT || e.code == ErrorCode.FORMAT_ERROR) {
              try {
                if (repository.info().workspaceId() == null) throw e;
              } catch (WorkspaceException check) {
                throw e;
              }
            }
            boolean created;
            try {
              created = repository.info().workspaceId() != null;
            } catch (WorkspaceException ignored) {
              created = false;
            }
            if (created)
              throw new WorkspaceException(
                  ErrorCode.PARTIAL_INITIALIZATION,
                  "Workspace exists; initialization incomplete. Retry workspace_init after"
                      + " resolving the reported cause.",
                  Map.of(
                      "workspaceCreated",
                      true,
                      "causeCode",
                      e.code.name(),
                      "cause",
                      e.getMessage()));
            throw e;
          }
        });
  }

  public WorkspaceStatus status() {
    return lock.withLock(() -> new WorkspaceStatus(repository.info(), guidance.status()));
  }
}
