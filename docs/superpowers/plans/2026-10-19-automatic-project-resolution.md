# Automatic Project Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 自动定位 Agent 当前项目，用户无需逐项目修改 MCP 配置，并保持旧固定目录配置及已有工作区兼容。

**Architecture:** 每个 MCP 请求通过 ProjectResolver 选择真实项目，WorkspaceRuntimeFactory 构建该次调用独立的根目录绑定服务集合。工具可接收框架上下文和可选顶层 projectRoot；业务服务继续只访问一个已确定项目。固定模式不查询 Roots，自动模式使用当前客户端 Roots 与 Agent 参数，不缓存可变当前项目。

**Tech Stack:** Java17、Maven、Spring Boot3.5.16、Spring AI1.1.8、MCP SDK0.18.3 / annotations0.9.0（现有依赖）、JUnit5 / AssertJ。

**Spec:** [用户已确认设计](C:/individual-event/Local-Workspace/docs/superpowers/specs/2026-10-19-automatic-project-resolution-design.md)。执行前须同时阅读本计划与设计。

**Status:** 用户于2026-10-19确认实施计划；已恢复开发目录写权限并同步评审文档。当前 Agent 在 C:/individual-event/Local-Workspace 顺序实现，完成后独立审查。

## Global Constraints

- 开发与产品构建仍在 C:/individual-event/Local-Workspace；自动默认示例保持原 Java/JAR 位置。
- Java17 / Maven / Spring Boot3.5.16 / Spring AI1.1.8；同步 STDIO；保留原十个工具，不增加产品依赖。
- 无启动 project-root 为 AUTO；显式空白无效；有效固定目录为 FIXED，无效固定配置启动失败。
- 十个工具增加可选顶层 String projectRoot，原 request/taskId/expectedRevision 等位置不变。
- AUTO 每次查询所属客户端 Roots，无全局/会话可变当前目录；未确定项目不写文件。
- Agent 参数必须为存在的本机绝对目录；AUTO 不接受相对路径、文件、不存在目录、UNC、文件系统根目录。
- 非空 Roots 限定 Agent 参数范围；零 Roots/无 Roots 能力可用显式参数；多候选不默认选第一项。
- Roots URI 仅本机 file:，authority 为空/localhost；非法条目、非空 nextCursor、失败及超时都报 ROOTS_UNAVAILABLE。
- Roots 等待配置10秒；已有项目锁等待5秒。先解析项目，再获取业务锁。
- 不回退 user.dir/JAR目录/主目录/旧项目；不扫描磁盘和私有聊天，不执行使用者项目命令。
- 文档 schemaVersion=1、ID、修订算法、历史格式与已有数据不变；无需迁移。
- 既有256KiB写前上限、YAML预校验、预算、原文件保护、原子替换、跨进程锁等保持。
- 原限额保持：任务/状态/交接及指南8192 code points，知识正文16384，响应默认24000、范围4000..64000。
- stdout 仅 JSON-RPC，日志与异常栈仅 stderr；测试只用临时项目，不初始化源代码目录或真实业务目录。
- 未恢复目标项目写权限、用户未评审本计划前，不创建产品代码或安装产品依赖。

## Review Focus

1. Roots 正常空列表与查询失败/不完整不能混淆；后两者不能用参数或旧项目绕过。Task1 决策测试，Task4 真协议故障测试。
2. Windows 百分号编码/localhost、UNC/相对路径、相似字符串前缀及 junction 真实路径边界必须正确。Task1 解析测试。
3. 列表变化、两个项目交错调用、已经创建的 Runtime 不能互相重定向。Task2 隔离测试，Task4 同进程切换/并行测试。
4. SYNC 工具内 roots/list 反向请求不能被客户端写锁或 stdout 排空线程阻塞；超时后还应能恢复。Task3 SDK 契约，Task4 双向行协议客户端。
5. Spring 空白路径绑定不能变成 AUTO；新增定位信息不能破坏预算；升级指引不能重置用户指南。Task3 配置/响应测试，Task4 兼容验收。

## 文件与接口地图

所有产品路径相对于开发目录。

