package dev.localworkspace.storage;

import dev.localworkspace.common.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class AtomicDocumentStore implements DocumentStore {
  private final WorkspacePaths paths;
  private static final String OWNER = "local-workspace-mcp:staging:v1";

  public AtomicDocumentStore(WorkspacePaths paths) {
    this.paths = paths;
  }

  public byte[] read(Path p) {
    paths.assertSafe(p);
    try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.size(p) > 262144)
        throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "File exceeds 256 KiB");
      byte[] b = in.readNBytes(262145);
      if (b.length > 262144)
        throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "File exceeds 256 KiB");
      return b;
    } catch (NoSuchFileException e) {
      throw new WorkspaceException(ErrorCode.NOT_FOUND, "Managed file not found", e);
    } catch (IOException e) {
      throw io(e);
    }
  }

  public boolean exists(Path p) {
    paths.assertSafe(p);
    return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
  }

  public List<Path> list(Path p) {
    paths.assertSafe(p);
    if (!exists(p)) return List.of();
    try (var stream = Files.list(p)) {
      return stream.sorted().toList();
    } catch (IOException e) {
      throw io(e);
    }
  }

  public void directories(Path p) {
    paths.assertSafe(p);
    try {
      Files.createDirectories(p);
      paths.assertSafe(p);
    } catch (IOException e) {
      throw io(e);
    }
  }

  public void create(Path p, byte[] b) {
    write(p, b, null);
  }

  public void replace(Path p, byte[] b, String r) {
    if (r == null)
      throw new WorkspaceException(
          ErrorCode.INVALID_ARGUMENT, "Expected file revision is required");
    write(p, b, r);
  }

  private void checkRevision(Path p, String expected) {
    if (expected == null) {
      if (exists(p))
        throw new WorkspaceException(ErrorCode.WORKSPACE_CONFLICT, "File already exists");
    } else if (!exists(p) || !ContentRevision.sha256(read(p)).equals(expected))
      throw new WorkspaceException(
          ErrorCode.REVISION_CONFLICT, "File changed; reload before saving");
  }

  private void write(Path p, byte[] b, String expected) {
    paths.assertSafe(p);
    if (b.length > 262144)
      throw new WorkspaceException(
          ErrorCode.SUMMARY_REQUIRED,
          "Managed file exceeds the 256 KiB limit; reduce content before saving");
    checkRevision(p, expected);
    Path temporary = null;
    try {
      temporary = Files.createTempFile(p.getParent(), ".lw-write-", ".tmp");
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(b);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      paths.assertSafe(p);
      checkRevision(p, expected);
      Files.move(temporary, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw io(e);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }

  public Path staging(Path parent, String kind) {
    if (!Set.of("task", "checkpoint").contains(kind))
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Invalid staging kind");
    Path result = parent.resolve(".lw-stage-" + kind + "-" + UUID.randomUUID());
    directories(result);
    create(result.resolve(".lw-owner"), Utf8.encode(OWNER));
    return result;
  }

  public void publishDirectory(Path stage, Path target) {
    paths.assertSafe(stage);
    paths.assertSafe(target);
    if (exists(target))
      throw new WorkspaceException(ErrorCode.WORKSPACE_CONFLICT, "Target directory already exists");
    try {
      Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw io(e);
    }
  }

  public void cleanupOwnedStaging(Path parent) {
    for (Path p : list(parent)) {
      if (!p.getFileName().toString().matches("\\.lw-stage-(task|checkpoint)-[0-9a-f-]{36}"))
        continue;
      Path marker = p.resolve(".lw-owner");
      if (!exists(marker) || !Utf8.decodeStrict(read(marker)).equals(OWNER)) continue;
      try {
        Files.walkFileTree(
            p,
            new SimpleFileVisitor<>() {
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                paths.assertSafe(dir);
                return FileVisitResult.CONTINUE;
              }

              public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                  throws IOException {
                paths.assertSafe(file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
              }

              public FileVisitResult postVisitDirectory(Path dir, IOException error)
                  throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        throw io(e);
      }
    }
  }

  private WorkspaceException io(IOException e) {
    return new WorkspaceException(ErrorCode.IO_ERROR, "Managed file operation failed", e);
  }
}
