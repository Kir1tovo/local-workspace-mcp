# Local Workspace MCP 自动项目定位：验收与独立审查

日期：2026-10-19。用户已确认书面设计与实施计划，选择在原目录顺序实现，完成后独立审查。

## 已完成的升级

- 目录：`C:\individual-event\Local-Workspace`；分支：`feat/automatic-project-resolution`。
- 四个计划任务的产品提交：`4703e2f`、`e6491fb`、`8721e19`、`f1a5525`。
- 不设置启动 `project-root` 时进入 AUTO。每次工具调用从当前客户端 Roots 或顶层 `projectRoot` 选择项目，不缓存可变的当前项目。
- FIXED 保持配置目录；所有十个工具保留原业务参数，增加可选顶层路径。只有 status/init 增加 resolution，context/history 的完整响应预算保持原规则。
- 多项目的任务、知识、历史、修订与锁隔离；无目录、歧义、无效或失败的 Roots 明确返回错误，不借旧项目回退。
- 默认 JSON/TOML 配置已移除业务项目路径。旧用户删除一次固定参数并重连即可启用 AUTO，已有工作区无需迁移；用户指南与已有根目录规则保留。

## 审查前完整验收

命令：`mvn -B verify`。2026-10-19 13:44:49 +08:00，BUILD SUCCESS。

- Surefire：100项；Failsafe：17项；总计117项；失败、错误、跳过均为0。
- 保留原六项固定模式 JAR 验收；增加十一项自动模式验收，包括错误启动目录、中文空格百分号路径、缺失/空/多 Roots、同连接切换与并行、失败/不完整列表、10秒超时后恢复、跨 Agent 接续和用户指南保护。
- 真实 SDK 工具发现验证十个 schema 的可选顶层 projectRoot、隐藏请求上下文、原业务参数位置及错误 isError；实际上下文字符计数仍与完整 JSON 正文一致。
- JSON/TOML 解析、Java 排版与 git diff 检查通过。JAR 与产品源文件同步，源码目录没有初始化产品数据，自建 JAR 测试子进程均已退出。
- 产物：`target/local-workspace-mcp-0.1.0.jar`；未改实际用户 MCP 配置。

## 实测发现与修复

1. SDK STDIO 客户端使用 JVM 默认字符集，Windows GBK 会破坏 UTF-8 中文及 JSON 转义。先复现 GBK 断言失败，再把 Surefire/Failsafe JVM 固定为 UTF-8，协议专项转为通过。
2. SDK0.18.3 出站 tryEmitNext 会在并发时丢消息，实际 JAR 并行用例超时并记录 Failed to enqueue message。增加仅保护同步入队订阅的 STDIO 适配器，不持锁等待 Roots 或执行业务；回归转为通过。
3. SDK 无参 roots() 会自动展开分页；重复游标触发持续反向请求。改用公开单页 listRoots((String)null)，由解析器拒绝非空 nextCursor。新增回归先失败后通过，真实 JAR 验证每次故障只查询一页且恢复后可继续。

## 独立审查

审查范围：`ff091a1182c97406cf62d0f687f081eedbb15b5b..f1a5525400258e98a90e21bb9fef7f4ec250dc03`。

审查者：新上下文 gpt-6-astra，只读，无实施子 Agent。审查原结论为 With fixes：未发现 Critical，发现2项 Important 与1项 Minor。作者按实际使用影响将 Minor 升为 Important，一次修复全部三项；未派发第二次审查。

1. **Roots 变更通知绕过工具查询规则**：独立有界 JAR 探针确认 FIXED 收到通知也会查询 Roots；AUTO 可重复翻页；通知查询报错会关闭连接。新增真实通知回归先失败（预期0次请求、实际1次），再明确消费可忽略的 `notifications/roots/list_changed`，使其不进入 SDK 默认分页处理器。每次工具调用继续重新读取 Roots，无当前项目缓存。回归同时覆盖 FIXED/AUTO 下 RPC 错误、重复游标、无回复及后续恢复，确认通知不查询、连接不退出、新工具调用能访问新目录。
2. **旧 FIXED UNC 配置被拒绝**：将 AUTO 本机目录限制与 FIXED 原有存在目录校验分开。新增文件系统边界回归先失败后通过；未连接真实 SMB share，此项不等于 SMB 端到端验证。不同真实目录仍拒绝。
3. **固定盘符根目录无法显式携带返回路径**：原启动配置接受该目录，后续指南却要求传入会被拒绝的相同路径。这会破坏正常接续，因此从 Minor 升为 Important。新增真实盘符根路径回归先失败后通过，固定配置与工具参数现在采用一致的验证。AUTO 仍拒绝盘符根目录与 UNC。