- 新建 project/ProjectResolution.java：不可变项目选择、枚举与输出 DTO。
- 新建 project/RootsSnapshot.java、ClientRootsSource.java、McpClientRootsSource.java：列表快照、可测试来源接口、SDK适配。
- 新建 project/ProjectResolver.java：纯项目选择决策，固定模式提前校验。
- 新建 project/WorkspaceRuntime.java、WorkspaceRuntimeFactory.java：按调用构建现有业务图。
- 新建 project/WorkspaceRuntimeProvider.java：工具上下文 → Resolver → RuntimeFactory。
- 修改 config/WorkspaceProperties.java、WorkspaceConfiguration.java：允许 AUTO 启动和延迟绑定。
- 修改 storage/WorkspacePaths.java：复用同一目录名验证，不放宽现有路径规则。
- 修改 common/ErrorCode.java：增加 PROJECT_REQUIRED / PROJECT_AMBIGUOUS / ROOTS_UNAVAILABLE。
- 修改 workspace/WorkspaceModels.java：给 WorkspaceStatus / InitResult 增加可选 resolution，保留旧构造签名。
- 修改 mcp/ 下四个工具类，保留 ToolExecutor 的错误包装和原业务 Service。
- 修改 application.yml、指南模板、GuidanceInstaller、README 和两个 examples 文件。
- 新建测试 ProjectResolverTest、McpClientRootsSourceTest、WorkspaceRuntimeFactoryTest、WorkspaceBindingTest、ProjectRoutingContractTest、AutoProjectStdioIT；扩展现有 StdioClient 和必要契约测试。

---

### Task 1：可测试的项目选择与 Roots 校验

**Files:** 新建 project/ProjectResolution.java、RootsSnapshot.java、ClientRootsSource.java、McpClientRootsSource.java、ProjectResolver.java；修改 common/ErrorCode.java；新建 src/test/java/dev/localworkspace/project/ProjectResolverTest.java、McpClientRootsSourceTest.java。

**Interfaces:**
- `record ProjectResolution(Path projectRoot, Mode mode, Source source)`；Mode=FIXED/AUTO，Source=CONFIG/MCP_ROOTS/AGENT_ARGUMENT。`info(): ResolutionInfo` 返回内部 record `ResolutionInfo(String mode,String source,String projectRoot)`，使用实际真实路径字符串。
- `record RootsSnapshot(List<String> uris,String nextCursor)`；不携带可变全局列表。
- `interface ClientRootsSource { boolean supported(); RootsSnapshot read(); }`。
- `McpClientRootsSource(McpSyncRequestContext context)` 实现来源；read 调用 roots() 并转换列表，捕获协议/超时/结构错误为 ROOTS_UNAVAILABLE，不能泄露栈。
- `ProjectResolver(Path configuredRoot)` 验证固定目录，null 表示 AUTO；`resolve(String projectRoot, ClientRootsSource roots): ProjectResolution`。
- FIXED 不调用来源的任何方法；自动参数非法为 INVALID_ARGUMENT、越界为 PATH_NOT_ALLOWED；无目录/多目录按设计报错，details 多候选按真实路径字符串排序。

- [ ] **Step 1 — 编写失败测试。** 用临时目录及小型 ClientRootsSource 假实现覆盖设计决策表。测试包括 fixedSkipsRoots、fixedRejectsDifferentArgument、uniqueRootResolvesAutomatically、emptyAndUnsupportedRequireArgument、argumentSelectsAllowedChild、ambiguousRootsDoNotSelectFirst、failedOrIncompleteRootsDoNotUseArgument、invalidEntryDoesNotSelectRemainingRoot、deduplicatesEquivalentRoots、rejectsOverlappingDistinctRootsWithoutSelection、rejectsStringPrefixEscape、rejectsJunctionEscape、supportsEncodedChineseAndLocalhost、rejectsRelativeUncRootAndBadUris。SDK适配测试用 mock context 验证能力/空列表/异常映射。

