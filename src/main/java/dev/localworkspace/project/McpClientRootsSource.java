package dev.localworkspace.project;

import dev.localworkspace.common.ErrorCode;
import dev.localworkspace.common.WorkspaceException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Objects;
import org.springaicommunity.mcp.context.McpSyncRequestContext;

public final class McpClientRootsSource implements ClientRootsSource {
  private final McpSyncRequestContext context;

  public McpClientRootsSource(McpSyncRequestContext context) {
    this.context = context;
  }

  public boolean supported() {
    return context != null && context.rootsEnabled();
  }

  public RootsSnapshot read() {
    try {
      // roots() automatically follows every cursor in SDK 0.18.3. Request one page so the
      // resolver can reject incomplete lists before another request or any project selection.
      McpSchema.ListRootsResult result =
          Objects.requireNonNull(context.exchange().listRoots((String) null));
      return new RootsSnapshot(
          Objects.requireNonNull(result.roots()).stream()
              .map(root -> Objects.requireNonNull(root.uri()))
              .toList(),
          result.nextCursor());
    } catch (Exception e) {
      throw new WorkspaceException(
          ErrorCode.ROOTS_UNAVAILABLE,
          "Cannot read client Roots; retry when the client can supply a complete list",
          e);
    }
  }
}
