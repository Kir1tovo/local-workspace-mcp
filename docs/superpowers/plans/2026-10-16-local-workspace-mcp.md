# Local Workspace MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建可让不同 Agent 自动接入、保存和恢复本地任务状态的工作区 MCP，并通过独立 STDIO 客户端验证交接。

**Architecture:** 单个 Java 进程绑定一个使用者项目，通过 Spring AI 提供同步 STDIO 工具。Markdown 保存业务事实，文件锁、内容哈希与原子替换保护写入；服务按工作区、任务、交接、知识和上下文分工，底层 Repository 不重复获取服务已经持有的锁。

**Tech Stack:** Java 17、Maven、Spring Boot 3.5.16、Spring AI 1.1.8、Jackson YAML、JUnit 5 / AssertJ。

**Spec:** [已确认的设计](C:/individual-event/Local-Workspace/docs/superpowers/specs/2026-10-16-local-workspace-mcp-design.md)。执行者必须同时阅读设计和本计划。

**Status:** 用户已确认 Native 顺序执行；八个任务已完成，独立审查的3项重要问题已修复并通过73项最终验收。完整记录见 `docs/superpowers/reviews/2026-10-16-local-workspace-mcp-review.md`。

## Global Constraints

- 开发目录为 `C:\individual-event\Local-Workspace`；实施阶段继续在该目录工作。
- Java 17、Maven、Spring Boot 3.5.16、Spring AI 1.1.8。
- 使用 `spring-ai-starter-mcp-server` 的同步 STDIO 服务；不启动 HTTP 服务。
- 启动时必须设置 `local-workspace.project-root`；`local-workspace.directory-name` 默认 `LocalWorkspace`。
- Markdown 是业务事实来源；JSON 只保存控制信息，不配置模型 API Key、SQLite、向量数据库或后台模型总结。
- 保留项目已有文档；入口只更新 `<!-- LocalWorkspace:begin -->` 到 `<!-- LocalWorkspace:end -->` 的受管区块。
- 当前任务文档、状态和交接每个 8,192 字符；公共指南 8,192 字符；知识正文 16,384 字符。
- 上下文及历史读取结果默认预算 24,000 字符，可选 4,000 至 64,000 字符；按 Unicode code point 计数。
- 单个输入文件字节硬上限 256 KiB；项目锁等待上限 5 秒。
- 一次修改一个当前文档；任务修订标识涵盖三个当前文件，不静默覆盖旧版本。
- 日志写 stderr，stdout 仅用于 MCP 协议；不执行使用者项目命令，不读取私有聊天历史。
- 测试只操作测试临时项目，不能使用开发目录或真实业务项目作为初始化样例。

## Review Focus

1. Windows 路径中的空格、中文、设备名、尾随点及 junction 不得导致错误项目定位或目录越界：Task 1 / 8。
2. 现有入口包含 UTF-8 BOM、CRLF、重复或残缺标记时，原文仍应保留，失败可重试：Task 2。
3. 人工编辑正文、重复 YAML 字段或异常元数据时，不能忽略修改或覆盖损坏文件：Task 3 / 5。
4. 同一时刻连续更新、emoji、多文档超预算时，版本和陈旧交接提示仍可靠，预算计数不能按 UTF-16 长度计算：Task 4 / 6。
5. JAR 子进程的 stderr 大量输出、客户端重连和读写竞争不能造成协议污染或无限等待：Task 8。

## 文件与职责

以下路径均相对于开发目录，对应已实现的文件与职责。

