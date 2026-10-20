package dev.localworkspace.storage;

import java.nio.file.Path;
import java.util.List;

public interface DocumentStore {
  byte[] read(Path file);

  boolean exists(Path path);

  List<Path> list(Path path);

  void directories(Path path);

  void create(Path file, byte[] bytes);

  void replace(Path file, byte[] bytes, String expectedFileRevision);

  Path staging(Path parent, String kind);

  void publishDirectory(Path staging, Path target);

  void cleanupOwnedStaging(Path parent);
}
