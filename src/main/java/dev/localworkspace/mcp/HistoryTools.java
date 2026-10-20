package dev.localworkspace.mcp;

import static dev.localworkspace.history.HistoryModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.config.WorkspaceProperties;
import dev.localworkspace.history.*;
import dev.localworkspace.project.WorkspaceRuntimeProvider;
import dev.localworkspace.task.TaskModels.HandoffContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.*;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
public class HistoryTools {
  private final WorkspaceRuntimeProvider provider;
  private final ToolExecutor executor;
  private final ResponseEncoder encoder;
  private final WorkspaceProperties properties;

  public HistoryTools(
      WorkspaceRuntimeProvider provider,
      ToolExecutor e,
      ResponseEncoder encoder,
      WorkspaceProperties p) {
    this.provider = provider;
    executor = e;
    this.encoder = encoder;
    properties = p;
  }

  @McpTool(
      name = "handoff_save",
      description =
          "Save the latest handoff summary, decisions with reasons, cautions and verification."
              + " Automatically archive the previous three documents. First task_update STATUS with"
              + " current nextSteps, then use its expectedRevision here. On conflict context_load"
              + " and merge.")
  public CallToolResult save(
      McpSyncRequestContext context,
      @McpToolParam(required = true, description = "Task identifier") String taskId,
      @McpToolParam(required = true, description = "Latest handoff content") HandoffContent content,
      @McpToolParam(required = true, description = "Latest task revision") String expectedRevision,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(
        () ->
            ApiResponse.success(
                provider
                    .resolve(context, projectRoot)
                    .history()
                    .saveHandoff(taskId, content, expectedRevision)));
  }

  @McpTool(
      name = "history_read",
      description =
          "List checkpoint metadata for taskId, or read selected TASK/STATUS/HANDOFF documents with"
              + " checkpointId. Does not restore/overwrite current files. Response budget maxChars"
              + " 4000..64000, default24000; reduce document selection if exceeded.")
  public CallToolResult read(
      McpSyncRequestContext context,
      @McpToolParam(
              required = true,
              description = "Task, optional checkpointId/documents, offset/limit and maxChars")
          HistoryRequest request,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(
        () -> {
          var response =
              ApiResponse.success(provider.resolve(context, projectRoot).history().read(request));
          encoder.requireBudget(
              response,
              request.maxChars() == null ? properties.limits().contextChars() : request.maxChars());
          return response;
        });
  }
}
