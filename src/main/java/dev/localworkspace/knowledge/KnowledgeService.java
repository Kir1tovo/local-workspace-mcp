package dev.localworkspace.knowledge;

import static dev.localworkspace.knowledge.KnowledgeModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import dev.localworkspace.workspace.*;
import java.util.*;

public class KnowledgeService {
  private final WorkspaceRepository workspace;
  private final KnowledgeRepository repository;
  private final TaskRepository tasks;
  private final ProjectLock lock;

  public KnowledgeService(
      WorkspaceRepository w, KnowledgeRepository k, TaskRepository t, ProjectLock l) {
    workspace = w;
    repository = k;
    tasks = t;
    lock = l;
  }

  public KnowledgeRecord save(SaveKnowledgeRequest r) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          if (r == null || r.input() == null) throw Checks.invalid("knowledge input is required");
          Checks.strings(r.input().tags());
          for (String id : Checks.strings(r.input().taskIds())) tasks.read(id);
          return repository.save(r);
        });
  }

  public List<KnowledgeHit> search(KnowledgeQuery query) {
    return lock.withLock(
        () -> {
          workspace.requireInitialized();
          var q = query == null ? new KnowledgeQuery(null, null, null, null) : query;
          int limit = Checks.range(q.limit(), 10, 1, 20, "limit");
          String term = q.query() == null ? "" : q.query().toLowerCase(Locale.ROOT);
          List<String> tags =
              Checks.strings(q.tags()).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
          if (q.taskId() != null) tasks.read(q.taskId());
          return repository.list().stream()
              .filter(k -> q.taskId() == null || k.taskIds().contains(q.taskId()))
              .filter(
                  k ->
                      k.tags().stream()
                          .map(s -> s.toLowerCase(Locale.ROOT))
                          .toList()
                          .containsAll(tags))
              .filter(k -> rank(k, term) < 3)
              .sorted(
                  Comparator.comparingInt((KnowledgeRecord k) -> rank(k, term))
                      .thenComparing(KnowledgeRecord::updatedAt, Comparator.reverseOrder())
                      .thenComparing(KnowledgeRecord::id))
              .limit(limit)
              .map(
                  k ->
                      new KnowledgeHit(
                          k.id(), k.title(), k.tags(), preview(k.body()), true, k.revision()))
              .toList();
        });
  }

  private int rank(KnowledgeRecord record, String term) {
    if (term.isEmpty() || record.title().toLowerCase(Locale.ROOT).contains(term)) return 0;
    if (record.tags().stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(term))) return 1;
    return record.body().toLowerCase(Locale.ROOT).contains(term) ? 2 : 3;
  }

  private String preview(String body) {
    return body.substring(
        0, body.offsetByCodePoints(0, Math.min(200, body.codePointCount(0, body.length()))));
  }
}
