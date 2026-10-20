package dev.localworkspace.history;

import dev.localworkspace.task.TaskModels.DocumentKind;
import java.time.Instant;
import java.util.*;

public final class HistoryModels {
  private HistoryModels() {}

  public record CheckpointInfo(
      String checkpointId,
      Instant createdAt,
      String reason,
      String taskRevision,
      Map<String, String> hashes) {}

  public record CheckpointPage(List<CheckpointInfo> items, int offset, int limit, int total) {}

  public record HistoryDocument(String text, String fileRevision) {}

  public record HistoryData(
      String taskId,
      String checkpointId,
      List<CheckpointInfo> checkpoints,
      Map<DocumentKind, HistoryDocument> documents,
      int offset,
      int limit,
      int total) {}

  public record HistoryRequest(
      String taskId,
      String checkpointId,
      List<DocumentKind> documents,
      Integer maxChars,
      Integer offset,
      Integer limit) {}

  public record HandoffResult(String taskId, String revision, String checkpointId) {}
}
