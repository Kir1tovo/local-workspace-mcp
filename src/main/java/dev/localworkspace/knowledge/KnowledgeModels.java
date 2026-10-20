package dev.localworkspace.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

public final class KnowledgeModels {
  private KnowledgeModels() {}

  public record KnowledgeInput(
      String title, List<String> tags, List<String> taskIds, String body) {}

  public record SaveKnowledgeRequest(
      String knowledgeId, KnowledgeInput input, String expectedRevision) {}

  public record KnowledgeRecord(
      String id,
      String title,
      List<String> tags,
      List<String> taskIds,
      @JsonIgnore String body,
      Instant createdAt,
      Instant updatedAt,
      String revision,
      @JsonIgnore String text) {}

  public record KnowledgeQuery(String query, List<String> tags, String taskId, Integer limit) {}

  public record KnowledgeHit(
      String id,
      String title,
      List<String> tags,
      String bodyPreview,
      boolean preview,
      String revision) {}
}
