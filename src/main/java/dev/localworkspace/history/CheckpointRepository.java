package dev.localworkspace.history;

import static dev.localworkspace.history.HistoryModels.*;
import static dev.localworkspace.task.TaskModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.localworkspace.common.*;
import dev.localworkspace.markdown.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class CheckpointRepository implements CheckpointWriter {
  private final WorkspacePaths paths;
  private final DocumentStore store;
  private final MarkdownCodec codec;
  private final ObjectMapper json;
  private final Clock clock;
  private static final List<DocumentKind> KINDS =
      List.of(DocumentKind.TASK, DocumentKind.STATUS, DocumentKind.HANDOFF);

  public CheckpointRepository(
      WorkspacePaths p, DocumentStore s, MarkdownCodec c, ObjectMapper j, Clock clock) {
    paths = p;
    store = s;
    codec = c;
    json = j;
    this.clock = clock;
  }

  public String save(TaskSnapshot snapshot, String reason) {
    Path parent = paths.task(snapshot.taskId()).resolve("archive");
    store.directories(parent);
    store.cleanupOwnedStaging(parent);
    String id = "checkpoint-" + clock.millis() + "-" + UUID.randomUUID();
    Path stage = store.staging(parent, "checkpoint");
    Map<String, String> hashes = new LinkedHashMap<>();
    for (DocumentKind kind : KINDS) {
      String name = TaskRepository.filename(kind);
      byte[] bytes = store.read(paths.task(snapshot.taskId()).resolve(name));
      String hash = ContentRevision.sha256(bytes);
      if (!hash.equals(snapshot.documents().get(kind).fileRevision()))
        throw new WorkspaceException(
            ErrorCode.REVISION_CONFLICT, "Task changed before checkpoint publication");
      store.create(stage.resolve(name), bytes);
      hashes.put(name, hash);
    }
    try {
      store.create(
          stage.resolve("checkpoint.json"),
          json.writeValueAsBytes(
              Map.of(
                  "checkpointId",
                  id,
                  "createdAt",
                  clock.instant().toString(),
                  "reason",
                  reason,
                  "taskRevision",
                  snapshot.revision(),
                  "hashes",
                  hashes)));
    } catch (IOException e) {
      throw new WorkspaceException(ErrorCode.IO_ERROR, "Cannot encode checkpoint", e);
    }
    store.publishDirectory(stage, paths.checkpoint(snapshot.taskId(), id));
    return id;
  }

  private CheckpointInfo info(String taskId, String checkpointId) {
    try {
      var result =
          json.readValue(
              store.read(paths.checkpoint(taskId, checkpointId).resolve("checkpoint.json")),
              CheckpointInfo.class);
      if (!checkpointId.equals(result.checkpointId())
          || result.createdAt() == null
          || result.reason() == null
          || !result.taskRevision().matches("[0-9a-f]{64}")
          || result.hashes() == null
          || !result.hashes().keySet().equals(Set.of("task.md", "STATUS.md", "handoff.md"))
          || result.hashes().values().stream()
              .anyMatch(h -> h == null || !h.matches("[0-9a-f]{64}")))
        throw new IllegalArgumentException();
      return result;
    } catch (IOException | IllegalArgumentException | NullPointerException e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid checkpoint metadata", e);
    }
  }

  public CheckpointPage list(String taskId, int offset, int limit) {
    List<CheckpointInfo> all = new ArrayList<>();
    for (Path child : store.list(paths.task(taskId).resolve("archive")))
      if (child.getFileName().toString().matches("checkpoint-[0-9]+-[0-9a-f-]{36}"))
        all.add(info(taskId, child.getFileName().toString()));
    all.sort(
        Comparator.comparing(CheckpointInfo::createdAt)
            .reversed()
            .thenComparing(CheckpointInfo::checkpointId));
    return new CheckpointPage(
        all.stream().skip(offset).limit(limit).toList(), offset, limit, all.size());
  }

  public HistoryData read(String taskId, String checkpointId, List<DocumentKind> documents) {
    var metadata = info(taskId, checkpointId);
    List<DocumentKind> selected = documents == null ? KINDS : documents;
    if (selected.isEmpty() || selected.stream().anyMatch(k -> k == null || k == DocumentKind.GUIDE))
      throw Checks.invalid("Select TASK, STATUS or HANDOFF history documents");
    Map<DocumentKind, HistoryDocument> result = new LinkedHashMap<>();
    for (DocumentKind kind : KINDS) {
      if (!selected.contains(kind)) continue;
      String name = TaskRepository.filename(kind);
      byte[] raw = store.read(paths.checkpoint(taskId, checkpointId).resolve(name));
      String hash = ContentRevision.sha256(raw);
      if (!hash.equals(metadata.hashes().get(name)))
        throw new WorkspaceException(
            ErrorCode.FORMAT_ERROR, "Checkpoint content does not match saved hash");
      var doc = codec.parse(raw);
      result.put(kind, new HistoryDocument(doc.text(), hash));
    }
    return new HistoryData(
        taskId, checkpointId, List.of(metadata), Collections.unmodifiableMap(result), 0, 1, 1);
  }
}
