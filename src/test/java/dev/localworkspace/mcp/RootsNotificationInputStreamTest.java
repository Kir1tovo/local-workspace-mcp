package dev.localworkspace.mcp;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RootsNotificationInputStreamTest {
  final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
  static final String ROOTS =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{}}\n";

  @Test
  void skipsOnlyRootsNotificationsAndPreservesOtherFrames() throws Exception {
    String initialized = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
    String request =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"notifications/roots/list_changed\"}\n";
    String response =
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"text\":\"中文"
            + " notifications/roots/list_changed\"}}\n";
    try (var input = stream(ROOTS + initialized + ROOTS + request + response)) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo(initialized + request + response);
    }
  }

  @Test
  void supportsSmallReadsZeroLengthAndMalformedFrames() throws Exception {
    String unchanged = "not-json\n{\"method\":\"notifications/roots/list_changed\"}\n";
    try (var input = stream(ROOTS + unchanged)) {
      byte[] bytes = new byte[3];
      assertThat(input.read(bytes, 0, 0)).isZero();
      var output = new ByteArrayOutputStream();
      int count;
      while ((count = input.read(bytes, 0, bytes.length)) != -1) output.write(bytes, 0, count);
      assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(unchanged);
      assertThat(input.read()).isEqualTo(-1);
    }
  }

  InputStream stream(String frames) {
    return new RootsNotificationInputStream(
        new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)), mapper);
  }
}
