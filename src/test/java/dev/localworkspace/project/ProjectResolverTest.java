package dev.localworkspace.project;

import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.ErrorCode;
import dev.localworkspace.common.WorkspaceException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectResolverTest {
  @Test
  void fixedFilesystemRootAcceptsItsOwnReturnedPath() {
    Path drive = temp.getRoot();
    var resolver = new ProjectResolver(drive);
    var initial = resolver.resolve(null, null);
    assertThat(resolver.resolve(initial.info().projectRoot(), null)).isEqualTo(initial);
  }

  @Test
  void fixedUncDirectoryKeepsLegacyFilesystemValidation() throws Exception {
    // Mock only the filesystem boundary; no SMB share is contacted by this unit test.
    Path unc = org.mockito.Mockito.mock(Path.class);
    org.mockito.Mockito.when(unc.toString()).thenReturn("\\\\server\\share\\project");
    org.mockito.Mockito.when(unc.toRealPath()).thenReturn(unc);
    try (var files = org.mockito.Mockito.mockStatic(Files.class)) {
      files.when(() -> Files.isDirectory(unc)).thenReturn(true);
      assertThat(new ProjectResolver(unc).resolve(null, null).projectRoot()).isSameAs(unc);
    }
  }

  @TempDir Path temp;
  ProjectResolver auto = new ProjectResolver(null);

  @Test
  void fixedSkipsRoots() throws Exception {
    var source =
        new ClientRootsSource() {
          public boolean supported() {
            throw new AssertionError("must not query capability");
          }

          public RootsSnapshot read() {
            throw new AssertionError("must not read roots");
          }
        };
    var result = new ProjectResolver(temp).resolve(temp.resolve(".").toString(), source);
    assertThat(result)
        .isEqualTo(
            new ProjectResolution(
                temp.toRealPath(), ProjectResolution.Mode.FIXED, ProjectResolution.Source.CONFIG));
    assertThat(result.info().projectRoot()).isEqualTo(temp.toRealPath().toString());
    assertThatThrownBy(() -> new ProjectResolver(temp.resolve("missing")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixedRejectsDifferentArgument() throws Exception {
    Path other = Files.createDirectory(temp.resolve("other"));
    error(
        () -> new ProjectResolver(temp).resolve(other.toString(), null),
        ErrorCode.PATH_NOT_ALLOWED);
  }

  @Test
  void uniqueRootResolvesAutomatically() throws Exception {
    assertThat(auto.resolve(null, rootsOf(temp)))
        .isEqualTo(
            new ProjectResolution(
                temp.toRealPath(),
                ProjectResolution.Mode.AUTO,
                ProjectResolution.Source.MCP_ROOTS));
  }

  @Test
  void emptyAndUnsupportedRequireArgument() throws Exception {
    for (ClientRootsSource source : List.of(source(false, null), roots())) {
      error(() -> auto.resolve(null, source), ErrorCode.PROJECT_REQUIRED);
      assertThat(auto.resolve(temp.toString(), source).source())
          .isEqualTo(ProjectResolution.Source.AGENT_ARGUMENT);
    }
  }

  @Test
  void argumentSelectsAllowedChild() throws Exception {
    Path child = Files.createDirectory(temp.resolve("child"));
    assertThat(auto.resolve(child.toString(), rootsOf(temp)).projectRoot())
        .isEqualTo(child.toRealPath());
  }

  @Test
  void ambiguousRootsDoNotSelectFirst() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    WorkspaceException e =
        (WorkspaceException) catchThrowable(() -> auto.resolve(null, rootsOf(b, a)));
    assertThat(e.code).isEqualTo(ErrorCode.PROJECT_AMBIGUOUS);
    assertThat(e.details.get("candidates"))
        .isEqualTo(List.of(a.toRealPath().toString(), b.toRealPath().toString()));
    assertThat(auto.resolve(a.toString(), rootsOf(b, a)).projectRoot()).isEqualTo(a.toRealPath());
  }

  @Test
  void failedOrIncompleteRootsDoNotUseArgument() {
    var failed =
        new ClientRootsSource() {
          public boolean supported() {
            return true;
          }

          public RootsSnapshot read() {
            throw new IllegalStateException("offline");
          }
        };
    for (ClientRootsSource s :
        List.of(
            failed,
            source(true, new RootsSnapshot(List.of(temp.toUri().toString()), "more")),
            source(true, null)))
      error(() -> auto.resolve(temp.toString(), s), ErrorCode.ROOTS_UNAVAILABLE);
  }

  @Test
  void invalidEntryDoesNotSelectRemainingRoot() {
    error(
        () -> auto.resolve(temp.toString(), roots(temp.toUri().toString(), "https://example.com")),
        ErrorCode.ROOTS_UNAVAILABLE);
  }

  @Test
  void deduplicatesEquivalentRoots() throws Exception {
    assertThat(
            auto.resolve(null, roots(temp.toUri().toString(), temp.resolve(".").toUri().toString()))
                .projectRoot())
        .isEqualTo(temp.toRealPath());
  }

  @Test
  void rejectsOverlappingDistinctRootsWithoutSelection() throws Exception {
    Path child = Files.createDirectory(temp.resolve("child"));
    error(() -> auto.resolve(null, rootsOf(temp, child)), ErrorCode.PROJECT_AMBIGUOUS);
  }

  @Test
  void rejectsStringPrefixEscape() throws Exception {
    Path a = Files.createDirectory(temp.resolve("repo"));
    Path b = Files.createDirectory(temp.resolve("repo-other"));
    error(() -> auto.resolve(b.toString(), rootsOf(a)), ErrorCode.PATH_NOT_ALLOWED);
  }

  @Test
  void rejectsJunctionEscape() throws Exception {
    Path a = Files.createDirectory(temp.resolve("repo"));
    Path b = Files.createDirectory(temp.resolve("outside"));
    Path link = a.resolve("link");
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      Process p =
          new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), b.toString())
              .redirectErrorStream(true)
              .start();
      String output = new String(p.getInputStream().readAllBytes());
      assertThat(p.waitFor()).as(output).isZero();
    } else Files.createSymbolicLink(link, b);
    try {
      error(() -> auto.resolve(link.toString(), rootsOf(a)), ErrorCode.PATH_NOT_ALLOWED);
    } finally {
      Files.delete(link);
    }
  }

  @Test
  void supportsEncodedChineseAndLocalhost() throws Exception {
    Path p = Files.createDirectory(temp.resolve("中文 空格%项目"));
    String uri = p.toUri().toASCIIString();
    assertThat(auto.resolve(null, roots(uri)).projectRoot()).isEqualTo(p.toRealPath());
    assertThat(
            auto.resolve(null, roots(uri.replace("file:///", "file://localhost/"))).projectRoot())
        .isEqualTo(p.toRealPath());
  }

  @Test
  void rejectsRelativeUncRootAndBadUris() throws Exception {
    for (String arg :
        List.of(
            "",
            " ",
            ".",
            "relative",
            "\\\\server\\share",
            "//server/share",
            temp.getRoot().toString(),
            temp.resolve("missing").toString()))
      error(() -> auto.resolve(arg, roots()), ErrorCode.INVALID_ARGUMENT);
    Path file = Files.writeString(temp.resolve("file"), "data");
    error(() -> auto.resolve(file.toString(), roots()), ErrorCode.INVALID_ARGUMENT);
    for (String uri :
        List.of(
            "file:relative",
            "relative",
            "https://example.com",
            "file://server/share",
            "file:////server/share",
            temp.toUri() + "?q=1",
            temp.toUri() + "#part",
            file.toUri().toString(),
            temp.resolve("missing").toUri().toString(),
            "file:///%ZZ"))
      error(() -> auto.resolve(temp.toString(), roots(uri)), ErrorCode.ROOTS_UNAVAILABLE);
    error(() -> auto.resolve(null, rootsOf(temp.getRoot())), ErrorCode.PROJECT_REQUIRED);
  }

  static void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call).hasFieldOrPropertyWithValue("code", code);
  }

  static ClientRootsSource rootsOf(Path... paths) {
    return roots(Arrays.stream(paths).map(p -> p.toUri().toString()).toArray(String[]::new));
  }

  static ClientRootsSource roots(String... uris) {
    return source(true, new RootsSnapshot(Arrays.asList(uris), null));
  }

  static ClientRootsSource source(boolean supported, RootsSnapshot snapshot) {
    return new ClientRootsSource() {
      public boolean supported() {
        return supported;
      }

      public RootsSnapshot read() {
        if (!supported) throw new AssertionError("unsupported roots read");
        return snapshot;
      }
    };
  }
}
