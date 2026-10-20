package dev.localworkspace.mcp;

import static dev.localworkspace.workspace.WorkspaceModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.project.WorkspaceRuntimeProvider;
import dev.localworkspace.workspace.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.*;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceTools {
  private final WorkspaceRuntimeProvider provider;
  private final ToolExecutor executor;

  public WorkspaceTools(WorkspaceRuntimeProvider provider, ToolExecutor e) {
    this.provider = provider;
    executor = e;
  }

  @McpTool(
      name = "workspace_init",
      description =
          "Resolve projectRoot using client Roots or the Agent's explicit project path, then"
              + " initialize its workspace and install a managed agent entry block. Idempotent;"
              + " preserves existing rules. target: agents (default), claude, both, none. Read"
              + " returned instructions, then task_list and context_load.")
  public CallToolResult initialize(
      McpSyncRequestContext context,
      @McpToolParam(required = false, description = "agents, claude, both, or none; default agents")
          String guidanceTarget,
      @McpToolParam(
              required = false,
              description =
                  "Absolute current project path; carry the returned canonical path on subsequent"
                      + " calls. Fixed mode must match configuration.")
          String projectRoot) {
    return executor.invoke(
        () -> {
          GuidanceTarget target;
          try {
            target =
                guidanceTarget == null
                    ? GuidanceTarget.AGENTS
                    : GuidanceTarget.valueOf(guidanceTarget.toUpperCase(java.util.Locale.ROOT));
          } catch (IllegalArgumentException e) {
            throw Checks.invalid("Unknown guidanceTarget");
          }
          var runtime = provider.resolve(context, projectRoot);
          return ApiResponse.success(
              runtime.workspace().initialize(target).withResolution(runtime.resolution().info()));
        });
  }

  @McpTool(
      name = "workspace_status",
      description =
          "Resolve and inspect the project and guidance installation. If PROJECT_REQUIRED or"
              + " PROJECT_AMBIGUOUS, supply projectRoot from the Agent's own workspace, never the"
              + " MCP process directory. Carry the returned path on related calls. Does not"
              + " initialize. If not initialized, call workspace_init before task operations.")
  public CallToolResult status(
      McpSyncRequestContext context,
      @McpToolParam(
              required = false,
              description =
                  "Absolute project path; optional with a unique client Root or fixed"
                      + " configuration")
          String projectRoot) {
    return executor.invoke(
        () -> {
          var runtime = provider.resolve(context, projectRoot);
          return ApiResponse.success(
              runtime.workspace().status().withResolution(runtime.resolution().info()));
        });
  }
}
