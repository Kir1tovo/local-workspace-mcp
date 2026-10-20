package dev.localworkspace.markdown;

import java.util.Map;

public record MarkdownDocument(
    String text, String body, Map<String, Object> metadata, String fileRevision) {
  public MarkdownDocument {
    metadata = Map.copyOf(metadata);
  }
}
