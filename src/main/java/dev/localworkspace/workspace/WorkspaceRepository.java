package dev.localworkspace.workspace;

import static dev.localworkspace.workspace.WorkspaceModels.*;

import com.fasterxml.jackson.databind.*;
import dev.localworkspace.common.*;
import dev.localworkspace.config.*;
import dev.localworkspace.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class WorkspaceRepository {
  private final WorkspacePaths paths;
  private final DocumentStore store;
  private final WorkspaceProperties properties;
  private final ObjectMapper json;
  private final Clock clock;

  public WorkspaceRepository(
      WorkspacePaths p, DocumentStore s, WorkspaceProperties c, ObjectMapper j, Clock clock) {
    paths = p;
    store = s;
    properties = c;
    json = j;
    this.clock = clock;
  }

  public WorkspaceInfo info() {
    Path marker = paths.workspaceRoot().resolve("workspace.json");
    if (!store.exists(marker))
      return new WorkspaceInfo(
          null,
          paths.projectRoot().toString(),
          paths.workspaceRoot().toString(),
          1,
          false,
          properties.limits());
    try {
      JsonNode node = json.readTree(store.read(marker));
      if (node == null
          || !node.isObject()
          || !node.path("schemaVersion").isInt()
          || node.path("schemaVersion").intValue() != 1
          || !node.path("workspaceId").isTextual()
          || !node.path("createdAt").isTextual()) throw new IllegalArgumentException();
      UUID id = UUID.fromString(node.path("workspaceId").textValue());
      Instant.parse(node.path("createdAt").textValue());
      boolean ready =
          store.exists(paths.workspaceRoot().resolve("AGENT_GUIDE.md"))
              && store.exists(paths.workspaceRoot().resolve("tasks"))
              && store.exists(paths.workspaceRoot().resolve("knowledge"));
      if (ready)
        ready =
            Files.isDirectory(paths.workspaceRoot().resolve("tasks"))
                && Files.isDirectory(paths.workspaceRoot().resolve("knowledge"));
      return new WorkspaceInfo(
          id,
          paths.projectRoot().toString(),
          paths.workspaceRoot().toString(),
          1,
          ready,
          properties.limits());
    } catch (IOException | IllegalArgumentException e) {
      throw new WorkspaceException(ErrorCode.FORMAT_ERROR, "Invalid workspace metadata", e);
    }
  }

  public WorkspaceInfo requireInitialized() {
    var info = info();
    if (!info.initialized())
      throw new WorkspaceException(
          ErrorCode.NOT_INITIALIZED, "Call workspace_init before using workspace tools");
    return info;
  }

  public WorkspaceInfo initialize() {
    Path dir = paths.workspaceRoot();
    Path marker = dir.resolve("workspace.json");
    if (store.exists(dir) && !Files.isDirectory(dir))
      throw new WorkspaceException(
          ErrorCode.WORKSPACE_CONFLICT, "Workspace path is not a directory");
    if (store.exists(dir) && !store.exists(marker) && !store.list(dir).isEmpty())
      throw new WorkspaceException(
          ErrorCode.WORKSPACE_CONFLICT,
          "Existing nonempty directory is not a workspace; choose another directory-name");
    store.directories(dir);
    if (!store.exists(marker))
      try {
        store.create(
            marker,
            json.writeValueAsBytes(
                Map.of(
                    "schemaVersion",
                    1,
                    "workspaceId",
                    UUID.randomUUID().toString(),
                    "createdAt",
                    clock.instant().toString())));
      } catch (IOException e) {
        throw new WorkspaceException(ErrorCode.IO_ERROR, "Cannot encode workspace metadata", e);
      }
    info();
    store.directories(dir.resolve("tasks"));
    store.directories(dir.resolve("knowledge"));
    Path guide = dir.resolve("AGENT_GUIDE.md");
    if (!store.exists(guide))
      try (var in = getClass().getResourceAsStream("/templates/AGENT_GUIDE.md")) {
        if (in == null) throw new IOException("Missing packaged guide");
        byte[] bytes = in.readAllBytes();
        String text = Utf8.decodeStrict(bytes);
        if (text.codePointCount(0, text.length()) > properties.limits().guideChars())
          throw new WorkspaceException(
              ErrorCode.SUMMARY_REQUIRED, "Guide exceeds configured limit");
        store.create(guide, bytes);
      } catch (IOException e) {
        throw new WorkspaceException(ErrorCode.IO_ERROR, "Cannot install workspace guide", e);
      }
    store.cleanupOwnedStaging(dir.resolve("tasks"));
    return requireInitialized();
  }

  public String instructions() {
    return Utf8.decodeStrict(store.read(paths.workspaceRoot().resolve("AGENT_GUIDE.md")));
  }
}
