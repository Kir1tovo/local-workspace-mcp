package dev.localworkspace.context;

import static dev.localworkspace.context.ContextModels.*;
import static dev.localworkspace.task.TaskModels.*;
import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.NONE;
import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.history.HistoryModels.*;
import dev.localworkspace.knowledge.KnowledgeModels.*;
import dev.localworkspace.support.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ContextServiceTest {
  @TempDir Path root;

  private WorkspaceFixture fixture() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    return f;
  }

  private ContextRequest request(String id) {
    return new ContextRequest(id, null, null, null);
  }

  private String text(ApiResponse<ContextData> result) {
    return result.data().documents().stream()
        .map(ContextDocument::text)
        .collect(Collectors.joining("\n"));
  }

  private CreateTaskRequest longTask() {
    return new CreateTaskRequest(
        new TaskContent(
            "长任务", "x".repeat(5000), List.of(), List.of("重要约束"), List.of(), List.of(), List.of()),
        List.of("下一步"));
  }

  @Test
  void restoresGoalConstraintsAndNextSteps() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatCode(() -> f.context.load(request(t.taskId()))).doesNotThrowAnyException();
    var result = f.context.load(request(t.taskId()));
    assertThat(text(result)).contains("实现登录", "使用 JWT", "实现接口");
    assertThat(result.data().authoritativeNextStepsDocument()).isEqualTo("STATUS.md");
    assertThat(result.data().revision()).isEqualTo(t.revision());
    assertThat(result.data().documents())
        .extracting(ContextDocument::kind)
        .containsExactly("GUIDE", "TASK", "STATUS", "HANDOFF");
  }

  @Test
  void excludesHistoryUnlessExplicitlyRequested() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var h =
        f.history.saveHandoff(
            t.taskId(),
            new HandoffContent("旧交接独有内容", List.of(), List.of(), List.of(), List.of()),
            t.revision());
    f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), h.revision());
    assertThat(text(f.context.load(request(t.taskId())))).doesNotContain("旧交接独有内容");
  }

  @Test
  void loadsOnlySelectedKnowledge() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var a =
        f.knowledge.save(
            new SaveKnowledgeRequest(null, new KnowledgeInput("A", null, null, "共享A独有"), null));
    f.knowledge.save(
        new SaveKnowledgeRequest(null, new KnowledgeInput("B", null, null, "共享B独有"), null));
    var result = f.context.load(new ContextRequest(t.taskId(), null, List.of(a.id()), null));
    assertThat(text(result)).contains("共享A独有").doesNotContain("共享B独有");
    assertThat(result.data().documents().stream().filter(d -> d.kind().equals("KNOWLEDGE")))
        .hasSize(1);
  }

  @Test
  void countsEmojiAsCodePoints() {
    var f = fixture();
    var t =
        f.taskService.create(
            new CreateTaskRequest(
                new TaskContent("😀", "😀".repeat(100), null, null, null, null, null),
                List.of("执行")));
    var result = f.context.load(request(t.taskId()));
    String json = f.encoder.encode(result);
    assertThat(result.data().totalChars()).isEqualTo(json.codePointCount(0, json.length()));
    assertThat(result.data().totalChars()).isLessThan(json.length());
  }

  @Test
  void rejectsBudgetBelow4000OrAbove64000() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    for (int n : List.of(3999, 64001))
      assertThatThrownBy(() -> f.context.load(new ContextRequest(t.taskId(), null, null, n)))
          .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
  }

  @Test
  void doesNotSilentlyTruncateOverBudgetContext() {
    var f = fixture();
    var t = f.taskService.create(longTask());
    assertThatThrownBy(() -> f.context.load(new ContextRequest(t.taskId(), null, null, 4000)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.BUDGET_EXCEEDED);
    assertThat(text(f.context.load(new ContextRequest(t.taskId(), null, null, 64000))))
        .contains("x".repeat(5000), "重要约束");
  }

  @Test
  void warnsWhenHandoffUsesOlderStatusRevision() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision());
    var before = f.context.load(request(t.taskId()));
    assertThat(before.warnings()).isEmpty();
    f.taskService.update(
        WorkspaceFixture.update(
            f.tasks.read(t.taskId()),
            WorkspaceFixture.status(TaskState.ACTIVE, List.of("更新后的下一步"))));
    var after = f.context.load(request(t.taskId()));
    assertThat(after.warnings()).isNotEmpty();
    assertThat(text(after)).contains("更新后的下一步");
  }

  @Test
  void limitsHistoryResponsesToo() {
    var f = fixture();
    var t = f.taskService.create(longTask());
    var handoff = f.history.saveHandoff(t.taskId(), WorkspaceFixture.handoff(), t.revision());
    var result =
        f.history.read(
            new HistoryRequest(t.taskId(), handoff.checkpointId(), null, 4000, null, null));
    assertThatThrownBy(() -> f.encoder.requireBudget(ApiResponse.success(result), 4000))
        .hasFieldOrPropertyWithValue("code", ErrorCode.BUDGET_EXCEEDED);
  }

  @Test
  void rejectsOversizedStateWithoutChangingTask() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    var state =
        new StatusContent(
            TaskState.ACTIVE, "x".repeat(8193), null, null, List.of("下一步"), null, null);
    assertThatThrownBy(() -> f.taskService.update(WorkspaceFixture.update(t, state)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThat(f.tasks.read(t.taskId()).revision()).isEqualTo(t.revision());
  }

  @Test
  void rejectsUnknownKnowledgeAndEmptySelection() {
    var f = fixture();
    var t = f.taskService.create(WorkspaceFixture.task("登录"));
    assertThatThrownBy(() -> f.context.load(new ContextRequest(t.taskId(), List.of(), null, null)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    assertThatThrownBy(
            () ->
                f.context.load(
                    new ContextRequest(t.taskId(), null, List.of("knowledge-missing"), null)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
  }
}
