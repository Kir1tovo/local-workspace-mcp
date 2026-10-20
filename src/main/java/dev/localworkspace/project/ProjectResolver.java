package dev.localworkspace.project;

import dev.localworkspace.common.ErrorCode;
import dev.localworkspace.common.WorkspaceException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Resolves exactly one project for a request, without retaining client state or writing files. */
public final class ProjectResolver {
  private final Path configuredRoot;

  public ProjectResolver(Path configuredRoot) {
    if (configuredRoot == null) {
      this.configuredRoot = null;
      return;
    }
    try {
      if (configuredRoot.toString().isBlank())
        throw new IllegalArgumentException("project-root must not be blank");
      this.configuredRoot = existingDirectory(configuredRoot);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Configured project-root must be an existing directory", e);
    }
  }

  public ProjectResolution resolve(String projectRoot, ClientRootsSource roots) {
    if (configuredRoot != null) {
      if (projectRoot != null && !argument(projectRoot, true).equals(configuredRoot))
        throw new WorkspaceException(
            ErrorCode.PATH_NOT_ALLOWED, "projectRoot differs from the fixed configured project");
      return new ProjectResolution(
          configuredRoot, ProjectResolution.Mode.FIXED, ProjectResolution.Source.CONFIG);
    }
    List<Path> candidates = readRoots(roots);
    if (projectRoot != null) {
      Path selected = argument(projectRoot, false);
      if (!candidates.isEmpty() && candidates.stream().noneMatch(selected::startsWith))
        throw new WorkspaceException(
            ErrorCode.PATH_NOT_ALLOWED, "projectRoot must be inside a client Root");
      return new ProjectResolution(
          selected, ProjectResolution.Mode.AUTO, ProjectResolution.Source.AGENT_ARGUMENT);
    }
    if (candidates.isEmpty() || (candidates.size() == 1 && candidates.get(0).getParent() == null))
      throw new WorkspaceException(
          ErrorCode.PROJECT_REQUIRED,
          "Provide projectRoot from the Agent's current project workspace; do not use the MCP"
              + " process directory");
    if (candidates.size() > 1)
      throw new WorkspaceException(
          ErrorCode.PROJECT_AMBIGUOUS,
          "Multiple client Roots: provide the intended projectRoot",
          Map.of("candidates", candidates.stream().map(Path::toString).toList()));
    return new ProjectResolution(
        candidates.get(0), ProjectResolution.Mode.AUTO, ProjectResolution.Source.MCP_ROOTS);
  }

  private List<Path> readRoots(ClientRootsSource roots) {
    try {
      if (roots == null || !roots.supported()) return List.of();
      RootsSnapshot snapshot = roots.read();
      if (snapshot == null || snapshot.uris() == null)
        throw new IllegalArgumentException("missing Roots list");
      if (snapshot.nextCursor() != null && !snapshot.nextCursor().isEmpty())
        throw new IllegalArgumentException(
            "incomplete Roots list; provide a complete list without nextCursor");
      Set<Path> paths = new HashSet<>();
      for (String value : snapshot.uris()) paths.add(rootUri(value));
      return paths.stream().sorted(Comparator.comparing(Path::toString)).toList();
    } catch (WorkspaceException e) {
      throw e;
    } catch (Exception e) {
      throw new WorkspaceException(
          ErrorCode.ROOTS_UNAVAILABLE,
          "Client Roots unavailable or invalid; retry after restoring a complete local directory"
              + " list",
          e);
    }
  }

  private Path rootUri(String value) throws Exception {
    URI uri = new URI(Objects.requireNonNull(value));
    if (!uri.isAbsolute()
        || uri.isOpaque()
        || !"file".equalsIgnoreCase(uri.getScheme())
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("Root must be a local file directory URI");
    String authority = uri.getRawAuthority();
    if (authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost"))
      throw new IllegalArgumentException("Remote Roots are unsupported");
    String path = uri.getPath();
    if (path == null || path.startsWith("//") || path.indexOf('\\') >= 0)
      throw new IllegalArgumentException("UNC Roots are unsupported");
    URI local = new URI("file", null, path, null);
    Path parsed = Path.of(local);
    if (!parsed.isAbsolute()) throw new IllegalArgumentException("Root must be absolute");
    return directory(parsed);
  }

  private Path argument(String value, boolean fixed) {
    try {
      if (value.isBlank()) throw new IllegalArgumentException("blank projectRoot");
      Path path = Path.of(value);
      if (!path.isAbsolute()) throw new IllegalArgumentException("relative projectRoot");
      Path real = fixed ? existingDirectory(path) : directory(path);
      if (!fixed && real.getParent() == null)
        throw new IllegalArgumentException("filesystem root is not a project");
      return real;
    } catch (Exception e) {
      throw new WorkspaceException(
          ErrorCode.INVALID_ARGUMENT,
          fixed
              ? "projectRoot must be an existing absolute directory matching the fixed"
                  + " configuration"
              : "projectRoot must be an existing absolute local project directory, excluding"
                  + " filesystem roots and UNC paths",
          e);
    }
  }

  private static Path directory(Path path) throws Exception {
    String text = path.toString().replace('\\', '/');
    if (text.startsWith("//")) throw new IllegalArgumentException("UNC directory");
    Path real = existingDirectory(path);
    if (real.toString().replace('\\', '/').startsWith("//"))
      throw new IllegalArgumentException("not a local directory");
    return real;
  }

  private static Path existingDirectory(Path path) throws java.io.IOException {
    Path real = path.toRealPath();
    if (!Files.isDirectory(real)) throw new IllegalArgumentException("not a directory");
    return real;
  }
}
