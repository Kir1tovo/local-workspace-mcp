package dev.localworkspace.context;

import static dev.localworkspace.context.ContextModels.*;
import static dev.localworkspace.task.TaskModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.config.*;
import dev.localworkspace.knowledge.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import dev.localworkspace.workspace.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class ContextService {
  private final WorkspacePaths paths;
  private final DocumentStore store;
  private final WorkspaceRepository workspace;
  private final TaskRepository tasks;
  private final KnowledgeRepository knowledge;
  private final ProjectLock lock;
  private final WorkspaceProperties properties;
  private final ResponseEncoder encoder;

  public ContextService(
      WorkspacePaths p,
      DocumentStore s,
      WorkspaceRepository w,
      TaskRepository t,
      KnowledgeRepository k,
      ProjectLock l,
      WorkspaceProperties properties,
      ResponseEncoder e) {
    paths = p;
    store = s;
    workspace = w;
    tasks = t;
    knowledge = k;
    lock = l;
    this.properties = properties;
    encoder = e;
  }

  public ApiResponse<ContextData> load(ContextRequest request) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          if (request == null) throw Checks.invalid("Context request is required");
          int budget =
              Checks.range(
                  request.maxChars(), properties.limits().contextChars(), 4000, 64000, "maxChars");
          var task = tasks.read(request.taskId());
          var selected =
              request.documents() == null
                  ? List.of(
                      DocumentKind.GUIDE,
                      DocumentKind.TASK,
                      DocumentKind.STATUS,
                      DocumentKind.HANDOFF)
                  : request.documents();
          if (selected.isEmpty() || selected.stream().anyMatch(Objects::isNull))
            throw Checks.invalid("Select at least one valid document");
          List<ContextDocument> docs = new ArrayList<>();
          for (DocumentKind kind : new LinkedHashSet<>(selected)) {
            Path path;
            String text, revision;
            if (kind == DocumentKind.GUIDE) {
              path = paths.workspaceRoot().resolve("AGENT_GUIDE.md");
              byte[] bytes = store.read(path);
              text = Utf8.decodeStrict(bytes);
              revision = ContentRevision.sha256(bytes);
              Checks.size(text, properties.limits().guideChars());
            } else {
              path = paths.task(task.taskId()).resolve(TaskRepository.filename(kind));
              var doc = task.documents().get(kind);
              text = doc.text();
              revision = doc.fileRevision();
              Checks.size(text, properties.limits().documentChars());
            }
            docs.add(
                new ContextDocument(
                    kind.name(),
                    kind == DocumentKind.GUIDE ? "guide" : task.taskId(),
                    relative(path),
                    text,
                    revision));
          }
          for (String id : new LinkedHashSet<>(Checks.strings(request.knowledgeIds()))) {
            var item = knowledge.read(id);
            Checks.size(item.body(), properties.limits().knowledgeChars());
            docs.add(
                new ContextDocument(
                    "KNOWLEDGE", id, relative(paths.knowledge(id)), item.text(), item.revision()));
          }
          List<String> warnings = new ArrayList<>();
          var status = task.documents().get(DocumentKind.STATUS);
          var handoff = task.documents().get(DocumentKind.HANDOFF);
          Object basedOn = handoff.metadata().get("basedOnStatusRevision");
          boolean older =
              Instant.parse((String) handoff.metadata().get("updatedAt"))
                  .isBefore(Instant.parse((String) status.metadata().get("updatedAt")));
          if (basedOn == null || !status.fileRevision().equals(basedOn) || older)
            warnings.add(
                "Handoff is absent or older than STATUS.md; use STATUS.md for the authoritative"
                    + " next steps.");
          if (!selected.contains(DocumentKind.STATUS))
            warnings.add("STATUS.md was not selected; load it before executing the next step.");
          ContextData data =
              new ContextData(
                  task.taskId(), task.revision(), task.state(), List.copyOf(docs), "STATUS.md", 0);
          ApiResponse<ContextData> result = ApiResponse.success(data, warnings);
          for (int i = 0; i < 10; i++) {
            int count = encoder.characters(result);
            if (count == result.data().totalChars()) break;
            result = ApiResponse.success(result.data().withTotalChars(count), warnings);
          }
          encoder.requireBudget(result, budget);
          return result;
        });
  }

  private String relative(Path path) {
    return paths.projectRoot().relativize(path).toString().replace('\\', '/');
  }
}
