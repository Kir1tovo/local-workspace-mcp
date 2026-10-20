package dev.localworkspace.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("local-workspace")
public record WorkspaceProperties(Path projectRoot, String directoryName, Limits limits) {
  public WorkspaceProperties {
    if (projectRoot != null && projectRoot.toString().isBlank())
      throw new IllegalArgumentException("local-workspace.project-root must not be blank");
    if (directoryName == null) directoryName = "LocalWorkspace";
    if (limits == null) limits = new Limits(8192, 8192, 16384, 24000);
  }

  public record Limits(
      @DefaultValue("8192") int documentChars,
      @DefaultValue("8192") int guideChars,
      @DefaultValue("16384") int knowledgeChars,
      @DefaultValue("24000") int contextChars) {
    public Limits {
      if (documentChars < 1
          || guideChars < 1
          || knowledgeChars < 1
          || contextChars < 4000
          || contextChars > 64000) throw new IllegalArgumentException("Invalid workspace limits");
    }
  }
}
