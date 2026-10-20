package dev.localworkspace.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.localworkspace.config.WorkspaceProperties.Limits;
import dev.localworkspace.project.ProjectResolution.ResolutionInfo;
import java.util.*;

public final class WorkspaceModels {
  private WorkspaceModels() {}

  public enum GuidanceTarget {
    AGENTS,
    CLAUDE,
    BOTH,
    NONE
  }

  public record WorkspaceInfo(
      UUID workspaceId,
      String projectRoot,
      String workspaceRoot,
      int schemaVersion,
      boolean initialized,
      Limits limits) {}

  public record WorkspaceStatus(
      WorkspaceInfo workspace,
      Map<String, Boolean> guidanceInstalled,
      @JsonInclude(JsonInclude.Include.NON_NULL) ResolutionInfo resolution) {
    public WorkspaceStatus(WorkspaceInfo workspace, Map<String, Boolean> guidanceInstalled) {
      this(workspace, guidanceInstalled, null);
    }

    public WorkspaceStatus withResolution(ResolutionInfo info) {
      return new WorkspaceStatus(workspace, guidanceInstalled, info);
    }
  }

  public record InitResult(
      WorkspaceInfo workspace,
      List<String> changedPaths,
      String instructions,
      @JsonInclude(JsonInclude.Include.NON_NULL) ResolutionInfo resolution) {
    public InitResult(WorkspaceInfo workspace, List<String> changedPaths, String instructions) {
      this(workspace, changedPaths, instructions, null);
    }

    public InitResult withResolution(ResolutionInfo info) {
      return new InitResult(workspace, changedPaths, instructions, info);
    }
  }
}
