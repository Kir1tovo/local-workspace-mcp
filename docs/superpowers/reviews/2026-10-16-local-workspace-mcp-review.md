# Local Workspace MCP 独立审查与最终验收

日期：2026-10-16。用户确认在 `C:\individual-event\Local-Workspace` 顺序实现，完成后独立审查。

## 交付与验证

- 八个计划任务均完成，代码保存在 `feat/local-workspace-mcp` 分支。
- Java17 / Maven / Spring Boot3.5.16 / Spring AI1.1.8，10个同步 STDIO MCP 工具。
- 最终命令：`mvn -B verify`；2026-10-16 12:27:57 +08:00 完成，BUILD SUCCESS。
- Surefire：67项；Failsafe：6项；共73项，失败/错误/跳过均为0。
- 验收包含两个独立进程之间的任务接续和写竞争、中文空格路径、无损初始化、输入超限、历史预算、真实SDK工具发现、缺少项目参数时拒绝启动、超过64KiB的stderr输出下协议正常。
- 产物：`target/local-workspace-mcp-0.1.0.jar`。测试仅使用临时项目；源代码目录没有初始化 `LocalWorkspace` 产品数据。
- `git diff --check` 通过。最终测试子进程已退出。配置 JSON/TOML 均通过解析且启动参数相同。

## 独立审查

审查者：新上下文的 gpt-6-astra，依据用户选择及 executing-plans / requesting-code-review 技能派发，只读审查，无实施子 Agent。
审查范围：`d0607c6fb6aec0b401a9884466aac2e33b5a8448..484f80f4930b0d781b4a3d9df1151bb72a9ff51d`。

未发现 Critical；发现3项 Important，审查时结论为需要修复。以下修复由作者完成，先运行9项新增回归并观察全部失败，再修改代码使其通过；最后运行全量73项。未进行第二次独立审查。

1. **知识元数据写后无法读取**：过多标签可超过解析器复杂度限制，但原实现已替换文件再读取。现在所有生成 Markdown 在发布前通过同一解析校验，拒绝时返回 SUMMARY_REQUIRED。回归检查已有知识字节不变、搜索仍可用，以及失败的新知识/任务不发布。
2. **字节上限只读不写**：现在共同存储写入前强制256KiB上限，任务文档在建立快照或发布暂存目录前也检查字节。回归覆盖新建任务、显式里程碑更新、共同存储新建/替换、接近限额的已有根目录规则文件，确保旧内容保留。
3. **当前文档损坏使完好历史不可读**：历史改为检查安全任务目录存在，不解析当前三个文档；快照继续使用自身元数据和哈希校验。回归验证当前 STATUS 损坏或 task.md 缺失时仍可列出、读取完好的历史，且不修改损坏文件。

## 延后的小问题

- 对象ID校验仍接受 nul/con/com1 等Windows设备名。服务生成的UUID ID不受影响，调用者传入异常ID时仍依靠后续文件系统与安全检查处理；未证实目录越界。
- workspace_init 的 changedPaths 仅列出规则入口变更，未列出新增或修复的工作区文件；workspace 信息本身仍正确。

## 执行判断与风险

以下为完整执行台账，含所有 Ruling 与 deferred minor，防止临时记录清理后丢失。前者是执行者对计划或审查边界作出的判断，后者是延后的小问题，不表示已修复。

