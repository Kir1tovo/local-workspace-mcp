package dev.localworkspace.storage;

import java.security.*;
import java.util.HexFormat;

public final class ContentRevision {
  private ContentRevision() {}

  public static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String task(byte[] a, byte[] b, byte[] c) {
    return sha256(Utf8.encode(sha256(a) + sha256(b) + sha256(c)));
  }
}
