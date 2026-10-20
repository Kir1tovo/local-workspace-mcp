package dev.localworkspace.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ignores only optional Roots change notifications; all other frames reach the SDK unchanged. */
final class RootsNotificationInputStream extends InputStream {
  private final BufferedReader reader;
  private final McpJsonMapper mapper;
  private byte[] current = new byte[0];
  private int position;

  RootsNotificationInputStream(InputStream input, McpJsonMapper mapper) {
    reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    this.mapper = mapper;
  }

  @Override
  public int read() throws IOException {
    return fill() ? current[position++] & 0xff : -1;
  }

  @Override
  public int read(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    if (length == 0) return 0;
    if (!fill()) return -1;
    int count = Math.min(length, current.length - position);
    System.arraycopy(current, position, target, offset, count);
    position += count;
    return count;
  }

  private boolean fill() throws IOException {
    while (position == current.length) {
      String line = reader.readLine();
      if (line == null) return false;
      if (isRootsNotification(line)) continue;
      current = (line + "\n").getBytes(StandardCharsets.UTF_8);
      position = 0;
    }
    return true;
  }

  private boolean isRootsNotification(String line) {
    try {
      Map<?, ?> frame = mapper.readValue(line, Map.class);
      return frame != null
          && "2.0".equals(frame.get("jsonrpc"))
          && !frame.containsKey("id")
          && "notifications/roots/list_changed".equals(frame.get("method"));
    } catch (IOException | IllegalArgumentException e) {
      // Preserve malformed frames for the SDK's normal protocol error handling.
      return false;
    }
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
