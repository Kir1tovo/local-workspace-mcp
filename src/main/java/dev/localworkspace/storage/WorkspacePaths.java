package dev.localworkspace.storage;

import dev.localworkspace.common.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class WorkspacePaths {
  private final Path root;
  private final String name;
  private static final Set<String> ENTRIES = Set.of("AGENTS.md", "AGENTS.override.md", "CLAUDE.md");

  public WorkspacePaths(Path root, String name) {
    validateDirectoryName(name);
    if (root == null)
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Project root is required");
    try {
      this.root = root.toRealPath();
    } catch (IOException e) {
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Project root must exist", e);
    }
    if (!Files.isDirectory(this.root))
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Project root must be a directory");
    this.name = name;
  }

  public static void validateDirectoryName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || name.equals(".")
        || name.equals("..")
        || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
        || name.endsWith(".")
        || name.endsWith(" ")
        || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Invalid workspace directory name");
  }

  public static String validId(String id) {
    if (id == null || !id.matches("[a-z][a-z0-9-]{1,99}"))
      throw new WorkspaceException(ErrorCode.INVALID_ARGUMENT, "Invalid object ID");
    return id;
  }

  public Path projectRoot() {
    return root;
  }

  public Path workspaceRoot() {
    return root.resolve(name);
  }

  public Path entry(String name) {
    if (!ENTRIES.contains(name))
      throw new WorkspaceException(ErrorCode.PATH_NOT_ALLOWED, "Unsupported guidance entry");
    return root.resolve(name);
  }

  public Path task(String id) {
    return workspaceRoot().resolve("tasks").resolve(validId(id));
  }

  public Path knowledge(String id) {
    return workspaceRoot().resolve("knowledge").resolve(validId(id) + ".md");
  }

  public Path checkpoint(String task, String checkpoint) {
    return task(task).resolve("archive").resolve(validId(checkpoint));
  }

  public void assertSafe(Path path) {
    Path p = path.toAbsolutePath().normalize();
    if (!p.startsWith(workspaceRoot())
        && !(root.equals(p.getParent()) && ENTRIES.contains(p.getFileName().toString())))
      throw new WorkspaceException(ErrorCode.PATH_NOT_ALLOWED, "Path is outside managed workspace");
    Path current = root;
    for (Path part : root.relativize(p)) {
      current = current.resolve(part);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        try {
          var attr =
              Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attr.isSymbolicLink() || attr.isOther() || !current.toRealPath().equals(current))
            throw new WorkspaceException(
                ErrorCode.PATH_NOT_ALLOWED, "Links and reparse points are not allowed");
        } catch (IOException e) {
          throw new WorkspaceException(ErrorCode.PATH_NOT_ALLOWED, "Cannot verify managed path", e);
        }
      }
    }
  }
}
