package dev.localworkspace.project;

import java.nio.file.Path;

public record ProjectResolution(Path projectRoot, Mode mode, Source source) {
  public enum Mode {
    FIXED,
    AUTO
  }

  public enum Source {
    CONFIG,
    MCP_ROOTS,
    AGENT_ARGUMENT
  }

  public record ResolutionInfo(String mode, String source, String projectRoot) {}

  public ResolutionInfo info() {
    return new ResolutionInfo(mode.name(), source.name(), projectRoot.toString());
  }
}
