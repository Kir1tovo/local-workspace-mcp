package dev.localworkspace.task;

import static dev.localworkspace.task.TaskModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.config.*;
import dev.localworkspace.markdown.*;
import dev.localworkspace.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class TaskRepository {
  private final WorkspacePaths paths;
  private final DocumentStore store;
  private final MarkdownCodec codec;
  private final WorkspaceProperties properties;
  private final Clock clock;

  public TaskRepository(
      WorkspacePaths p,
      DocumentStore s,
      MarkdownCodec codec,
      WorkspaceProperties properties,
      Clock clock) {
    paths = p;
    store = s;
    this.codec = codec;
    this.properties = properties;
    this.clock = clock;
  }

  public static String filename(DocumentKind kind) {
    return switch (kind) {
      case TASK -> "task.md";
      case STATUS -> "STATUS.md";
      case HANDOFF -> "handoff.md";
      default -> throw Checks.invalid("Guide is not a task document");
    };
  }

  public TaskSnapshot create(CreateTaskRequest request) {
    var content = request.task();
    String id = "task-" + UUID.randomUUID();
    var status =
        new StatusContent(
            TaskState.ACTIVE,
            "尚未开始",
            List.of(),
            List.of(),
            request.nextSteps(),
            List.of(),
            List.of());
    byte[] task = renderTask(content, metadata(id, content.title(), DocumentKind.TASK));
    byte[] state = renderStatus(status, metadata(id, content.title(), DocumentKind.STATUS));
    byte[] handoff =
        renderHandoff(
            new HandoffContent("尚无交接记录", List.of(), List.of(), List.of(), List.of()),
            metadata(id, content.title(), DocumentKind.HANDOFF));
    Path staging = store.staging(paths.workspaceRoot().resolve("tasks"), "task");
    store.create(staging.resolve("task.md"), task);
    store.create(staging.resolve("STATUS.md"), state);
    store.create(staging.resolve("handoff.md"), handoff);
    store.directories(staging.resolve("archive"));
    store.publishDirectory(staging, paths.task(id));
    return read(id);
  }

  public TaskSnapshot read(String id) {
    Path dir = requireDirectory(id);
    Map<DocumentKind, MarkdownDocument> docs = new EnumMap<>(DocumentKind.class);
    List<byte[]> bytes = new ArrayList<>();
    for (DocumentKind kind :
        List.of(DocumentKind.TASK, DocumentKind.STATUS, DocumentKind.HANDOFF)) {
      try {
        byte[] raw = store.read(dir.resolve(filename(kind)));
        var doc = codec.parse(raw);
        validateMetadata(doc, id, kind);
        docs.put(kind, doc);
        bytes.add(raw);
      } catch (WorkspaceException e) {
        if (e.code == ErrorCode.NOT_FOUND)
          throw new WorkspaceException(
              ErrorCode.FORMAT_ERROR, "Task is missing a required document", e);
        throw e;
      }
    }
    try {
      String title = (String) docs.get(DocumentKind.TASK).metadata().get("title");
      TaskState state =
          TaskState.valueOf((String) docs.get(DocumentKind.STATUS).metadata().get("state"));
      Instant updated =
          docs.values().stream()
              .map(d -> Instant.parse((String) d.metadata().get("updatedAt")))
              .max(Comparator.naturalOrder())
              .orElseThrow();
      return new TaskSnapshot(
          id,
          title,
          state,
          ContentRevision.task(bytes.get(0), bytes.get(1), bytes.get(2)),
          updated,
          docs);
    } catch (RuntimeException e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid task state or timestamp", e);
    }
  }

  /**
   * Existence check used by history, which must remain readable if current documents are damaged.
   */
  public Path requireDirectory(String id) {
    Path dir = paths.task(id);
    if (!store.exists(dir)) throw new WorkspaceException(ErrorCode.NOT_FOUND, "Task not found");
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Task path is not a directory");
    return dir;
  }

  public List<TaskSnapshot> list() {
    List<TaskSnapshot> result = new ArrayList<>();
    for (Path dir : store.list(paths.workspaceRoot().resolve("tasks")))
      if (dir.getFileName().toString().matches("task-[0-9a-f-]{36}"))
        result.add(read(dir.getFileName().toString()));
    return result;
  }

  public TaskSnapshot replace(TaskSnapshot expected, DocumentKind kind, byte[] replacement) {
    requireRevision(read(expected.taskId()), expected.revision());
    var doc = codec.parse(replacement);
    validateMetadata(doc, expected.taskId(), kind);
    checkSize(replacement);
    store.replace(
        paths.task(expected.taskId()).resolve(filename(kind)),
        replacement,
        expected.documents().get(kind).fileRevision());
    return read(expected.taskId());
  }

  public static void requireRevision(TaskSnapshot snapshot, String expected) {
    Checks.revision(expected);
    if (!snapshot.revision().equals(expected))
      throw new WorkspaceException(
          ErrorCode.REVISION_CONFLICT,
          "Task changed; call context_load and merge before saving",
          Map.of("currentRevision", snapshot.revision()));
  }

  private Map<String, Object> metadata(String id, String title, DocumentKind kind) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schemaVersion", 1);
    m.put("documentType", kind.name().toLowerCase(Locale.ROOT));
    m.put("id", id);
    m.put("title", title);
    m.put("createdAt", clock.instant().toString());
    m.put("updatedAt", clock.instant().toString());
    return m;
  }

  public Map<String, Object> metadataForUpdate(TaskSnapshot snapshot, DocumentKind kind) {
    Map<String, Object> result = new LinkedHashMap<>(snapshot.documents().get(kind).metadata());
    result.put("updatedAt", clock.instant().toString());
    return result;
  }

  public byte[] renderTask(TaskContent c, Map<String, Object> m) {
    validateTask(c);
    m = new LinkedHashMap<>(m);
    m.put("title", c.title());
    m.put("tags", Checks.strings(c.tags()));
    return limited(
        codec.render(
            m,
            "# "
                + c.title()
                + "\n\n## 目标\n"
                + c.goal()
                + "\n\n## 范围\n"
                + Checks.bullets(c.scope())
                + "\n## 约束\n"
                + Checks.bullets(c.constraints())
                + "\n## 实施计划\n"
                + Checks.bullets(c.plan())
                + "\n## 验收标准\n"
                + Checks.bullets(c.acceptanceCriteria())));
  }

  public byte[] renderStatus(StatusContent c, Map<String, Object> m) {
    validateStatus(c);
    m = new LinkedHashMap<>(m);
    m.put("state", c.state().name());
    return limited(
        codec.render(
            m,
            "# 当前状态\n\n## 已完成\n"
                + Checks.bullets(c.completed())
                + "\n## 当前工作\n"
                + c.current()
                + "\n\n## 阻塞\n"
                + Checks.bullets(c.blockers())
                + "\n## 下一步\n"
                + Checks.bullets(c.nextSteps())
                + "\n## 相关文件\n"
                + Checks.bullets(c.relatedFiles())
                + "\n## 验证结果\n"
                + Checks.bullets(c.verificationResults())));
  }

  public byte[] renderHandoff(HandoffContent c, Map<String, Object> m) {
    if (c == null) throw Checks.invalid("Handoff content is required");
    Checks.text(c.summary(), "summary");
    List<String> decisions = new ArrayList<>();
    if (c.decisions() != null)
      for (Decision d : c.decisions()) {
        if (d == null) throw Checks.invalid("Decision is required");
        decisions.add(
            Checks.text(d.decision(), "decision") + "；原因：" + Checks.text(d.reason(), "reason"));
      }
    return limited(
        codec.render(
            m,
            "# 最新交接\n\n## 摘要\n"
                + c.summary()
                + "\n\n## 关键决策\n"
                + Checks.bullets(decisions)
                + "\n## 接手注意事项\n"
                + Checks.bullets(c.cautions())
                + "\n## 相关文件\n"
                + Checks.bullets(c.relatedFiles())
                + "\n## 验证结果\n"
                + Checks.bullets(c.verificationResults())));
  }

  private byte[] limited(byte[] bytes) {
    checkSize(bytes);
    return bytes;
  }

  public void checkSize(byte[] bytes) {
    if (bytes.length > 262144)
      throw new WorkspaceException(
          ErrorCode.SUMMARY_REQUIRED,
          "Task document exceeds the 256 KiB file limit; summarize before saving");
    Checks.size(Utf8.decodeStrict(bytes), properties.limits().documentChars());
  }

  public static void validateTask(TaskContent c) {
    if (c == null) throw Checks.invalid("Task content is required");
    Checks.text(c.title(), "title");
    Checks.text(c.goal(), "goal");
    Checks.strings(c.scope());
    Checks.strings(c.constraints());
    Checks.strings(c.plan());
    Checks.strings(c.acceptanceCriteria());
    Checks.strings(c.tags());
  }

  public static void validateStatus(StatusContent c) {
    if (c == null || c.state() == null) throw Checks.invalid("Status and state are required");
    Checks.text(c.current(), "current");
    Checks.strings(c.completed());
    Checks.strings(c.relatedFiles());
    Checks.strings(c.verificationResults());
    var next = Checks.strings(c.nextSteps());
    var blockers = Checks.strings(c.blockers());
    if ((c.state() == TaskState.ACTIVE || c.state() == TaskState.BLOCKED) && next.isEmpty())
      throw Checks.invalid("Active or blocked tasks require nextSteps");
    if (c.state() == TaskState.BLOCKED && blockers.isEmpty())
      throw Checks.invalid("Blocked tasks require blockers");
  }

  private void validateMetadata(MarkdownDocument d, String id, DocumentKind kind) {
    var m = d.metadata();
    try {
      if (!Integer.valueOf(1).equals(m.get("schemaVersion"))
          || !id.equals(m.get("id"))
          || !kind.name().toLowerCase(Locale.ROOT).equals(m.get("documentType")))
        throw new IllegalArgumentException();
      if (!(m.get("title") instanceof String title) || title.isBlank())
        throw new IllegalArgumentException();
      Instant.parse((String) m.get("createdAt"));
      Instant.parse((String) m.get("updatedAt"));
      if (kind == DocumentKind.STATUS) TaskState.valueOf((String) m.get("state"));
      if (kind == DocumentKind.TASK) metadataStrings(m.get("tags"));
    } catch (RuntimeException e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid task document metadata", e);
    }
  }

  public static List<String> metadataStrings(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("Expected a string list");
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String s) || s.isBlank())
        throw new IllegalArgumentException("Expected nonempty strings");
      result.add(s);
    }
    return List.copyOf(result);
  }
}
