package dev.localworkspace.e2e;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Independent bidirectional JSON-RPC line client; no service or SDK dispatch is reused. */
final class StdioClient implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Process process;
  private final BufferedWriter input;
  private final Object writerLock = new Object();
  private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending =
      new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private final AtomicInteger rootsRequests = new AtomicInteger();
  private final AtomicReference<RootsReply> rootsReply = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<String> nonProtocol = new CopyOnWriteArrayList<>();
  private final Deque<String> transcript = new ConcurrentLinkedDeque<>();
  private final StringBuilder stderr = new StringBuilder();
  private final Thread outputThread, errorThread;
  private long stderrCharacters;

  record RootsReply(
      List<String> uris, String nextCursor, Integer rpcErrorCode, boolean withholdResponse) {
    RootsReply {
      if (uris != null) uris = List.copyOf(uris);
    }
  }

  private StdioClient(
      Path jar,
      Path processDirectory,
      Path fixedProject,
      List<Path> roots,
      String... serverArguments)
      throws IOException {
    String executable =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
            ? "java.exe"
            : "java";
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8",
                "-jar",
                jar.toAbsolutePath().toString()));
    if (fixedProject != null)
      command.add("--local-workspace.project-root=" + fixedProject.toAbsolutePath());
    command.addAll(List.of(serverArguments));
    var builder = new ProcessBuilder(command);
    if (processDirectory != null) builder.directory(processDirectory.toFile());
    setRoots(roots);
    process = builder.start();
    input = process.outputWriter(StandardCharsets.UTF_8);
    outputThread = new Thread(this::readOutput, "test-mcp-stdout");
    errorThread = new Thread(this::readError, "test-mcp-stderr");
    outputThread.setDaemon(true);
    errorThread.setDaemon(true);
    outputThread.start();
    errorThread.start();
  }

  private void readOutput() {
    try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        JsonNode node;
        try {
          node = JSON.readTree(line);
        } catch (IOException e) {
          nonProtocol.add(line);
          continue;
        }
        if (node == null || !node.isObject() || !node.path("jsonrpc").asText().equals("2.0")) {
          nonProtocol.add(line);
          continue;
        }
        if (node.has("method")) {
          trace("<- " + node.path("id") + " " + node.path("method").asText());
          if (node.has("id")) replyToServer(node);
        } else if (node.has("id")) {
          trace("<- response " + node.path("id"));
          var future = pending.get(node.get("id").toString());
          if (future != null) future.complete(node);
        }
      }
    } catch (IOException e) {
      if (!closed.get()) nonProtocol.add("stdout read failed: " + e.getMessage());
    } finally {
      failPending(new IOException("Server stdout closed: " + diagnostics()));
    }
  }

  private void replyToServer(JsonNode request) throws IOException {
    ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", request.get("id"));
    if (!request.path("method").asText().equals("roots/list")) {
      response.set(
          "error",
          JSON.valueToTree(Map.of("code", -32601, "message", "Unsupported test client method")));
    } else {
      rootsRequests.incrementAndGet();
      RootsReply reply = rootsReply.get();
      if (reply.withholdResponse()) return;
      if (reply.rpcErrorCode() != null) {
        response.set(
            "error",
            JSON.valueToTree(
                Map.of("code", reply.rpcErrorCode(), "message", "Test Roots failure")));
      } else {
        ObjectNode result = JSON.createObjectNode();
        result.set(
            "roots",
            JSON.valueToTree(
                reply.uris() == null
                    ? null
                    : reply.uris().stream()
                        .map(uri -> Map.of("uri", uri, "name", "test-root"))
                        .toList()));
        if (reply.nextCursor() != null) result.put("nextCursor", reply.nextCursor());
        response.set("result", result);
      }
    }
    send(response);
  }

  private void readError() {
    try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null)
        synchronized (stderr) {
          stderrCharacters += line.length() + 1;
          if (stderr.length() < 65536) stderr.append(line).append('\n');
        }
    } catch (IOException ignored) {
    }
  }

  static StdioClient start(Path jar, Path project, String... serverArguments) throws Exception {
    return initialized(new StdioClient(jar, null, project, List.of(), serverArguments), false);
  }

  static StdioClient startAuto(
      Path jar,
      Path processDirectory,
      boolean rootsSupported,
      List<Path> roots,
      String... serverArguments)
      throws Exception {
    return initialized(
        new StdioClient(jar, processDirectory, null, roots, serverArguments), rootsSupported);
  }

  private static StdioClient initialized(StdioClient client, boolean rootsSupported)
      throws Exception {
    try {
      JsonNode result =
          client.request(
              "initialize",
              Map.of(
                  "protocolVersion",
                  "2025-03-26",
                  "capabilities",
                  rootsSupported ? Map.of("roots", Map.of("listChanged", true)) : Map.of(),
                  "clientInfo",
                  Map.of("name", "independent-test-agent", "version", "1.0")));
      if (result.path("protocolVersion").asText().isBlank())
        throw new AssertionError("No negotiated MCP version");
      client.notification("notifications/initialized", Map.of());
      return client;
    } catch (Exception | AssertionError e) {
      client.close();
      throw e;
    }
  }

  void setRoots(List<Path> roots) {
    setRootsReply(
        new RootsReply(
            roots.stream().map(p -> p.toUri().toASCIIString()).toList(), null, null, false));
  }

  void setRootsReply(RootsReply reply) {
    rootsReply.set(Objects.requireNonNull(reply));
  }

  int rootsRequestCount() {
    return rootsRequests.get();
  }

  JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
    return request("tools/call", Map.of("name", name, "arguments", arguments));
  }

  JsonNode request(String method, Map<String, Object> params) throws Exception {
    if (closed.get()) throw new IOException("Client closed");
    long id = sequence.incrementAndGet();
    String key = Long.toString(id);
    var future = new CompletableFuture<JsonNode>();
    pending.put(key, future);
    try {
      ObjectNode node =
          JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
      node.set("params", JSON.valueToTree(params));
      send(node);
      JsonNode response = future.get(30, TimeUnit.SECONDS);
      if (response.has("error"))
        throw new AssertionError("JSON-RPC error: " + response.get("error"));
      return response.path("result");
    } catch (TimeoutException e) {
      close();
      throw new AssertionError("MCP request timed out: " + diagnostics(), e);
    } finally {
      pending.remove(key);
    }
  }

  void notification(String method, Map<String, Object> params) throws IOException {
    ObjectNode node = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
    node.set("params", JSON.valueToTree(params));
    send(node);
  }

  private void send(JsonNode node) throws IOException {
    synchronized (writerLock) {
      trace("-> " + node.path("id") + " " + node.path("method").asText("response"));
      input.write(JSON.writeValueAsString(node));
      input.newLine();
      input.flush();
    }
  }

  static JsonNode body(JsonNode result) throws IOException {
    return JSON.readTree(result.path("content").get(0).path("text").asText());
  }

  List<String> nonProtocolStdout() {
    return List.copyOf(nonProtocol);
  }

  long stderrCharacters() {
    synchronized (stderr) {
      return stderrCharacters;
    }
  }

  String diagnostics() {
    synchronized (stderr) {
      return String.join("\n", transcript) + "\n" + stderr;
    }
  }

  private void trace(String message) {
    transcript.addLast(message);
    while (transcript.size() > 40) transcript.pollFirst();
  }

  boolean alive() {
    return process.isAlive();
  }

  private void failPending(Throwable error) {
    pending.values().forEach(f -> f.completeExceptionally(error));
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    failPending(new IOException("Client closed"));
    try {
      synchronized (writerLock) {
        input.close();
      }
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      }
      outputThread.join(1000);
      errorThread.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    } catch (IOException e) {
      process.destroyForcibly();
    }
  }
}