```text
pom.xml                         Maven、Boot、Spring AI BOM 与测试插件
.gitignore                      target/、本地 IDE 文件和日志
src/main/java/dev/localworkspace/
  LocalWorkspaceApplication.java
  config/WorkspaceProperties.java
  config/WorkspaceConfiguration.java
  common/ErrorCode.java
  common/WorkspaceException.java
  common/ApiResponse.java
  common/ResponseEncoder.java
  storage/WorkspacePaths.java
  storage/DocumentStore.java
  storage/AtomicDocumentStore.java
  storage/ProjectLock.java
  storage/ContentRevision.java
  storage/Utf8.java
  workspace/WorkspaceModels.java
  workspace/WorkspaceRepository.java
  workspace/GuidanceInstaller.java
  workspace/WorkspaceService.java
  markdown/MarkdownDocument.java
  markdown/MarkdownCodec.java
  task/TaskModels.java
  task/TaskRepository.java
  task/CheckpointWriter.java
  task/TaskService.java
  history/HistoryModels.java
  history/CheckpointRepository.java
  history/HistoryService.java
  knowledge/KnowledgeModels.java
  knowledge/KnowledgeRepository.java
  knowledge/KnowledgeService.java
  context/ContextModels.java
  context/ContextService.java
  mcp/ToolExecutor.java
  mcp/WorkspaceTools.java
  mcp/TaskTools.java
  mcp/HistoryTools.java
  mcp/KnowledgeTools.java
src/main/resources/
  application.yml
  logback-spring.xml
  templates/AGENT_GUIDE.md
src/test/java/dev/localworkspace/
  storage/StorageContractTest.java
  storage/ProjectLockTest.java
  workspace/WorkspaceServiceTest.java
  markdown/MarkdownCodecTest.java
  task/TaskServiceTest.java
  history/HistoryServiceTest.java
  knowledge/KnowledgeServiceTest.java
  context/ContextServiceTest.java
  mcp/ToolContractTest.java
  e2e/StdioClient.java
  e2e/McpStdioIT.java
  support/WorkspaceFixture.java
  support/FailingDocumentStore.java
README.md
examples/mcp-stdio.json
examples/codex-config.toml
```

`WorkspaceFixture` 只生成临时项目、固定时钟和合法请求；`FailingDocumentStore` 包装真实存储，在指定写入点抛出 IO 错误，用于验证失败后的用户可见行为。

## 共享数据契约

各功能模型使用 Java records，集中放在相应 `*Models.java` 中。元数据时间使用 UTC `Instant`，序列化为 ISO-8601 字符串；注入 `Clock` 使时间测试可重复。

- `GuidanceTarget`：`AGENTS`、`CLAUDE`、`BOTH`、`NONE`；工具接受小写名称。
- `TaskState`：`ACTIVE`、`BLOCKED`、`COMPLETED`、`ARCHIVED`。
- `DocumentKind`：`GUIDE`、`TASK`、`STATUS`、`HANDOFF`；任务更新只允许 `TASK` 或 `STATUS`。
- `TaskContent`：`title: String`、`goal: String`、`scope / constraints / plan / acceptanceCriteria / tags: List<String>`。
- `StatusContent`：`state: TaskState`、`current: String`、`completed / blockers / nextSteps / relatedFiles / verificationResults: List<String>`。
- `HandoffContent`：`summary: String`、`decisions: List<Decision>`、`cautions / relatedFiles / verificationResults: List<String>`；`Decision` 包含 `decision` 与 `reason` 两个字符串。
- `CreateTaskRequest`：`task: TaskContent`、`nextSteps: List<String>`。
- `UpdateTaskRequest`：`taskId: String`、`document: DocumentKind`、`expectedRevision: String`、`task: TaskContent`、`status: StatusContent`、`checkpoint: boolean`；正文对象只能提供被选中的一个。
- `TaskSnapshot`：`taskId`、`title`、`state`、`revision`、`updatedAt` 和 `Map<DocumentKind, MarkdownDocument> documents`，必须是不可变值。
- `MarkdownDocument`：完整 Markdown `text`、正文 `body`、`Map<String,Object> metadata`、原始内容哈希 `fileRevision`。
- `ApiResponse<T>`：`ok: boolean`、`data: T`、`error: ApiError`、`warnings: List<String>`；成功与失败分别只填写 data 或 error，禁止同时存在。
- `ApiError`：`code: ErrorCode`、`message: String`、`details: Map<String,Object>`。错误码集合采用设计第 9 节的完整列表，不新造模糊的成功状态。

Task 1 定义存储与错误类型；Task 2 定义工作区模型；Task 3 定义任务与 Markdown 模型；Task 4 / 5 / 6 分别定义历史、知识和上下文模型。其他模型只包含对应任务下面列出的请求与结果，不先建设通用实体框架。