```text
# SDD ledger — plan: docs/superpowers/plans/2026-10-16-local-workspace-mcp.md

Setup: approved spec and plan; user selected inline implementation at C:/individual-event/Local-Workspace, then independent review. Feature branch feat/local-workspace-mcp starts at d0607c6. Baseline contains only approved docs; no existing executable tests.
Pre-flight: Task 1 -> Tasks 2-8: shared path/store/lock/hash contracts match; only services acquire project locks.
Pre-flight: Task 2 -> Task 3: requireInitialized gates task access; partial initialization must be retryable.
Pre-flight: Task 3 -> Task 4: CheckpointWriter is introduced before its implementation; tests without checkpoints use a rejecting stub.
Pre-flight: Tasks 3-5 -> Task 6: context consumes repositories, not locking services; no nested file locks.
Pre-flight: Task 6 -> Task 7: context already returns an ApiResponse; adapter must not double-wrap.
Pre-flight: Task 7 -> Task 8: classpath and packaged-JAR protocol tests exercise separate launch paths; consistent tool names.
Task 1: complete (commits d0607c6..6b0340a, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 2: complete (commits 6b0340a..41d2ab2, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 3: complete (commits 41d2ab2..6244dbc, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 4: complete (commits 6244dbc..0d1102b, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 5: complete (commits 0d1102b..25ccc66, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 6: complete (commits 25ccc66..919c426, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)
Task 7: complete (commits 919c426..9ed0794, tests: mvn -B test → [INFO] ------------------------------------------------------------------------)

Task 8: Ruling: the first packaged-JAR integration run passed against features already implemented and tested RED→GREEN in Tasks 1–7; no artificial product regression was introduced to manufacture a failing end-to-end test — real first-run evidence retained — cost if wrong: end-to-end tests have no pre-fix failure evidence, while lower-layer regressions do.
Task 8: complete (commits 9ed0794..484f80f, tests: python .superpowers/sdd/2026-10-16-local-workspace-mcp/verify-reports.py → mvn -B verify evidence: 58 unit/SDK + 5 JAR process tests, zero failures/errors/skips; packaged JAR exists; source workspace untouched)

Final review: fresh independent reviewer gpt-6-astra, base d0607c6..484f80f; three Important findings accepted; no Critical. One fix pass in progress; nine regression tests failed on the reviewed tree and now pass in targeted suite 46/46.
Final: minor (deferred): object ID validation accepts reserved Windows device names (generated UUID IDs are safe; malformed caller IDs may reach filesystem checks).
Final: minor (deferred): workspace_init changedPaths lists only installed guidance entries, omitting newly created/repaired workspace files.
Final: Ruling: reviewer deferred artificially forcing the first JAR integration run red — accepted the Task 8 ruling because lower-level RED→GREEN evidence already exists — cost if wrong: no failing pre-fix end-to-end evidence for the initial acceptance tests.
Final: Ruling: reviewer deferred malicious path swaps, noncooperating writers' final race, and power-loss durability — these remain explicitly outside this cooperative local-file transaction guarantee — cost if wrong: adversarial concurrent edits or a crash can defeat these guarantees; use project backups/Git.
Final: Ruling: reviewer deferred Linux/macOS execution — retain Windows/JDK17 as the verified scope and disclose other platforms as unverified — cost if wrong: platform-specific path or launch failures outside Windows remain undiscovered.
Final: Ruling: reviewer deferred deliberate stderr saturation and atomic-move-refusing filesystems — add measured >64KiB stderr acceptance; retain unsupported-filesystem execution as an unverified boundary, with existing IO-failure protection tests — cost if wrong: unusual filesystems may expose IO behavior not seen on the tested filesystem.

Final: fixed knowledge metadata overwrite — excessiveMetadataCannotReplaceReadableKnowledge / excessiveMetadataIsRejectedBeforeCreatingKnowledge / rejectsComplexTaskMetadataBeforePublishing RED→GREEN, suite 73/73.
Final: fixed managed byte-cap write/read inconsistency — rejectsOversizedCreateAndReplacementBeforeCommitting / rejectsByteOversizedTaskBeforePublishingDirectory / rejectsByteOversizedUpdateBeforeCheckpointOrReplacement / oversizedManagedEntryPreservesExistingRootFile RED→GREEN, suite 73/73.
Final: fixed history dependency on damaged current documents — readsIntactHistoryWhenCurrentStateIsDamaged / readsIntactHistoryWhenCurrentDocumentIsMissing RED→GREEN, suite 73/73.
Final: measured stderr-volume acceptance drainsMoreThan64KiBStderrWithoutBlockingProtocol passed on the packaged JAR; full mvn -B verify: unit/SDK 67/67, independent JAR process 6/6; zero failures/errors/skips; no second review dispatched.

```
