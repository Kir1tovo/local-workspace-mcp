package dev.localworkspace.context;

import dev.localworkspace.task.TaskModels.*;
import java.util.List;

public final class ContextModels {
  private ContextModels() {}

  public record ContextRequest(
      String taskId, List<DocumentKind> documents, List<String> knowledgeIds, Integer maxChars) {}

  public record ContextDocument(
      String kind, String id, String path, String text, String fileRevision) {}

  public record ContextData(
      String taskId,
      String revision,
      TaskState state,
      List<ContextDocument> documents,
      String authoritativeNextStepsDocument,
      int totalChars) {
    public ContextData withTotalChars(int chars) {
      return new ContextData(
          taskId, revision, state, documents, authoritativeNextStepsDocument, chars);
    }
  }
}
