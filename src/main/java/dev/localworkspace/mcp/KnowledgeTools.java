package dev.localworkspace.mcp;

import static dev.localworkspace.knowledge.KnowledgeModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.knowledge.*;
import dev.localworkspace.project.WorkspaceRuntimeProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.*;
import org.springaicommunity.mcp.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTools {
  private final WorkspaceRuntimeProvider provider;
  private final ToolExecutor executor;

  public KnowledgeTools(WorkspaceRuntimeProvider provider, ToolExecutor e) {
    this.provider = provider;
    executor = e;
  }

  @McpTool(
      name = "knowledge_save",
      description =
          "Create or replace shared Markdown knowledge. New: omit knowledgeId/expectedRevision."
              + " Update: supply both with latest knowledge revision. input has"
              + " title,tags,taskIds,body (max16384 characters). Task links must exist; never"
              + " changes task revisions.")
  public CallToolResult save(
      McpSyncRequestContext context,
      @McpToolParam(
              required = true,
              description = "Knowledge identifier, input and optional expectedRevision")
          SaveKnowledgeRequest request,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(
        () ->
            ApiResponse.success(provider.resolve(context, projectRoot).knowledge().save(request)));
  }

  @McpTool(
      name = "knowledge_search",
      description =
          "Search shared knowledge by case-insensitive title, tags or body; multiple tags are AND."
              + " Optional taskId filter; limit default10 max20. Results contain 200-character"
              + " previews only; use returned IDs with context_load for full content.")
  public CallToolResult search(
      McpSyncRequestContext context,
      @McpToolParam(required = false, description = "Optional query, tags, taskId and limit")
          KnowledgeQuery request,
      @McpToolParam(
              required = false,
              description = "Canonical project path returned by workspace_status")
          String projectRoot) {
    return executor.invoke(
        () ->
            ApiResponse.success(
                provider.resolve(context, projectRoot).knowledge().search(request)));
  }
}
