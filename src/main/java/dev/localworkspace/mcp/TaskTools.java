package dev.localworkspace.mcp;

import static dev.localworkspace.context.ContextModels.*;
import static dev.localworkspace.task.TaskModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.context.*;
import dev.localworkspace.project.WorkspaceRuntimeProvider;
import dev.localworkspace.task.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.*;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
public class TaskTools {
  private final WorkspaceRuntimeProvider provider;
  private final ToolExecutor executor;

  public TaskTools(WorkspaceRuntimeProvider provider, ToolExecutor e) {
    this.provider = provider;
    executor = e;
  }

  @McpTool(
      name = "task_create",
      description =
          "Create a separate task with goal, constraints, plan, acceptanceCriteria and nonempty"
              + " nextSteps. Initialize workspace first. Save returned taskId and revision.")
  public CallToolResult create(
      McpSyncRequestContext context,
      @McpToolParam(required = true, description = "Task content and nonempty nextSteps")
          CreateTaskRequest request,
      @McpToolParam(
              required = false,
              description =
                  "Canonical project path returned by workspace_status; required if Roots cannot"
                      + " select one project")
          String projectRoot) {
    return executor.invoke(
        () -> ApiResponse.success(provider.resolve(context, projectRoot).tasks().create(request)));
  }

  @McpTool(
      name = "task_list",
      description =
          "Find tasks by title/tags and state. Defaults exclude ARCHIVED. Pagination offset=0"
              + " limit=20 (max100). Select a taskId then context_load; never assume a global"
              + " current task.")
  public CallToolResult list(
      McpSyncRequestContext context,
      @McpToolParam(required = false, description = "Optional state, query, offset, limit")
          TaskQuery request,
      @McpToolParam(
              required = false,
              description =
                  "Canonical project path returned by workspace_status; never infer a global"
                      + " current project")
          String projectRoot) {
    return executor.invoke(
        () -> ApiResponse.success(provider.resolve(context, projectRoot).tasks().list(request)));
  }

  @McpTool(
      name = "context_load",
      description =
          "Restore task goal, constraints, current state and next steps across agents. Default"
              + " documents GUIDE,TASK,STATUS,HANDOFF; optional knowledgeIds, maxChars 4000..64000"
              + " (default24000). History is separate. Verify actual code before continuing;"
              + " STATUS.md owns next steps. Returns latest revision.")
  public CallToolResult context(
      McpSyncRequestContext context,
      @McpToolParam(
              required = true,
              description = "taskId, optional document selection, knowledgeIds and maxChars")
          ContextRequest request,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(() -> provider.resolve(context, projectRoot).context().load(request));
  }

  @McpTool(
      name = "task_update",
      description =
          "Replace exactly one TASK or STATUS document using the latest expectedRevision."
              + " ACTIVE/BLOCKED require nextSteps; BLOCKED requires blockers. checkpoint=true"
              + " saves an explicit milestone. On REVISION_CONFLICT call context_load and merge"
              + " before retry; never blindly overwrite.")
  public CallToolResult update(
      McpSyncRequestContext context,
      @McpToolParam(
              required = true,
              description =
                  "taskId, document TASK/STATUS, expectedRevision, selected task/status content,"
                      + " optional checkpoint")
          UpdateTaskRequest request,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(
        () -> ApiResponse.success(provider.resolve(context, projectRoot).tasks().update(request)));
  }
}