```java
assertThat(new ProjectResolver(null).resolve(null, rootsOf(project))).isEqualTo(
    new ProjectResolution(project.toRealPath(), Mode.AUTO, Source.MCP_ROOTS));
assertThatThrownBy(() -> resolver.resolve(outside.toString(), rootsOf(project)))
    .hasFieldOrPropertyWithValue("code", ErrorCode.PATH_NOT_ALLOWED);
assertThatThrownBy(() -> resolver.resolve(project.toString(), incompleteRoots()))
    .hasFieldOrPropertyWithValue("code", ErrorCode.ROOTS_UNAVAILABLE);
```

`rootsOf(Path...)` / `incompleteRoots()` 是本测试类内辅助来源，分别使用 Path.toUri 字符串、非空 nextCursor。Windows junction 测试采用原 StorageContractTest 的 mklink /J 模式并在 finally 删除链接，不递归删除目标。

- [ ] **Step 2 — 验证 RED。** `mvn -B -Dtest=ProjectResolverTest,McpClientRootsSourceTest test`。Expected：初始可因缺少新类型编译失败；加最小签名后应因未实现决策而失败，不能把 mock 自身错误当成功复现。
- [ ] **Step 3 — 实现。** 严格按设计决策表。URI 查询/fragment/authority/opaque 检查在 Path.of/真实路径解析前执行；localhost 规范化为空 authority；用路径对象而非字符串判定边界。未配置或正常空列表与失败分开处理，非空 cursor 拒绝；校验完所有 Roots 再选择，去重不折叠重叠父子目录。不读取进程工作目录或缓存旧选择。
- [ ] **Step 4 — 验证 GREEN。** 同一专项命令；Expected：所有决策/适配测试通过。再 `mvn -B test`，原固定项目功能应保持通过，且没有临时项目之外写入。
- [ ] **Step 5 — 提交。** `feat: resolve projects from client roots and explicit arguments`。此步不改 Spring 绑定或工具入口，当前产品仍按旧方式启动。

### Task 2：按项目构建不可变业务 Runtime

**Files:** 新建 project/WorkspaceRuntime.java、WorkspaceRuntimeFactory.java；修改 storage/WorkspacePaths.java 暴露复用验证；新建 src/test/java/dev/localworkspace/project/WorkspaceRuntimeFactoryTest.java。

**Interfaces:**
- `record WorkspaceRuntime(ProjectResolution resolution,WorkspaceService workspace,TaskService tasks,ContextService context,HistoryService history,KnowledgeService knowledge)`。
- `WorkspaceRuntimeFactory(WorkspaceProperties properties,ObjectMapper json,Clock clock,MarkdownCodec codec,ResponseEncoder encoder)`；`create(ProjectResolution resolution): WorkspaceRuntime`。
- `WorkspacePaths.validateDirectoryName(String name): void`：将原 private validName 的规则原样复用，构造器也调用它；不改变已有有效/无效名字集合。
- Factory 构造时检查公共目录名，create 中根据选择目录构造新的根目录绑定 WorkspaceProperties、路径、存储、锁及全部 Repository/Service；不调用 initialize，不缓存 Runtime，不覆盖 Factory 的“当前项目”。

- [ ] **Step 1 — 编写失败测试。** creatingRuntimeDoesNotInitialize、twoProjectsKeepTasksKnowledgeAndHistorySeparate、reusingRuntimeAfterAnotherCreationKeepsItsProject、concurrentProjectsDoNotRetarget、sameRootRuntimesDetectStaleRevision、differentProjectIsNotBlockedByOtherProjectLock、invalidDirectoryNameStillRejected。

```java
var a = factory.create(auto(projectA));
var b = factory.create(auto(projectB));
assertThat(Files.exists(projectA.resolve("LocalWorkspace"))).isFalse();
a.workspace().initialize(NONE);
b.workspace().initialize(NONE);
var task = a.tasks().create(WorkspaceFixture.task("A 独有"));
assertThat(b.tasks().list(null).total()).isZero();
assertThatThrownBy(() -> b.context().load(contextRequest(task.taskId())))
    .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
```

