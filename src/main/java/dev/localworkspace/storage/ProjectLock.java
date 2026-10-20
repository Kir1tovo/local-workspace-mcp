package dev.localworkspace.storage;

import dev.localworkspace.common.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;

public class ProjectLock {
  private final Path file;
  private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

  public ProjectLock(Path root) {
    try {
      String key = root.toRealPath().toString();
      if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))
        key = key.toLowerCase(Locale.ROOT);
      file =
          Path.of(
              System.getProperty("java.io.tmpdir"),
              "local-workspace-locks",
              ContentRevision.sha256(Utf8.encode(key)) + ".lock");
    } catch (IOException e) {
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Project root must exist", e);
    }
  }

  public Path lockPath() {
    return file;
  }

  public <T> T withLock(Supplier<T> action) {
    ReentrantLock mutex = LOCKS.computeIfAbsent(file, p -> new ReentrantLock());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean acquired = false;
    try {
      acquired = mutex.tryLock(5, TimeUnit.SECONDS);
      if (!acquired) throw busy();
      Files.createDirectories(file.getParent());
      try (var channel =
          FileChannel.open(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        while (System.nanoTime() < deadline) {
          FileLock held = null;
          try {
            held = channel.tryLock();
          } catch (OverlappingFileLockException ignored) {
          }
          if (held != null) {
            try {
              return action.get();
            } finally {
              held.release();
            }
          }
          Thread.sleep(25);
        }
      }
      throw busy();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy();
    } catch (IOException e) {
      throw new WorkspaceException(ErrorCode.IO_ERROR, "Cannot lock workspace", e);
    } finally {
      if (acquired) mutex.unlock();
    }
  }

  private WorkspaceException busy() {
    return new WorkspaceException(
        ErrorCode.WORKSPACE_BUSY, "Workspace busy; retry after current operation");
  }
}
