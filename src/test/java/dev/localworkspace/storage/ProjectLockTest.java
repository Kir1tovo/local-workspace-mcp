package dev.localworkspace.storage;

import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.ErrorCode;
import java.nio.channels.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLockTest {
  @TempDir Path root;

  @Test
  void sameProjectUsesSameLock() {
    var a = new ProjectLock(root);
    var b = new ProjectLock(root.resolve("."));
    assertThat(a.lockPath()).isEqualTo(b.lockPath());
    assertThat(a.withLock(() -> "ok")).isEqualTo("ok");
  }

  @Test
  void timesOutAfterFiveSeconds() throws Exception {
    var lock = new ProjectLock(root);
    Files.createDirectories(lock.lockPath().getParent());
    try (var channel =
            FileChannel.open(lock.lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      long before = System.nanoTime();
      assertThatThrownBy(() -> lock.withLock(() -> "wrong"))
          .hasFieldOrPropertyWithValue("code", ErrorCode.WORKSPACE_BUSY);
      assertThat((System.nanoTime() - before) / 1_000_000).isBetween(4800L, 7500L);
    }
  }
}
