package dev.localworkspace.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class ResponseEncoder {
  private final ObjectMapper json;

  public ResponseEncoder(ObjectMapper json) {
    this.json = json;
  }

  public String encode(ApiResponse<?> response) {
    try {
      return json.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      throw new WorkspaceException(ErrorCode.IO_ERROR, "Cannot encode tool response", e);
    }
  }

  public int characters(ApiResponse<?> response) {
    String text = encode(response);
    return text.codePointCount(0, text.length());
  }

  public void requireBudget(ApiResponse<?> response, int maxChars) {
    Checks.range(maxChars, 24000, 4000, 64000, "maxChars");
    int actual = characters(response);
    if (actual > maxChars)
      throw new WorkspaceException(
          ErrorCode.BUDGET_EXCEEDED,
          "Response exceeds character budget; request fewer documents or a larger maxChars",
          Map.of("requiredChars", actual, "maxChars", maxChars));
  }
}