### Task 1：可测试的文件边界、原子写入与项目锁

**Files:** 创建构建文件、应用入口、`WorkspaceProperties`、`common/ErrorCode.java`、`common/WorkspaceException.java`、全部 `storage/` 文件；创建 `StorageContractTest`、`ProjectLockTest`。

**Interfaces:**
- `WorkspacePaths(Path projectRoot, String directoryName)` 提供 `projectRoot()`、`workspaceRoot()`、`entry(String name)`、`task(String id)`、`knowledge(String id)`、`checkpoint(String taskId, String checkpointId)` 和 `assertSafe(Path path)`。
- `DocumentStore` 提供 `read(Path): byte[]`、`exists(Path): boolean`、`list(Path): List<Path>`、`directories(Path): void`、`create(Path, byte[]): void`、`replace(Path, byte[], String expectedFileRevision): void`、`staging(Path parent, String kind): Path`、`publishDirectory(Path staging, Path target): void`、`cleanupOwnedStaging(Path parent): void`。
- `ContentRevision.sha256(byte[]): String`；`ContentRevision.task(byte[] task, byte[] status, byte[] handoff): String` 按固定顺序拼接三个 64 位十六进制哈希，再以 UTF-8 求 SHA-256。
- `ProjectLock.withLock(Supplier<T>): T`；`Utf8.decodeStrict(byte[]): String`、`Utf8.encode(String): byte[]`。所有 IO 错误转为有原因链的 `WorkspaceException`。

- [x] **Step 1 — 创建最小可测试构建。** POM 固定 Boot 3.5.16、AI BOM 1.1.8、Java 17、UTF-8、compiler `parameters=true`；依赖 MCP server starter、Jackson YAML 和 Boot test starter；配置 Surefire 运行 `*Test`，Failsafe 在 `verify` 运行 `*IT`。入口不初始化使用者项目。此步骤不添加模型供应商、HTTP starter 或其他无关依赖。
- [x] **Step 2 — 写失败测试。** `rejectsTraversalAndReservedNames`、`rejectsReparsePoints`、`readsAtMost256KiB`、`replacesWithoutDestroyingOldContent`、`doesNotCleanUnownedDirectories`、`sameProjectUsesSameLock`、`timesOutAfterFiveSeconds`。覆盖目录名 `CON`、`workspace.`、`../other`；临时路径包含中文和空格。Windows junction 测试通过 `ProcessBuilder` 的独立参数创建测试 junction，清理时只移除链接本身。

```java
assertThatThrownBy(() -> store.read(oversizedFile)).hasFieldOrPropertyWithValue("code", ErrorCode.FORMAT_ERROR);
assertThat(Files.readAllBytes(target)).isEqualTo(originalBytes); // 修订冲突或替换失败后
assertThat(ContentRevision.sha256(originalBytes)).hasSize(64);
```

- [x] **Step 3 — 运行失败测试。** `mvn -Dtest=StorageContractTest,ProjectLockTest test`；预期因尚无存储实现或断言失败而失败，不能将依赖下载失败视为正确的 red。
- [x] **Step 4 — 实现并验证。** 使用真实路径约束、NOFOLLOW 检查、严格 UTF-8、256 KiB 有界读取、同目录临时文件与 `ATOMIC_MOVE`；创建不覆盖目标。锁位于系统临时目录的 `local-workspace-locks/<project-path-hash>.lock`，进程内互斥后再持有文件锁；所有读写均在服务层锁定。暂存目录必须同时具备本工具前缀和有效 ownership 标识才允许清理。运行 Step 3 命令，预期全部通过。
- [x] **Step 5 — 提交本阶段文件。** 只暂存本任务 Files 中的文件，提交 `feat: add guarded workspace storage and process locks`。

### Task 2：工作区初始化与自动入口安装

**Files:** 创建全部 `workspace/` 文件和 `templates/AGENT_GUIDE.md`；创建 `WorkspaceServiceTest`、`support/WorkspaceFixture.java`、`support/FailingDocumentStore.java`。

