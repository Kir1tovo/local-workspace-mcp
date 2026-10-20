package dev.localworkspace.mcp;

import dev.localworkspace.common.*;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);
  private final ResponseEncoder encoder;

  public ToolExecutor(ResponseEncoder encoder) {
    this.encoder = encoder;
  }

  public CallToolResult invoke(Supplier<? extends ApiResponse<?>> operation) {
    ApiResponse<?> response;
    try {
      response = operation.get();
    } catch (WorkspaceException e) {
      if (e.code == ErrorCode.ROOTS_UNAVAILABLE && e.getCause() != null)
        LOG.warn("Client Roots resolution failed", e.getCause());
      response = ApiResponse.failure(e);
    } catch (IllegalArgumentException e) {
      response = ApiResponse.failure(Checks.invalid("Invalid tool arguments"));
    } catch (Exception e) {
      LOG.error("Unexpected workspace operation failure", e);
      response =
          ApiResponse.failure(
              new WorkspaceException(
                  ErrorCode.IO_ERROR,
                  "Workspace operation failed; see server stderr for diagnostics"));
    }
    return new CallToolResult(encoder.encode(response), !response.ok());
  }
}
