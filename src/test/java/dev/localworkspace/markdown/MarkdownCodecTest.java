package dev.localworkspace.markdown;

import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarkdownCodecTest {
  private final MarkdownCodec codec = new MarkdownCodec();

  @Test
  void roundTripsBodyWithMarkdownFences() {
    String body = "# 目标\n```text\n---\n```\n中文😀";
    byte[] bytes = codec.render(Map.of("schemaVersion", 1, "title", "中文"), body);
    assertThat(codec.parse(bytes).body()).isEqualTo(body);
    assertThat(codec.parse(bytes).metadata().get("title")).isEqualTo("中文");
  }

  @Test
  void rejectsDuplicateYamlKeys() {
    assertThatThrownBy(() -> codec.parse(Utf8.encode("---\ntitle: one\ntitle: two\n---\nbody")))
        .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
  }

  @Test
  void rejectsAliasesAndMissingMetadata() {
    for (String text :
        List.of(
            "---\nx: &a [one]\ny: *a\n---\nbody",
            "not markdown metadata",
            "---\n- item\n---\nbody"))
      assertThatThrownBy(() -> codec.parse(Utf8.encode(text)))
          .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
  }
}
