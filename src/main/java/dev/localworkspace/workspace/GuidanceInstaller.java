package dev.localworkspace.workspace;

import static dev.localworkspace.workspace.WorkspaceModels.*;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import java.nio.file.Path;
import java.util.*;

public class GuidanceInstaller {
  private static final String BEGIN = "<!-- LocalWorkspace:begin -->",
      END = "<!-- LocalWorkspace:end -->";
  private final WorkspacePaths paths;
  private final DocumentStore store;

  public GuidanceInstaller(WorkspacePaths p, DocumentStore s) {
    paths = p;
    store = s;
  }

  public List<Path> install(GuidanceTarget target) {
    List<Path> files = new ArrayList<>();
    if (target == GuidanceTarget.AGENTS || target == GuidanceTarget.BOTH)
      files.add(
          store.exists(paths.entry("AGENTS.override.md"))
              ? paths.entry("AGENTS.override.md")
              : paths.entry("AGENTS.md"));
    if (target == GuidanceTarget.CLAUDE || target == GuidanceTarget.BOTH)
      files.add(paths.entry("CLAUDE.md"));
    List<Path> changed = new ArrayList<>();
    for (Path file : files) {
      byte[] old = store.exists(file) ? store.read(file) : null;
      String original = old == null ? "" : Utf8.decodeStrict(old);
      String newline = original.contains("\r\n") ? "\r\n" : "\n";
      String directory = paths.workspaceRoot().getFileName().toString();
      String block =
          String.join(
              newline,
              BEGIN,
              "本项目的 Agent 工作区位于 " + directory + "/。",
              "开始或继续工作时，通过工作区 MCP 检查初始化状态、查找任务并加载上下文。",
              "使用客户端 Roots 或显式 projectRoot 定位；缺失或多项目时从 Agent 自身工作区补路径，不猜 MCP 启动目录。",
              "后续相关调用携带返回的真实 projectRoot；Roots 查询失败先修复客户端，固定模式尊重配置目录。",
              "相关任务有多个且无法确定归属时，向用户确认任务，不自行猜测。",
              "阶段完成时保存状态；交接前保存关键决策、相关文件、验证结果和下一步。",
              "公共流程见 " + directory + "/AGENT_GUIDE.md。",
              END);
      int start = original.indexOf(BEGIN), end = original.indexOf(END);
      String replacement;
      if (start < 0 && end < 0)
        replacement =
            original
                + (original.isEmpty() || original.endsWith("\n") ? "" : newline)
                + block
                + newline;
      else {
        if (start < 0
            || end < start
            || start != original.lastIndexOf(BEGIN)
            || end != original.lastIndexOf(END))
          throw new WorkspaceException(
              ErrorCode.WORKSPACE_CONFLICT,
              "Malformed or duplicate LocalWorkspace guidance markers");
        replacement = original.substring(0, start) + block + original.substring(end + END.length());
      }
      byte[] bytes = Utf8.encode(replacement);
      if (Arrays.equals(old, bytes)) continue;
      if (old == null) store.create(file, bytes);
      else store.replace(file, bytes, ContentRevision.sha256(old));
      changed.add(file);
    }
    return List.copyOf(changed);
  }

  public Map<String, Boolean> status() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (String name : List.of("AGENTS.md", "AGENTS.override.md", "CLAUDE.md")) {
      boolean installed = false;
      try {
        Path p = paths.entry(name);
        if (store.exists(p)) {
          String t = Utf8.decodeStrict(store.read(p));
          int a = t.indexOf(BEGIN), b = t.indexOf(END);
          installed = a >= 0 && b > a && a == t.lastIndexOf(BEGIN) && b == t.lastIndexOf(END);
        }
      } catch (WorkspaceException ignored) {
      }
      result.put(name, installed);
    }
    return Collections.unmodifiableMap(result);
  }
}
