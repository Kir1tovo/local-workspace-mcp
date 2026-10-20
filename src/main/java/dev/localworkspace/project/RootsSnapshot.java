package dev.localworkspace.project;

import java.util.List;

public record RootsSnapshot(List<String> uris, String nextCursor) {
  public RootsSnapshot {
    if (uris != null)
      uris = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(uris));
  }
}