**Interfaces:**
- `WorkspaceRepository.requireInitialized(): WorkspaceInfo`、`initialize(): WorkspaceInfo`；`WorkspaceInfo` 包含 ID、真实路径、schemaVersion=1、初始化状态和限制。磁盘 `workspace.json` 只保存 schemaVersion、workspaceId、createdAt。
- `GuidanceInstaller.install(GuidanceTarget): List<Path>`、`status(): Map<String,Boolean>`。
- `WorkspaceService.initialize(GuidanceTarget): InitResult`、`status(): WorkspaceStatus`。`InitResult` 包含工作区信息、变更相对路径和使用规则；`WorkspaceStatus` 额外包含各入口安装状态。

- [x] **Step 1 — 写失败测试。** `initializationIsIdempotent`、`preservesExistingGuideAndRootTask`、`preservesBomAndCrLfOutsideManagedBlock`、`usesExistingAgentsOverride`、`rejectsMalformedMarkersAndInvalidUtf8`、`refusesForeignNonemptyWorkspace`、`retriesAfterPartialInitialization`。明确覆盖 AGENTS / CLAUDE / BOTH / NONE 和自定义目录名。

```java
assertThat(Utf8.decodeStrict(agentsAfterSecondInit)).containsOnlyOnce("<!-- LocalWorkspace:begin -->");
assertThat(agentsAfterSecondInit).startsWith(originalPrefixBytes).endsWith(originalSuffixBytes);
assertThatThrownBy(() -> service.initialize(AGENTS)).hasFieldOrPropertyWithValue("code", ErrorCode.PARTIAL_INITIALIZATION);
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=WorkspaceServiceTest test`；预期缺失初始化实现或对应断言失败。
- [x] **Step 3 — 实现初始化与重试。** 在空目录中先原子创建 workspace.json 声明归属，再补齐 tasks/、knowledge/ 和指南；requireInitialized 验证这些必要项就绪，部分创建后的重试只补缺失项。根入口最后安装，使用现有文件哈希进行替换；按设计原样生成入口文案。只有受管区块可修改；残缺或重复标记、未知非空目录不改写。使用失败存储模拟创建指南失败及根入口替换失败，保持 workspace.json、用户文件与已存在任务，再次初始化补完。`NONE` 不创建任何根规则文件。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=StorageContractTest,ProjectLockTest,WorkspaceServiceTest test`；检查初始化只产生指南与实际需要的入口，不预创建知识或任务。
- [x] **Step 5 — 提交。** 提交 `feat: initialize workspaces and install agent guidance safely`。

### Task 3：任务文档、状态规则与修订冲突

**Files:** 创建 `markdown/`、`task/` 全部文件；创建 `MarkdownCodecTest`、`TaskServiceTest`；扩展 `WorkspaceFixture`。

**Interfaces:**
- `MarkdownCodec.parse(byte[]): MarkdownDocument`、`render(Map<String,Object> metadata, String body): byte[]`；禁止 YAML 重复键、别名和类型不符的元数据，不启用任意 Java 类型反序列化。
- `TaskRepository.create(CreateTaskRequest): TaskSnapshot`、`read(String taskId): TaskSnapshot`、`list(): List<TaskSnapshot>`、`replace(TaskSnapshot expected, DocumentKind kind, byte[] replacement): TaskSnapshot`；Repository 不获取项目锁。
- `CheckpointWriter.save(TaskSnapshot snapshot, String reason): String`：端口在本任务定义，实际快照实现由 Task 4 提供；本任务测试在 checkpoint=false 时断言不调用该端口。
- `TaskService.create(CreateTaskRequest): TaskSnapshot`、`update(UpdateTaskRequest): TaskSnapshot`、`list(TaskQuery): TaskPage`。`TaskQuery` 包含 state、query、offset、limit；`TaskPage` 包含 items、offset、limit、total。

- [x] **Step 1 — 写失败测试。** `createsThreeDocumentsForOneTask`、`doesNotMixTwoTasks`、`rejectsStaleTaskRevisionAfterAnyDocumentChanges`、`requiresNextStepsForActiveAndBlocked`、`requiresBlockerForBlocked`、`hidesArchivedByDefault`、`roundTripsBodyWithMarkdownFences`、`rejectsDuplicateYamlKeys`。手工只改 handoff 正文后，用旧 revision 更新 STATUS 也必须冲突。

```java
assertThat(created.state()).isEqualTo(TaskState.ACTIVE);
assertThat(snapshot.documents().keySet()).containsExactlyInAnyOrder(TASK, STATUS, HANDOFF);
assertThatThrownBy(() -> service.update(staleRequest)).hasFieldOrPropertyWithValue("code", ErrorCode.REVISION_CONFLICT);
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=MarkdownCodecTest,TaskServiceTest test`。
- [x] **Step 3 — 实现完整快照替换。** taskId 使用 `task-` 加随机 UUID；标题不参与路径。初始交接明确注明尚无记录。说明 / 状态各自结构化渲染，不能提供两个正文对象；任务修订号覆盖三个原始文件。ACTIVE / BLOCKED 的 nextSteps 必须非空，BLOCKED 的 blockers 必须非空。已有文本格式损坏时拒绝写入。分页默认 20、上限 100、offset 非负，按更新时间倒序且以 ID 作稳定次序；归档仅改变状态，不移动任务目录。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=MarkdownCodecTest,TaskServiceTest test`；同时运行 `mvn test` 检查前置契约不回退。
- [x] **Step 5 — 提交。** 提交 `feat: persist isolated tasks with optimistic revisions`。