辅助 auto(Path) 构造 Task1 的 AUTO/MCP_ROOTS 选择；contextRequest(String) 构造现有 ContextRequest。并发用 CountDownLatch、有限 Future.get，不用固定 sleep 猜完成；用一个独立 ProjectLock 暂持 A，验证 B 可完成，不把全局锁行为当项目隔离。

- [ ] **Step 2 — 验证 RED。** `mvn -B -Dtest=WorkspaceRuntimeFactoryTest test`。Expected：最小类型声明后隔离/无副作用断言失败，或明确缺少 Factory 行为。
- [ ] **Step 3 — 实现。** 复用原 WorkspaceConfiguration 的完整依赖图：CheckpointRepository 同时作为 TaskService 的 CheckpointWriter，ContextService 使用 Repository，不嵌套锁 Service。每个图绑定同一真实根目录和限额；共享只读编解码器/Clock/ObjectMapper。目录名验证在项目未选择时也可完成。
- [ ] **Step 4 — 验证 GREEN。** 专项命令后 `mvn -B test`；Expected：新增隔离测试和原业务测试全通过，原 WorkspaceConfiguration 暂保持旧工具装配，避免中间提交无法启动。
- [ ] **Step 5 — 提交。** `feat: construct isolated workspace runtimes per project`。

### Task 3：自动启动、十个工具路由和可见定位结果

**Files:** 新建 project/WorkspaceRuntimeProvider.java；修改 config/WorkspaceProperties.java、WorkspaceConfiguration.java、workspace/WorkspaceModels.java、mcp/WorkspaceTools.java、TaskTools.java、HistoryTools.java、KnowledgeTools.java、application.yml、模板指南和 GuidanceInstaller；新增 WorkspaceBindingTest、ProjectRoutingContractTest；调整现有 ToolContractTest。

**Interfaces:**
- `WorkspaceRuntimeProvider(ProjectResolver resolver,WorkspaceRuntimeFactory factory)`；`resolve(McpSyncRequestContext context,String projectRoot): WorkspaceRuntime`，使用本次上下文的 McpClientRootsSource。
- 四个工具构造器改用 Provider/ToolExecutor；HistoryTools 另保留 ResponseEncoder 和公共 WorkspaceProperties。每个工具增加框架注入 context 和 `@McpToolParam(required=false) String projectRoot`，原业务参数及名字保持。
- WorkspaceTools：initialize(context,guidanceTarget,projectRoot)、status(context,projectRoot)；TaskTools：create/list/context/update(context,request,projectRoot)；HistoryTools：save(context,taskId,content,expectedRevision,projectRoot)、read(context,request,projectRoot)；KnowledgeTools：save/search(context,request,projectRoot)。返回均为原 CallToolResult。
- WorkspaceStatus / InitResult 各追加可选 ResolutionInfo resolution；保留原构造重载，并提供 `withResolution(ResolutionInfo): 相同record类型`。只在这两个 MCP 结果中补入信息，不改 ApiResponse 或其他预算结果。
- WorkspaceConfiguration 只保留公共 Bean，装配 Resolver/Factory/Provider，去掉提前绑定项目的业务 Bean；固定配置依然初始化时验证。每个工具必须在 executor.invoke 的 Supplier 内调用 Provider，使定位失败也生成业务错误 envelope/isError。

- [ ] **Step 1 — 编写失败测试。** WorkspaceBindingTest 使用 ApplicationContextRunner 覆盖 absentRootUsesAuto、explicitEmptyOrWhitespaceRootFails、invalidFixedRootFails、partialLimitsKeepDefaults。空白属性需检查 Environment 原始值，不能因 Path 绑定将空字符串转 null 就当 AUTO。ProjectRoutingContractTest 使用真实 MCP Java SDK STDIO 子进程覆盖无配置启动/tools-list无写入、唯一Roots自动状态/初始化、无Roots补充路径、固定模式不请求Roots、定位失败isError、遍历十个工具验证schema可选顶层projectRoot且没有context字段、旧业务字段位置、resolution保留data.workspace。SDK client 使用 capabilities(ClientCapabilities.builder().roots(true).build()) 和 roots(new Root(project.toUri().toString(),name))，支持实例设置列表。