专项验证：18项目录/通知流测试及1项真实通知 JAR 测试全部通过。修复后的最终全量结果见文末记录。

本次没有新增延后小问题。上版已有的设备名ID、changedPaths仅列规则入口两项仍按[前次审查](2026-10-16-local-workspace-mcp-review.md)保留；本次未扩大其影响。审查者未判断的 Linux/macOS 实际运行、恶意路径替换/非合作写入/断电持久性均已记录执行判断，保留既有边界。

## 限制与接入

仅在 Windows + JDK17 验证。不读取私有聊天、不增加 HTTP/数据库/模型依赖，不强制宿主 Agent 调用工具。缺少或多个 Roots 时仍需 Agent 明确当前项目；无法确定才询问用户。

用法与迁移见 [README](../../../README.md)，配置见 [通用 JSON](../../../examples/mcp-stdio.json) 和 [Codex TOML](../../../examples/codex-config.toml)。Codex 字段按 [OpenAI 官方 MCP 文档](https://learn.chatgpt.com/docs/extend/mcp?surface=cli) 于2026-10-19核对。

## 最终修复与交付验证

最终产品提交：`41a3c3ff55560318817df4f26090f99558642646`。

修复后执行 `mvn -B verify`，于2026-10-19 13:58:57 +08:00得到 BUILD SUCCESS。Surefire 104项，Failsafe 18项，共122项；失败/错误/跳过均为0。原六项固定模式 JAR 用例仍全部保留并通过。独立审查发现的三项问题均完成 RED→GREEN 及全量回归；没有第二次独立审查，也没有新增延后小问题。

全量验证之后格式化器仅规范了 ProjectResolver 的两行缩进；已检查精确差异，重新 package 后该 class 的 SHA256 完全相同，无语义或字节码变化。未为排版重复运行完整测试。配置解析、JAR新鲜度、排版、diff检查均通过；测试子进程已退出，源码目录没有初始化工作区。保留原目录和升级分支，按已批准计划不自动合并、推送或修改用户配置。

产物：`target/local-workspace-mcp-0.1.0.jar`。

SHA256：`04E6887ED23AF2FBE4F09463B191E1250B2E8DEF62D3DADAE7C421CA4431EF0A`。

## 完整执行台账

以下完整保存本次计划的执行记录，包含全部 Ruling 及审查分级判断。临时测试记录清理后，Git历史和本报告仍可追溯；测试明细保留在 target 的 Surefire/Failsafe 报告中。

```text
# SDD ledger — plan: docs/superpowers/plans/2026-10-19-automatic-project-resolution.md

Setup: user approved spec and plan and selected sequential implementation with final independent review. Project write permission recovered. Work in requested directory on feat/automatic-project-resolution, base ff091a1 (product base 779740a). Ordinary sandbox cannot modify existing Git metadata; approved escalated git commands succeed.
Pre-flight: Task1 -> Task2/3: ProjectResolution/ResolutionInfo, ClientRootsSource, ProjectResolver signatures agree. FIXED validates eagerly and skips source access; AUTO reads per call.
Pre-flight: Task2 -> Task3: immutable WorkspaceRuntime exposes five business services plus resolution; factory stays unwired until Task3, so old startup remains valid through Task2.
Pre-flight: Task3 -> Task4: all ten tools take optional top-level projectRoot and injected context, status/init add resolution only; default AUTO startup allows tool discovery before project selection.
Pre-flight: Task4 -> prior tests: preserve fixed start helper, raw protocol must handle reverse roots/list without holding response wait under writer lock; existing six JAR tests retained.
Pre-flight: configured blank Path binding must also inspect raw Environment value; spec is explicit, implementation planned in Task3.
Task 1: complete (commits ff091a1..4703e2f, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 2: complete (commits 4703e2f..e6491fb, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 3: Ruling: Configure Surefire/Failsafe JVM charset as UTF-8 — SDK 0.18.3 STDIO uses default InputStreamReader; diagnostic test proved GBK corrupts UTF-8 Chinese and JSON escape boundaries. Server already starts with UTF-8. Cost if wrong: test JVM charset is constrained, no product protocol change.
Task 3: complete (commits e6491fb..8721e19, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 4: Ruling: Add a serialized STDIO outbound adapter without changing dependencies — actual JAR parallel requests exposed SDK 0.18.3 tryEmitNext dropping concurrent messages (Failed to enqueue message). Serialize the synchronous enqueue subscription only; never tool execution or waiting for Roots. Cost if wrong: adapter depends on the current SDK enqueue semantics and must be revisited on SDK upgrades.
Task 4: Ruling: Read Roots with context.exchange().listRoots((String)null), the public single-page API, instead of context.roots() — actual repeated cursor caused approximately 495k reverse requests in 30 seconds because the convenience method auto-expands pagination. Spec requires rejecting nonempty nextCursor. Cost if wrong: integration depends on the SDK single-page overload; upgrade must retain its contract.

Task 4: SDK outbound race regression and single-page pagination regression passed: McpClientRootsSourceTest 4/4, AutoProjectStdioIT selected failure/recovery and parallel tests 2/2. Full verify follows both product fixes.
Task 4: full mvn -B verify passed at 2026-10-19 13:44:49 +08:00; Surefire 100, Failsafe 17, zero failures/errors/skips. Existing six fixed JAR cases retained. JSON/TOML parsed, formatter/diff checks passed, JAR fresh, source has no LocalWorkspace data, all self-started JAR processes exited. task-done reads these fresh reports rather than repeating an unchanged full suite.
Task 4: complete (commits 8721e19..f1a5525, tests: /c/python/python.exe .superpowers/sdd/2026-10-19-automatic-project-resolution/verify_reports.py → Examples parsed; AUTO defaults, JAR freshness and clean source layout verified.)
Final: review by fresh gpt-6-astra completed on ff091a1..f1a5525. No Critical; Important Roots notification path, Important legacy FIXED UNC rejection; reviewer Minor fixed drive-root inconsistency regraded Important because following the required carry-returned-path guide breaks a previously valid fixed project. All three enter one fix pass; no new deferred minors.
Final: Ruling: Windows-only verification retained — Linux/macOS execution was outside the delivered platform and not exercised. Cost if wrong: behavior on those platforms remains unverified.
Final: Ruling: Retain cooperative local filesystem transaction boundary — malicious path races, noncooperating writers and power-loss guarantees were explicitly excluded and no destructive probes are warranted. Cost if wrong: this is not a defense against hostile filesystem actors.
Final: Ruling: Consume only optional notifications/roots/list_changed before the SDK notification handler — every tool already re-reads Roots, and the SDK exposes no public replacement handler. All requests, responses, initialization and other notifications still pass through. Cost if wrong: the version-specific transport adaptation must be reconsidered if future SDK notification semantics are needed.
Final: fixed Roots notification bypass — RootsNotificationInputStreamTest 2 cases and real-JAR rootsChangeNotificationsNeverQueryOrDisconnectFixedOrAuto RED→GREEN, suite 122/122.
Final: fixed legacy FIXED UNC restriction — fixedUncDirectoryKeepsLegacyFilesystemValidation RED→GREEN at filesystem boundary without contacting SMB, suite 122/122.
Final: fixed FIXED drive-root returned-path inconsistency (regraded Important) — fixedFilesystemRootAcceptsItsOwnReturnedPath RED→GREEN, suite 122/122.
Final: mvn -B verify BUILD SUCCESS at 2026-10-19 13:58:57 +08:00; 104 Surefire + 18 Failsafe, zero failures/errors/skips. One fresh independent review and one regression-verified fix pass; no second review. No new deferred minors. JSON/TOML, JAR/source freshness, diff checks passed; no source workspace data or test processes remain.
Final: Google Java Format required a second pass on two indentation lines only. Inspected exact diff and refreshed JAR with mvn -B -DskipTests package; ProjectResolver.class SHA256 identical before/after, no semantic or bytecode change and no redundant test rerun.
```
