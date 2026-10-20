package dev.localworkspace.project;

import org.springaicommunity.mcp.context.McpSyncRequestContext;

public final class WorkspaceRuntimeProvider {
  private final ProjectResolver resolver;
  private final WorkspaceRuntimeFactory factory;

  public WorkspaceRuntimeProvider(ProjectResolver resolver, WorkspaceRuntimeFactory factory) {
    this.resolver = resolver;
    this.factory = factory;
  }

  public WorkspaceRuntime resolve(McpSyncRequestContext context, String projectRoot) {
    return factory.create(resolver.resolve(projectRoot, new McpClientRootsSource(context)));
  }
}
