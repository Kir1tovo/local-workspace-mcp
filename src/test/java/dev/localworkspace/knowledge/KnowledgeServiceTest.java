package dev.localworkspace.knowledge;

import static dev.localworkspace.knowledge.KnowledgeModels.*;
import static dev.localworkspace.workspace.WorkspaceModels.GuidanceTarget.NONE;
import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.common.*;
import dev.localworkspace.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeServiceTest {
  @TempDir Path root;

  private WorkspaceFixture fixture() {
    var f = new WorkspaceFixture(root);
    f.workspace.initialize(NONE);
    return f;
  }

  private SaveKnowledgeRequest create(
      String title, List<String> tags, List<String> taskIds, String body) {
    return new SaveKnowledgeRequest(null, new KnowledgeInput(title, tags, taskIds, body), null);
  }

  @Test
  void requiresRevisionWhenReplacingKnowledge() {
    var f = fixture();
    assertThatCode(() -> f.knowledge.save(create("Redis", List.of(), List.of(), "old")))
        .doesNotThrowAnyException();
    var k = f.knowledge.save(create("Redis", List.of(), List.of(), "old"));
    assertThatThrownBy(
            () ->
                f.knowledge.save(
                    new SaveKnowledgeRequest(
                        k.id(), new KnowledgeInput("Redis", List.of(), List.of(), "new"), null)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_ARGUMENT);
    f.knowledge.save(
        new SaveKnowledgeRequest(
            k.id(), new KnowledgeInput("Redis", List.of(), List.of(), "new"), k.revision()));
    assertThatThrownBy(
            () ->
                f.knowledge.save(
                    new SaveKnowledgeRequest(
                        k.id(),
                        new KnowledgeInput("Redis", List.of(), List.of(), "overwrite"),
                        k.revision())))
        .hasFieldOrPropertyWithValue("code", ErrorCode.REVISION_CONFLICT);
    assertThat(f.knowledgeRepository.read(k.id()).body()).isEqualTo("new");
  }

  @Test
  void ranksTitleBeforeTagBeforeBody() {
    var f = fixture();
    var body = f.knowledge.save(create("缓存", List.of("other"), List.of(), "Redis 说明"));
    var tag = f.knowledge.save(create("缓存", List.of("redis"), List.of(), "标签匹配"));
    var title = f.knowledge.save(create("Redis 用法", List.of(), List.of(), "标题匹配"));
    assertThat(f.knowledge.search(new KnowledgeQuery("REDIS", null, null, null)))
        .extracting(KnowledgeHit::id)
        .containsExactly(title.id(), tag.id(), body.id());
  }

  @Test
  void requiresAllRequestedTags() {
    var f = fixture();
    var both = f.knowledge.save(create("两者", List.of("JAVA", "spring"), List.of(), "body"));
    f.knowledge.save(create("单个", List.of("java"), List.of(), "body"));
    assertThat(f.knowledge.search(new KnowledgeQuery(null, List.of("java", "SPRING"), null, null)))
        .extracting(KnowledgeHit::id)
        .containsExactly(both.id());
  }

  @Test
  void filtersByLinkedTask() {
    var f = fixture();
    var a = f.taskService.create(WorkspaceFixture.task("登录"));
    var b = f.taskService.create(WorkspaceFixture.task("支付"));
    var note = f.knowledge.save(create("登录知识", List.of(), List.of(a.taskId()), "body"));
    f.knowledge.save(create("支付知识", List.of(), List.of(b.taskId()), "body"));
    assertThat(f.knowledge.search(new KnowledgeQuery(null, null, a.taskId(), null)))
        .extracting(KnowledgeHit::id)
        .containsExactly(note.id());
    assertThat(f.tasks.read(a.taskId()).revision()).isEqualTo(a.revision());
  }

  @Test
  void returnsPreviewWithoutFullBody() {
    var f = fixture();
    f.knowledge.save(create("emoji", List.of(), List.of(), "😀".repeat(201) + "tail"));
    var hit = f.knowledge.search(new KnowledgeQuery(null, null, null, null)).get(0);
    assertThat(hit.bodyPreview()).isEqualTo("😀".repeat(200));
    assertThat(hit.preview()).isTrue();
  }

  @Test
  void rejectsUnknownLinkedTask() {
    var f = fixture();
    assertThatThrownBy(
            () ->
                f.knowledge.save(
                    create(
                        "missing",
                        List.of(),
                        List.of("task-00000000-0000-0000-0000-000000000000"),
                        "body")))
        .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
  }

  @Test
  void rejectsMalformedKnowledgeMetadata() throws Exception {
    var f = fixture();
    var k = f.knowledge.save(create("test", List.of(), List.of(), "body"));
    String bad =
        "---\nschemaVersion: 1\ndocumentType: knowledge\nid: "
            + k.id()
            + "\n"
            + "title: [bad]\n"
            + "tags: []\n"
            + "taskIds: []\n"
            + "createdAt: 2026-10-16T01:00:00Z\n"
            + "updatedAt: 2026-10-16T01:00:00Z\n"
            + "---\n"
            + "body";
    Files.writeString(f.paths.knowledge(k.id()), bad);
    assertThatThrownBy(() -> f.knowledge.search(new KnowledgeQuery(null, null, null, null)))
        .hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
    assertThat(Files.readString(f.paths.knowledge(k.id()))).isEqualTo(bad);
  }

  @Test
  void rejectsOversizedBodyAndMetadataWithoutChangingSavedRecord() {
    var f = fixture();
    var k = f.knowledge.save(create("limit", List.of(), List.of(), "old"));
    assertThatThrownBy(
            () ->
                f.knowledge.save(
                    new SaveKnowledgeRequest(
                        k.id(),
                        new KnowledgeInput("limit", List.of(), List.of(), "x".repeat(16385)),
                        k.revision())))
        .hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThatThrownBy(
            () ->
                f.knowledge.save(
                    new SaveKnowledgeRequest(
                        k.id(),
                        new KnowledgeInput("limit", List.of("x".repeat(262145)), List.of(), "new"),
                        k.revision())))
        .hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThat(f.knowledgeRepository.read(k.id()).body()).isEqualTo("old");
  }

  @Test
  void excessiveMetadataCannotReplaceReadableKnowledge() throws Exception {
    var f = fixture();
    var previous = f.knowledge.save(create("保留", List.of(), List.of(), "原有知识"));
    byte[] before = Files.readAllBytes(f.paths.knowledge(previous.id()));
    Throwable failure =
        catchThrowable(
            () ->
                f.knowledge.save(
                    new SaveKnowledgeRequest(
                        previous.id(),
                        new KnowledgeInput(
                            "过多标签", Collections.nCopies(4100, "tag"), List.of(), "新内容"),
                        previous.revision())));
    assertThat(Files.readAllBytes(f.paths.knowledge(previous.id()))).isEqualTo(before);
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
    assertThat(f.knowledge.search(new KnowledgeQuery(null, null, null, null)))
        .extracting(KnowledgeHit::id)
        .containsExactly(previous.id());
  }

  @Test
  void excessiveMetadataIsRejectedBeforeCreatingKnowledge() {
    var f = fixture();
    Throwable failure =
        catchThrowable(
            () ->
                f.knowledge.save(
                    create("过多标签", Collections.nCopies(4100, "tag"), List.of(), "正文")));
    assertThat(f.store.list(f.paths.workspaceRoot().resolve("knowledge"))).isEmpty();
    assertThat(failure).hasFieldOrPropertyWithValue("code", ErrorCode.SUMMARY_REQUIRED);
  }
}