```java
assertThat(client.listTools().tools()).hasSize(10);
var schema = json.valueToTree(updateTool.inputSchema());
assertThat(schema.path("properties").has("projectRoot")).isTrue();
assertThat(schema.path("required").toString()).doesNotContain("projectRoot");
assertThat(status.path("data").path("resolution").path("source").asText())
    .isEqualTo("MCP_ROOTS");
```

改写原 missingProjectRootFailsStartupWithoutCreatingWorkspace 用例为 AUTO 可启动且未定位不写文件，同时新增明确非法固定配置仍启动失败的用例；不要删除其他业务、格式、预算和退出检查。

- [ ] **Step 2 — 验证 RED。** `mvn -B -Dtest=WorkspaceBindingTest,ProjectRoutingContractTest,ToolContractTest test`。Expected：新自动启动/新schema/定位结果失败，保留失败前子进程stderr；不能因SDK客户端配置错误而误判服务缺陷。
- [ ] **Step 3 — 实现。** WorkspaceProperties 允许 null 根目录且拒绝 Path 空白；Configuration 另外检查 Environment 的原始配置，固定模式提前创建 Resolver 验证。application.yml 设置 spring.ai.mcp.server.request-timeout=10s。Provider 每次创建 Runtime。context_load 不二次包 envelope；history_read 仍使用完整响应预算；旧参数默认值照常。新指南和工具描述说明补路径/固定模式/后续携带实际路径，受管区块外字节与用户指南继续保留。
- [ ] **Step 4 — 验证 GREEN。** 专项命令后 `mvn -B test`。Expected：固定/自动客户端契约全部通过；新增 resolution 不改变 context_load 的实际字符计数。补断言已有自定义指南在再次初始化后字节不变、BOM/CRLF仍保留；旧fixture直接构造业务Service仍可用。
- [ ] **Step 5 — 提交。** `feat: route stdio tools to automatically selected projects`。

### Task 4：打包双向协议验收与接入文档

**Files:** 扩展 src/test/java/dev/localworkspace/e2e/StdioClient.java；新建 AutoProjectStdioIT.java；保留 McpStdioIT.java 原固定模式验收；修改 README.md、examples/mcp-stdio.json、examples/codex-config.toml。POM 维持原打包名和 Failsafe 执行顺序。

**Interfaces:**
- 保留 `StdioClient.start(Path jar,Path project,String... serverArguments)` 固定模式。
- 增加 `startAuto(Path jar,Path processDirectory,boolean rootsSupported,List<Path> roots,String... serverArguments): StdioClient`，自动启动默认不传 project-root；额外启动参数仅供覆盖测试配置。
- 增加 `record RootsReply(List<String> uris,String nextCursor,Integer rpcErrorCode,boolean withholdResponse)`、`setRoots(List<Path>): void`、`setRootsReply(RootsReply): void`、`rootsRequestCount(): int`；状态使用 AtomicReference，根请求计数使用 AtomicInteger，仅是测试客户端控制，不是服务端全局项目。
- 原 callTool/request 改为 AtomicLong 请求ID + ConcurrentHashMap<String,CompletableFuture<JsonNode>> 关联响应，允许同一进程并行调用；待回包最多30秒，关闭时唤醒未完成等待并终止自建子进程。
- stdout线程先区分含method的服务器请求：处理roots/list并原样回传其ID、RootsReply列表/错误；withholdResponse时不回复但继续排空。普通响应分发给pending。writerLock只保护一条JSON-RPC的写入/flush，不能把等待响应包在写锁里；保留独立stderr排空和64KiB计数测试。

- [ ] **Step 1 — 编写验收测试。** JarAutoModeListsToolsWithoutWrites、uniqueRootInitializesWithoutArgument、unsupportedAndEmptyRootsAcceptAgentArgument、multipleRootsRequireSelectionWithoutWrites、rootsChangeRoutesNextCallToNewProject、parallelCallsToTwoRootsStayIsolated、rootsFailureAndIncompleteListNeverUseArgumentOrPreviousProject、rootsTimeoutThenRecovers、agentBResumesAutoWorkspace、autoModePreservesEditedGuide、fixedModeSkipsAdvertisedRoots。固定模式测试用 startAuto 的额外参数固定A、客户端声明B，断言定位仍为A/CONFIG且 rootsRequestCount()==0。每项通过实际打包JAR和行协议，temp目录模拟错误启动目录，确认没有在那里生成工作区。

