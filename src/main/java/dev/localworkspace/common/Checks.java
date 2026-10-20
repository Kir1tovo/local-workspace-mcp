package dev.localworkspace.common;

import java.util.*;

public final class Checks {
  private Checks() {}

  public static String text(String value, String name) {
    if (value == null || value.isBlank()) throw invalid(name + " is required");
    return value;
  }

  public static List<String> strings(List<String> values) {
    if (values == null) return List.of();
    for (String value : values) text(value, "List item");
    return List.copyOf(values);
  }

  public static int range(Integer value, int fallback, int min, int max, String name) {
    int n = value == null ? fallback : value;
    if (n < min || n > max) throw invalid(name + " must be between " + min + " and " + max);
    return n;
  }

  public static void size(String value, int max) {
    if (value.codePointCount(0, value.length()) > max)
      throw new WorkspaceException(
          ErrorCode.SUMMARY_REQUIRED,
          "Document exceeds " + max + " characters; summarize and retry");
  }

  public static String bullets(List<String> values) {
    var list = strings(values);
    return list.isEmpty()
        ? "- 无\n"
        : String.join("\n", list.stream().map(s -> "- " + s.replace("\n", "\n  ")).toList()) + "\n";
  }

  public static WorkspaceException invalid(String message) {
    return new WorkspaceException(ErrorCode.INVALID_ARGUMENT, message);
  }

  public static String revision(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw invalid("expectedRevision must be the revision returned by the latest read");
    return value;
  }
}
