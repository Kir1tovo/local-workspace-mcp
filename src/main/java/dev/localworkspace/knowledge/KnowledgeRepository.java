package dev.localworkspace.knowledge;

import static dev.localworkspace.knowledge.KnowledgeModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.config.*;
import dev.localworkspace.markdown.*;
import dev.localworkspace.storage.*;
import dev.localworkspace.task.TaskRepository;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class KnowledgeRepository {
  private final WorkspacePaths paths;
  private final DocumentStore store;
  private final MarkdownCodec codec;
  private final WorkspaceProperties properties;
  private final Clock clock;

  public KnowledgeRepository(
      WorkspacePaths p,
      DocumentStore s,
      MarkdownCodec c,
      WorkspaceProperties properties,
      Clock clock) {
    paths = p;
    store = s;
    codec = c;
    this.properties = properties;
    this.clock = clock;
  }

  public KnowledgeRecord read(String id) {
    var doc = codec.parse(store.read(paths.knowledge(id)));
    var m = doc.metadata();
    try {
      if (!Integer.valueOf(1).equals(m.get("schemaVersion"))
          || !"knowledge".equals(m.get("documentType"))
          || !id.equals(m.get("id"))
          || !(m.get("title") instanceof String title)
          || title.isBlank()) throw new IllegalArgumentException();
      return new KnowledgeRecord(
          id,
          title,
          TaskRepository.metadataStrings(m.get("tags")),
          TaskRepository.metadataStrings(m.get("taskIds")),
          doc.body(),
          Instant.parse((String) m.get("createdAt")),
          Instant.parse((String) m.get("updatedAt")),
          doc.fileRevision(),
          doc.text());
    } catch (RuntimeException e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid knowledge metadata", e);
    }
  }

  public List<KnowledgeRecord> list() {
    List<KnowledgeRecord> result = new ArrayList<>();
    for (Path p : store.list(paths.workspaceRoot().resolve("knowledge"))) {
      String name = p.getFileName().toString();
      if (name.matches("knowledge-[0-9a-f-]{36}\\.md"))
        result.add(read(name.substring(0, name.length() - 3)));
    }
    return result;
  }

  public KnowledgeRecord save(SaveKnowledgeRequest request) {
    var input = request.input();
    Checks.text(input.title(), "title");
    Checks.text(input.body(), "body");
    Checks.size(input.body(), properties.limits().knowledgeChars());
    String id = request.knowledgeId();
    KnowledgeRecord previous = null;
    if (id == null) {
      if (request.expectedRevision() != null)
        throw Checks.invalid("A new knowledge entry has no expectedRevision");
      id = "knowledge-" + UUID.randomUUID();
    } else {
      Checks.revision(request.expectedRevision());
      previous = read(id);
      if (!previous.revision().equals(request.expectedRevision()))
        throw new WorkspaceException(
            ErrorCode.REVISION_CONFLICT,
            "Knowledge changed; reload before saving",
            Map.of("currentRevision", previous.revision()));
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("schemaVersion", 1);
    meta.put("documentType", "knowledge");
    meta.put("id", id);
    meta.put("title", input.title());
    meta.put("tags", Checks.strings(input.tags()));
    meta.put("taskIds", Checks.strings(input.taskIds()));
    meta.put(
        "createdAt",
        previous == null ? clock.instant().toString() : previous.createdAt().toString());
    meta.put("updatedAt", clock.instant().toString());
    byte[] bytes = codec.render(meta, input.body());
    if (bytes.length > 262144)
      throw new WorkspaceException(
          ErrorCode.SUMMARY_REQUIRED, "Knowledge metadata and body exceed the 256 KiB file limit");
    if (previous == null) store.create(paths.knowledge(id), bytes);
    else store.replace(paths.knowledge(id), bytes, previous.revision());
    return read(id);
  }
}