### Task 4：交接、里程碑快照与历史读取

**Files:** 创建 `history/` 全部文件、`HistoryServiceTest`；在任务更新中接入 `CheckpointWriter`，扩展支持测试。

**Interfaces:**
- `CheckpointRepository implements CheckpointWriter`；补充 `list(String taskId, int offset, int limit): CheckpointPage`、`read(String taskId, String checkpointId, List<DocumentKind> documents): HistoryData`。
- `HistoryService.saveHandoff(String taskId, HandoffContent content, String expectedRevision): HandoffResult`、`read(HistoryRequest): HistoryData`。`HistoryRequest` 包含 taskId、checkpointId、documents、maxChars、offset、limit；`HandoffResult` 返回新任务 revision 和 checkpointId。
- `HistoryData` 分别携带分页摘要或已选检查点文档，不把全部历史隐式加载。`checkpoint.json` 包含 ID、时间、原因、旧任务 revision 和三个文件哈希。

- [x] **Step 1 — 写失败测试。** `archivesExactPreHandoffBytes`、`ordinaryUpdateCreatesNoCheckpoint`、`milestoneUpdateCreatesOneCheckpoint`、`failedSnapshotLeavesCurrentDocumentsUntouched`、`failedReplacementReportsCreatedCheckpoint`、`checkpointsRemainDistinctWithFixedClock`。