```java
try (var c = StdioClient.startAuto(jar, wrongStartupDirectory, true, List.of(projectA))) {
    assertThat(ok(c, "workspace_status", Map.of()).path("workspace")
        .path("projectRoot").asText()).isEqualTo(projectA.toRealPath().toString());
    c.setRoots(List.of(projectB));
    ok(c, "workspace_init", Map.of("guidanceTarget", "none"));
    assertThat(Files.exists(projectA.resolve("LocalWorkspace"))).isFalse();
    assertThat(Files.exists(projectB.resolve("LocalWorkspace"))).isTrue();
    assertThat(c.nonProtocolStdout()).isEmpty();
}
```

Roots 超时测试用 withholdResponse=true 而不是让stdout读取线程sleep：调用前已成功取得A，之后超时的初始化不能写A；观察约10秒返回ROOTS_UNAVAILABLE（允许调度误差，上限20秒，客户端30秒等待），恢复B的正常列表后同连接能继续。A/B接续包含任务状态、交接和知识；同项目两个独立进程继续验证一成功一REVISION_CONFLICT。

- [ ] **Step 2 — 运行实际 JAR 验收。** `mvn -B verify`。Expected：打包后 Failsafe 执行新增自动模式与原固定模式测试。新测试若发现协议/业务缺陷，按真实失败修复；若首轮已通过则保留该证据，不人为破坏实现制造端到端红灯。
- [ ] **Step 3 — 完成文档。** 默认 JSON/TOML 删除固定 project-root 参数，保留原 Java/JAR路径和超时；README提供旧固定模式参数示例、AUTO/FIXED决策表、Agent补充顶层路径提示词、多项目选择、客户端不支持Roots时的预期行为，以及首次删除旧固定参数只需一次。说明没有HTTP/私有聊天读取、不覆盖用户指南、不需要数据迁移。Codex格式按执行时官方文档核对，不改实际用户配置。
- [ ] **Step 4 — 最终验收。** 有代码修复后再 `mvn -B verify`，读取Surefire/Failsafe汇总，Expected：全部零失败/错误/跳过，原73项行为覆盖仍在。运行 `git diff --check`、JSON/TOML解析、检查JAR存在、源目录无LocalWorkspace产品数据、自建测试进程均退出。只有失败或新修改才重复相关测试，不为宣称完成反复跑同一套。
- [ ] **Step 5 — 提交和交付。** `test: verify automatic project selection and document migration`。按已选 Native 方法做一次新上下文整支分支独立审查；重要问题以回归测试修复后验收。交付实际JAR、配置示例、通过的测试和限制，不把计划写成已实现。

## 设计覆盖与执行交接

| 设计内容 | 负责任务 |
| --- | --- |
| AUTO/FIXED、Roots/参数决策、路径边界、失败无写入 | Task1，Task3/4真实协议 |
| 请求隔离、依赖图、锁与无初始化副作用 | Task2，Task4并行/切换 |
| 十工具可选参数、启动语义、定位结果和错误/预算 | Task3 |
| 指南原文保护、已有数据格式和固定调用兼容 | Task2/3/4与原业务测试 |
| 独立进程、反向请求、10秒超时恢复、纯协议输出 | Task4 |
| 默认配置自动化、手动配置迁移、使用说明 | Task4 |

计划自检应确认上表无缺项、Task1/2产出的签名与Task3/4消费一致、五项Review Focus均有明确测试、没有占位要求或超范围功能。

用户此前明确选择“由你在当前目录顺序实现，完成后独立审查”，本升级沿用该方法，不重复要求选择执行方式。请评审本计划是否符合需求；确认后且目标目录写权限恢复后，使用 executing-plans 实施。实施前同步已批准设计/本计划到目标项目并核对仓库状态，保留现有修改；不自动合并、推送或改用户配置。
