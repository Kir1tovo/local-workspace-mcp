package dev.localworkspace.common;

import java.util.*;

public record ApiResponse<T>(boolean ok, T data, ApiError error, List<String> warnings) {
  public ApiResponse {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public record ApiError(ErrorCode code, String message, Map<String, Object> details) {}

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null, List.of());
  }

  public static <T> ApiResponse<T> success(T data, List<String> warnings) {
    return new ApiResponse<>(true, data, null, warnings);
  }

  public static ApiResponse<Void> failure(WorkspaceException e) {
    return new ApiResponse<>(
        false, null, new ApiError(e.code, e.getMessage(), e.details), List.of());
  }
}