```java
assertThat(checkpointTaskBytes).isEqualTo(beforeTaskBytes);
assertThat(checkpointStatusBytes).isEqualTo(beforeStatusBytes);
assertThat(checkpointHandoffBytes).isEqualTo(beforeHandoffBytes);
assertThat(newRevision).isNotEqualTo(oldRevision);
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=HistoryServiceTest test`。
- [x] **Step 3 — 实现快照发布。** checkpointId 使用时间加随机 UUID，不依赖时钟唯一。先完整发布旧三文档快照，再替换选中的当前文档；快照失败就不替换。交接 front matter 保存 `basedOnStatusRevision`，用于检测同一时间戳下的陈旧交接。任务更新 checkpoint=true 时使用真实 CheckpointRepository。历史列表默认 20、上限 100，指定检查点时只读选中文档；禁止 GUIDE 作为历史快照文档。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=TaskServiceTest,HistoryServiceTest test`；失败存储用例确认没有损坏用户当前文档，且错误细节中包含已成功生成的 checkpointId。
- [x] **Step 5 — 提交。** 提交 `feat: save handoffs and query immutable task checkpoints`。

### Task 5：共享知识保存与确定性检索

**Files:** 创建 `knowledge/` 全部文件和 `KnowledgeServiceTest`。

**Interfaces:**
- `KnowledgeInput` 包含 title、tags、taskIds、body；`SaveKnowledgeRequest` 包含可空 knowledgeId、input、可空 expectedRevision；已有 ID 修改时必须提交原哈希。
- `KnowledgeRepository.read(String id): KnowledgeRecord`、`list(): List<KnowledgeRecord>`、`save(SaveKnowledgeRequest): KnowledgeRecord`；记录包含 ID、标题、标签、关联任务、正文、更新时间和 revision。
- `KnowledgeService.save(SaveKnowledgeRequest): KnowledgeRecord`、`search(KnowledgeQuery): List<KnowledgeHit>`。查询包含 query、tags、taskId、limit；命中包含 ID、标题、标签、最多 200 字符预览、preview=true 和 revision。

- [x] **Step 1 — 写失败测试。** `requiresRevisionWhenReplacingKnowledge`、`ranksTitleBeforeTagBeforeBody`、`requiresAllRequestedTags`、`filtersByLinkedTask`、`returnsPreviewWithoutFullBody`、`rejectsUnknownLinkedTask`、`rejectsMalformedKnowledgeMetadata`。

```java
assertThat(hits).extracting(KnowledgeHit::id).containsExactly(titleHitId, tagHitId, bodyHitId);
assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(200);
assertThat(hit.preview()).isTrue();
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=KnowledgeServiceTest test`。
- [x] **Step 3 — 实现。** ID 使用 `knowledge-` 加 UUID；标签按 `Locale.ROOT` 比较，多个标签为 AND；查询词是标题、标签或正文的子串，不做向量检索。默认 10、上限 20，输入标签关联任务均验证格式和存在性；同等级命中按更新时间倒序、ID 稳定排序。每个知识正文限制 16,384 code points，超出返回 SUMMARY_REQUIRED 且保留旧文档。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=KnowledgeServiceTest test`；然后 `mvn test`，验证保存知识不改变任何任务文档修订号。
- [x] **Step 5 — 提交。** 提交 `feat: persist and search shared workspace knowledge`。

### Task 6：有预算的上下文恢复与陈旧状态提示

**Files:** 创建 `context/`、`common/ApiResponse.java`、`common/ResponseEncoder.java`、`ContextServiceTest`；为历史读取接入同一预算检查。

**Interfaces:**
- `ContextRequest` 包含 taskId、documents、knowledgeIds、maxChars，默认选择 GUIDE / TASK / STATUS / HANDOFF，不含历史。
- `ContextData` 包含 taskId、revision、state、documents、authoritativeNextStepsDocument="STATUS.md"、totalChars；每项 `ContextDocument` 包含文档类型或知识 ID、项目相对路径、text、fileRevision。
- `ContextService.load(ContextRequest): ApiResponse<ContextData>`；在同一项目锁内使用底层 Repository 组装结果，不嵌套调用其他已经加锁的 Service。
- `ResponseEncoder.encode(ApiResponse<?>): String`、`requireBudget(ApiResponse<?>, int): void`；上下文结果的 totalChars 以整个 ApiResponse JSON 文本计算，含 warnings 与必要包装，不含 JSON-RPC 传输外壳。

- [x] **Step 1 — 写失败测试。** `restoresGoalConstraintsAndNextSteps`、`excludesHistoryUnlessExplicitlyRequested`、`loadsOnlySelectedKnowledge`、`countsEmojiAsCodePoints`、`rejectsBudgetBelow4000OrAbove64000`、`doesNotSilentlyTruncateOverBudgetContext`、`warnsWhenHandoffUsesOlderStatusRevision`、`limitsHistoryResponsesToo`。

