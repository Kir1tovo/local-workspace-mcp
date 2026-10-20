package dev.localworkspace.markdown;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.*;
import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import java.io.StringReader;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.*;

public class MarkdownCodec {
  private final ObjectMapper yaml =
      new ObjectMapper(
          new YAMLFactory()
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

  public MarkdownDocument parse(byte[] bytes) {
    String original = Utf8.decodeStrict(bytes);
    String normalized = original.startsWith("\ufeff") ? original.substring(1) : original;
    normalized = normalized.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) throw format("Missing YAML front matter");
    int end = normalized.indexOf("\n---\n", 4);
    if (end < 0 && normalized.endsWith("\n---")) end = normalized.length() - 4;
    if (end < 0) throw format("Missing front matter end marker");
    String header = normalized.substring(4, end);
    String body = end + 5 <= normalized.length() ? normalized.substring(end + 5) : "";
    try {
      int depth = 0, count = 0;
      for (Event event : new Yaml().parse(new StringReader(header))) {
        if (++count > 4096) throw format("Metadata too complex");
        if (event instanceof AliasEvent
            || (event instanceof NodeEvent node && node.getAnchor() != null))
          throw format("YAML anchors and aliases are not supported");
        if (event instanceof CollectionStartEvent && ++depth > 20)
          throw format("Metadata too deeply nested");
        if (event instanceof CollectionEndEvent) depth--;
      }
      Map<String, Object> metadata =
          yaml.readValue(header, new TypeReference<Map<String, Object>>() {});
      if (metadata == null || metadata.isEmpty())
        throw format("Metadata must be a nonempty mapping");
      return new MarkdownDocument(original, body, metadata, ContentRevision.sha256(bytes));
    } catch (WorkspaceException e) {
      throw e;
    } catch (Exception e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid YAML front matter", e);
    }
  }

  public byte[] render(Map<String, Object> metadata, String body) {
    try {
      byte[] bytes = Utf8.encode("---\n" + yaml.writeValueAsString(metadata) + "---\n" + body);
      // Apply the same parser limits before any caller can publish generated content.
      parse(bytes);
      return bytes;
    } catch (WorkspaceException e) {
      throw new WorkspaceException(
          ErrorCode.SUMMARY_REQUIRED,
          "Generated metadata exceeds supported limits; reduce tags and metadata, then retry",
          e);
    } catch (Exception e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Cannot encode Markdown metadata", e);
    }
  }

  private WorkspaceException format(String message) {
    return new WorkspaceException(ErrorCode.FORMAT_ERROR, message);
  }
}
