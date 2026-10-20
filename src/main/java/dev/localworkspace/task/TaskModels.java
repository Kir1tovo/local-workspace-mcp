package dev.localworkspace.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.localworkspace.markdown.MarkdownDocument;
import java.time.Instant;
import java.util.*;

public final class TaskModels {
  private TaskModels() {}

  public enum TaskState {
    ACTIVE,
    BLOCKED,
    COMPLETED,
    ARCHIVED
  }

  public enum DocumentKind {
    GUIDE,
    TASK,
    STATUS,
    HANDOFF
  }

  public record TaskContent(
      String title,
      String goal,
      List<String> scope,
      List<String> constraints,
      List<String> plan,
      List<String> acceptanceCriteria,
      List<String> tags) {}

  public record StatusContent(
      TaskState state,
      String current,
      List<String> completed,
      List<String> blockers,
      List<String> nextSteps,
      List<String> relatedFiles,
      List<String> verificationResults) {}

  public record Decision(String decision, String reason) {}

  public record HandoffContent(
      String summary,
      List<Decision> decisions,
      List<String> cautions,
      List<String> relatedFiles,
      List<String> verificationResults) {}

  public record CreateTaskRequest(TaskContent task, List<String> nextSteps) {}

  public record UpdateTaskRequest(
      String taskId,
      DocumentKind document,
      String expectedRevision,
      TaskContent task,
      StatusContent status,
      boolean checkpoint) {}

  public record TaskSnapshot(
      String taskId,
      String title,
      TaskState state,
      String revision,
      Instant updatedAt,
      @JsonIgnore Map<DocumentKind, MarkdownDocument> documents) {
    public TaskSnapshot {
      documents = Map.copyOf(documents);
    }
  }

  public record TaskQuery(TaskState state, String query, Integer offset, Integer limit) {}

  public record TaskSummary(
      String taskId,
      String title,
      TaskState state,
      String revision,
      Instant updatedAt,
      List<String> tags) {}

  public record TaskPage(List<TaskSummary> items, int offset, int limit, int total) {}
}