```java
assertThat(result.data().authoritativeNextStepsDocument()).isEqualTo("STATUS.md");
assertThat(result.data().totalChars()).isEqualTo(json.codePointCount(0, json.length()));
assertThatThrownBy(() -> service.load(tooSmallRequest)).hasFieldOrPropertyWithValue("code", ErrorCode.BUDGET_EXCEEDED);
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=ContextServiceTest test`。
- [x] **Step 3 — 实现。** 保持选中文档的固定输出顺序，拒绝未知文档或知识 ID；默认预算 24,000。设置 totalChars 后重新序列化，直到数字字段自身的长度计入后稳定，再与预算比较。超过预算返回所需长度和可分开读取的文档，不截断正文。比较 basedOnStatusRevision 并辅以更新时间检测陈旧交接；初始空交接明确提示尚无记录。所有普通任务文档写入限制 8,192 code points，公共指南也检查 8,192；大于限额时返回整理提示并保留原文。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=ContextServiceTest,HistoryServiceTest,KnowledgeServiceTest test`；同一固定时钟下先保存交接再更新状态，也必须提示交接陈旧。
- [x] **Step 5 — 提交。** 提交 `feat: assemble bounded task context with stale handoff warnings`。

### Task 7：发布十个真实 MCP 工具与协议错误

**Files:** 创建 `mcp/`、`config/WorkspaceConfiguration.java`、`application.yml`、`logback-spring.xml` 和 `ToolContractTest`；补齐应用入口配置。

**Interfaces:**
- `ToolExecutor.invoke(Supplier<? extends ApiResponse<?>>): McpSchema.CallToolResult`：成功或失败的 JSON 放入 TextContent；业务失败必须设置 isError=true，不能只在正文返回 ok=false。
- 四个工具类分别暴露设计中的十个名字：workspace_init、workspace_status、task_create、task_list、context_load、task_update、handoff_save、history_read、knowledge_save、knowledge_search。context_load 放在 TaskTools 中。参数采用前述请求模型，默认值由服务校验；普通服务结果先包 ApiResponse.success，上下文已包含 envelope，不再二次包装。
- 使用 Spring AI 1.1 的 `@McpTool`、`@McpToolParam` 注册同步方法；直接返回 SDK `CallToolResult`，不混用第二套 ToolCallback 注册造成重名。

- [x] **Step 1 — 写失败测试。** `listsExactlyTenTools`、`schemasContainTaskIdAndExpectedRevision`、`toolFailuresSetIsError`、`malformedArgumentsReturnReadableError`、`missingProjectRootFailsStartupWithoutCreatingWorkspace`。通过 MCP Java SDK 的 STDIO 客户端连接编译后的应用 classpath，实际执行 tools/list 与工具调用；不得只反射 Java 注解。

```java
assertThat(toolNames).containsExactlyInAnyOrderElementsOf(expectedTenNames);
assertThat(failedResult.isError()).isTrue();
assertThat(errorJson.path("error").path("code").asText()).isEqualTo("NOT_INITIALIZED");
```

- [x] **Step 2 — 验证 red。** `mvn -Dtest=ToolContractTest test`；classpath 子进程使用 Surefire 提供的 `surefire.test.class.path`，参数用列表传给 ProcessBuilder，不拼接命令字符串。
- [x] **Step 3 — 实现注册与配置。** 设置 `spring.main.web-application-type=none`、`spring.main.banner-mode=off`、`spring.ai.mcp.server.stdio=true`、`spring.ai.mcp.server.type=SYNC`；日志 appender 指向 System.err。启动不自动运行 workspace_init。服务 description 中写明先检查 / 初始化、查询任务再加载上下文，以及修订冲突后应重读；只返回安全错误说明，异常栈仅写 stderr。
- [x] **Step 4 — 验证 green。** `mvn -Dtest=ToolContractTest test`，再 `mvn test`；参数绑定错误如果发生在工具方法之外，也要验证客户端得到协议允许的可读错误，不产生无法解析的输出。
- [x] **Step 5 — 提交。** 提交 `feat: expose local workspace services as stdio MCP tools`。

### Task 8：打包、跨 Agent 接续与独立进程竞争验收

**Files:** 创建 `e2e/StdioClient.java`、`e2e/McpStdioIT.java`、`README.md`、两个 `examples/` 配置；完成 POM 的 repackage 与 Failsafe 配置。

**Interfaces:**
- 测试 `StdioClient` 是独立的 JSON-RPC 行协议客户端，不复用服务的请求分发代码；提供 `start(Path jar, Path project): StdioClient`、`callTool(String name, Map<String,Object> arguments): JsonNode`、`close(): void`。请求 ID 关联返回结果，处理通知，独立排空 stderr；单次等待最多 30 秒，超时终止自建进程并报告已收集的诊断。
- `McpStdioIT` 通过 `java -jar target/local-workspace-mcp-0.1.0.jar` 连接，实际协商 SDK 支持的 MCP 版本；协议测试只使用临时项目。

- [x] **Step 1 — 写失败集成测试。** `agentBResumesAfterAgentAExits` 完成 A 初始化、创建两个任务、更新一个、交接、保存关联知识、关闭 A；B 新进程能定位原任务、获得相同下一步，且不串到另一个任务。`onlyOneProcessWinsSameRevisionUpdate` 先让两个独立进程读取相同 revision，再同步发起不同状态更新，断言一个成功另一个 REVISION_CONFLICT。

```java
assertThat(successCount).isEqualTo(1);
assertThat(conflictCount).isEqualTo(1);
assertThat(restoredNextSteps).containsExactly("实现刷新令牌接口并补充过期测试");
assertThat(nonProtocolStdoutLines).isEmpty();
```

- [x] **Step 2 — 运行验收并定位失败。** `mvn verify`；测试应等待打包完成后启动 JAR。覆盖中文与空格路径、重复连接、stderr 被持续排空、非法路径参数、超限状态写入、无损初始化重试和历史查询。此时尚未实现的行为应产生明确失败，不能忽略集成测试结果。
- [x] **Step 3 — 完成打包与使用说明。** 固定产物名 `local-workspace-mcp-0.1.0.jar`；通用 JSON 示例 command 使用 java.exe，args 分别列出 `-jar`、JAR 路径与 `--local-workspace.project-root=...`；Codex TOML 示例按当时官方连接格式核对后写入。README 说明首次连接、自动入口安装、十个工具、修订冲突重读、摘要超限处理、单任务接续示例和已知边界，不自动修改用户家目录配置。
- [x] **Step 4 — 最终验收。** 运行 `mvn verify`，预期 BUILD SUCCESS、Surefire 和 Failsafe 零失败；运行 `git diff --check`。检查产物存在、所有集成子进程已退出、测试未修改真实项目。仅在出现代码修改或未解决失败时重复相关测试。
- [x] **Step 5 — 提交并交付。** 提交 `test: verify cross-agent workspace recovery and document setup`，报告 JAR 路径、启动命令、实际通过的测试和任何未通过的条件，不把待执行步骤写成已完成。

## 设计验收覆盖

| 设计第 12 节 | 主要任务 |
| --- | --- |
| 1 初始化幂等、指南保留 | Task 2 / 8 |
| 2 现有规则、BOM、CRLF、override | Task 2 |
| 3 同名非工作区目录冲突 | Task 1 / 2 |
| 4 多任务隔离与归档过滤 | Task 3 / 8 |
| 5 独立客户端重启恢复 | Task 7 / 8 |
| 6 独立进程并发写入 | Task 1 / 3 / 8 |
| 7 人工编辑冲突与损坏格式 | Task 3 / 5 |
| 8 文档限额、读取预算、默认不读历史 | Task 5 / 6 / 8 |
| 9 精确历史快照、普通更新不归档 | Task 4 / 8 |
| 10 路径穿越与链接边界 | Task 1 / 2 / 8 |
| 11 写入失败保护与初始化恢复 | Task 1 / 2 / 4 |
| 12 JAR 工具发现与 stdout 纯净 | Task 7 / 8 |

## 执行方式与评审点

推荐 Native：由当前 Agent 在指定目录顺序实现八个任务，每个任务完成必要验证；完成后由一个新的审查者检查完整改动。任务共享文件与版本契约，顺序执行能减少接口漂移。

另一选项为 Subagent-driven：逐任务交给新的子 Agent，并在每个任务后进行独立评审，开销更高。

书面计划须由用户评审并选择执行方式后，才能开始创建 POM、产品代码、测试代码或安装产品依赖。执行所需的 executing-plans 或 subagent-driven-development 技能在选择后补齐并读取，不以此计划本身替代对应执行流程。
