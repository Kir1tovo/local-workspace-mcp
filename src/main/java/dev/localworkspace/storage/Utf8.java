package dev.localworkspace.storage;

import dev.localworkspace.common.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

public final class Utf8 {
  private Utf8() {}

  public static String decodeStrict(byte[] b) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(b))
          .toString();
    } catch (CharacterCodingException e) {
      throw new WorkspaceException(
          ErrorCode.ENCODING_UNSUPPORTED, "Existing file must be UTF-8", e);
    }
  }

  public static byte[] encode(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
