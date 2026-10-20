package dev.localworkspace.storage;

import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.ErrorCode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageContractTest {
  @TempDir Path root;

  @Test
  void rejectsTraversalAndReservedNames() {
    for (String name : List.of("../other", "CON", "workspace.", "a/b", "C:", ".", ".."))
      assertThatThrownBy(() -> new WorkspacePaths(root, name))
          .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    var paths = new WorkspacePaths(root, "LocalWorkspace");
    assertThatThrownBy(() -> paths.task("../x"))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    assertThatThrownBy(() -> paths.assertSafe(root.resolve("pom.xml")))
        .hasFieldOrPropertyWithValue("code", ErrorCode.PATH_NOT_ALLOWED);
  }

  @Test
  void readsAtMost256KiB() throws Exception {
    var p = new WorkspacePaths(root, "LocalWorkspace");
    var s = new AtomicDocumentStore(p);
    Files.createDirectory(p.workspaceRoot());
    Path file = p.workspaceRoot().resolve("large.md");
    Files.write(file, new byte[262145]);
    assertThatThrownBy(() -> s.read(file))
        .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
  }

  @Test
  void replacesWithoutDestroyingOldContent() throws Exception {
    var p = new WorkspacePaths(root, "LocalWorkspace");
    var s = new AtomicDocumentStore(p);
    s.directories(p.workspaceRoot());
    Path f = p.workspaceRoot().resolve("sample.md");
    byte[] before = Utf8.encode("原文😀");
    s.create(f, before);
    assertThatThrownBy(() -> s.replace(f, Utf8.encode("覆盖"), "wrong"))
        .hasFieldOrPropertyWithValue("code", ErrorCode.REVISION_CONFLICT);
    assertThat(s.read(f)).isEqualTo(before);
    s.replace(f, Utf8.encode("更新"), ContentRevision.sha256(before));
    assertThat(Utf8.decodeStrict(s.read(f))).isEqualTo("更新");
    assertThatThrownBy(() -> s.create(f, before))
        .hasFieldOrPropertyWithValue("code", ErrorCode.WORKSPACE_CONFLICT);
  }

  @Test
  void doesNotCleanUnownedDirectories() throws Exception {
    var p = new WorkspacePaths(root, "LocalWorkspace");
    var s = new AtomicDocumentStore(p);
    s.directories(p.workspaceRoot());
    Path owned = s.staging(p.workspaceRoot(), "task");
    Path foreign = Files.createDirectory(p.workspaceRoot().resolve(".lw-tmp-user"));
    Files.writeString(foreign.resolve("mine.txt"), "keep");
    s.cleanupOwnedStaging(p.workspaceRoot());
    assertThat(Files.exists(owned)).isFalse();
    assertThat(Files.readString(foreign.resolve("mine.txt"))).isEqualTo("keep");
  }

  @Test
  void rejectsReparsePoints() throws Exception {
    var p = new WorkspacePaths(root, "LocalWorkspace");
    var s = new AtomicDocumentStore(p);
    s.directories(p.workspaceRoot());
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path link = p.workspaceRoot().resolve("linked");
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      Process proc =
          new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
              .redirectErrorStream(true)
              .start();
      String output = new String(proc.getInputStream().readAllBytes());
      assertThat(proc.waitFor()).as(output).isZero();
    } else Files.createSymbolicLink(link, outside);
    try {
      assertThatThrownBy(() -> s.read(link.resolve("secret.md")))
          .hasFieldOrPropertyWithValue("code", ErrorCode.PATH_NOT_ALLOWED);
    } finally {
      Files.delete(link);
    }
  }

  @Test
  void utf8IsStrictAndHashIsContentBased() {
    assertThat(Utf8.decodeStrict(Utf8.encode("中文😀"))).isEqualTo("中文😀");
    assertThatThrownBy(() -> Utf8.decodeStrict(new byte[] {(byte) 0xff}))
        .hasFieldOrPropertyWithValue("code", ErrorCode.ENCODING_UNSUPPORTED);
    assertThat(ContentRevision.sha256(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void rejectsOversizedCreateAndReplacementBeforeCommitting() throws Exception {
    var p = new WorkspacePaths(root, "LocalWorkspace");
    var s = new AtomicDocumentStore(p);
    s.directories(p.workspaceRoot());
    Path file = p.workspaceRoot().resolve("saved.md");
    byte[] before = Utf8.encode("原文");
    s.create(file, before);
    byte[] tooLarge = new byte[262145];
    Throwable replacement =
        catchThrowable(() -> s.replace(file, tooLarge, ContentRevision.sha256(before)));
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(replacement).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    Path fresh = p.workspaceRoot().resolve("new.md");
    assertThatThrownBy(() -> s.create(fresh, tooLarge))
        .hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThat(Files.exists(fresh)).isFalse();
  }
}
