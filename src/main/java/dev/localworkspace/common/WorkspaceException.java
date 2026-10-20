package dev.localworkspace.common;

import java.util.Map;

public class WorkspaceException extends RuntimeException {
  public final ErrorCode code;
  public final Map<String, Object> details;

  public WorkspaceException(ErrorCode code, String message) {
    this(code, message, Map.of());
  }

  public WorkspaceException(ErrorCode code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = Map.copyOf(details);
  }

  public WorkspaceException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.details = Map.of();
  }
}
