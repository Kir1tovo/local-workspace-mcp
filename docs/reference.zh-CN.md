# Local Workspace MCP 技术参考

[English README](../README.md) · [中文 README](../README.zh-CN.md)

让不同 AI Agent 通过本地 Markdown 接续同一任务：保存目标、约束、当前状态、下一步、交接记录和共享知识。换软件或开新会话后，Agent 连接同一个项目即可读取已保存的事实。

Java 17 + Spring Boot 3.5 + Spring AI 1.1，使用标准 STDIO MCP。无需数据库、模型 Key 或联网推理服务。MCP 负责文件、修订号、历史和读取预算；内容由正在工作的 Agent 调用工具整理并保存。

## 构建与启动

需要 JDK 17 和 Maven 3.6.3+：

```powershell
cd C:\path\to\Local-Workspace
mvn verify
```

产物：`target/local-workspace-mcp-0.1.0.jar`。`verify` 同时执行单元测试、真实 SDK 契约测试和启动 JAR 的跨进程验收。

```powershell
java -Dfile.encoding=UTF-8 -jar C:\path\to\Local-Workspace\target\local-workspace-mcp-0.1.0.jar
```

默认 **AUTO 自动模式**，省略启动参数 `project-root`。服务每次调用根据客户端的 MCP Roots 或 Agent 传入的当前项目路径定位，无需为每个项目修改连接配置。启动和列出工具不创建工作区；由 Agent 首次调用 `workspace_init`。这是供 MCP 客户端启动的进程，终端直接启动后等待 STDIN 是正常行为；没有 HTTP 端口。stdout 只传输 JSON-RPC，日志写 stderr。

旧的固定目录模式仍可使用：

```powershell
java -Dfile.encoding=UTF-8 -jar C:\path\to\Local-Workspace\target\local-workspace-mcp-0.1.0.jar "--local-workspace.project-root=C:\项目 示例"
```

显式指定非空 `project-root` 即为 **FIXED 固定模式**：目录必须已存在，启动时校验；整个进程只访问该项目，完全不查询客户端 Roots。工具传入其他项目会被拒绝。显式空字符串或空白配置属于错误；要启用 AUTO，请完全删除该参数。

## 接入 Agent

- 通用 STDIO 配置见 [examples/mcp-stdio.json](../examples/mcp-stdio.json)。适用于采用 `mcpServers` 格式的客户端，其他软件按自身配置界面填写相同的 command/args。
- Codex 配置见 [examples/codex-config.toml](../examples/codex-config.toml)，请手动合并至自己的 Codex 配置，只需按安装位置修改 Java 和 JAR 路径。格式参考 [OpenAI 官方 MCP 文档](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)，核对日期 2026-10-19。示例不会自动修改实际用户配置。

从旧版本升级：在原连接配置中**删除一次** `--local-workspace.project-root=...` 参数，然后重连。保留它则继续使用 FIXED。已有 `LocalWorkspace/`、文档格式、ID、revision 和历史均无需迁移；已有用户指南不会被重置。

首次连接后，对 Agent 说：

> 使用 local-workspace MCP 检查并初始化当前项目。若没有 Roots 或存在多个项目，从你自身执行环境确定当前项目绝对路径，作为工具的顶层 projectRoot 传入；不要用 MCP 进程启动目录猜测。记录返回的真实路径，后续相关调用继续携带。开始新任务时创建任务；接续已有任务时先列出任务并加载上下文。完成阶段工作后更新状态，保存明确的下一步；结束前保存交接和可复用知识。

`workspace_init` 默认自动安装根目录 `AGENTS.md` 入口，并返回完整工作指南。不需要手动填写模板。入口仅修改 `LocalWorkspace:begin/end` 标记之间的内容，原有规则和根目录 `task.md` 保留；已有 `AGENTS.override.md` 时使用该文件。`guidanceTarget` 可选 `agents`、`claude`、`both`、`none`，分别支持 AGENTS、CLAUDE、两者或不安装入口。已有 AGENTS 不会因为选择 claude 而被删除。

自动入口能让支持对应规则文件的软件发现指南；其他 Agent 需要在首次指令中明确让它调用工具。MCP 无法强制宿主软件调用，也不会自动抓取未保存的聊天内容。

## 自动定位项目

所有十个工具都有可选的**顶层** `projectRoot`，原有业务参数位置不变。例如：

```json
{"projectRoot":"C:/work/order-service","request":{"query":"认证"}}
```

| AUTO 下的客户端目录信息 | 未传 projectRoot | 传入有效 projectRoot |
| --- | --- | --- |
| 不支持 Roots，或正常返回空列表 | `PROJECT_REQUIRED`，让 Agent 补路径 | 使用 Agent 指定目录 |
| 一个有效 Root | 自动使用该目录 | 必须位于该 Root 内，可选子项目 |
| 多个不同 Roots | `PROJECT_AMBIGUOUS`，返回排序后的候选路径 | 必须位于至少一个 Root 内 |
| 查询失败、超时、非法条目或分页不完整 | `ROOTS_UNAVAILABLE` | 同样失败，不能借参数绕过未知边界 |

不支持 Roots 的客户端仍可使用 AUTO，由 Agent 在每次工具调用补充自己的当前项目路径。多项目时 Agent 应明确选择并携带该路径；无法确定时才询问用户，不自动选第一项。Roots 只表示可用目录，不能据此保证知道客户端正在编辑哪一个项目。

AUTO 的 `projectRoot` 必须为本机绝对路径、已存在目录；拒绝相对路径、文件、UNC 网络路径和文件系统根目录。FIXED 沿用旧配置的目录验证，允许原来有效的 UNC 或盘符根目录；显式路径仍必须是同一个真实目录。非法参数返回 `INVALID_ARGUMENT`；越出非空 Roots 或偏离 FIXED 配置返回 `PATH_NOT_ALLOWED`。唯一 Root 是盘符根目录时也要求指定具体项目。Roots URI 只接受本机 `file:` 目录（空 authority 或 localhost），支持中文、空格和百分号编码；任一条目非法则整次列表失败。等价真实路径去重，重叠但不同的父子目录仍有歧义。非空 `nextCursor` 视为不完整列表。

`workspace_status` / `workspace_init` 的 `data` 保留 `workspace` 等原字段，新增 `resolution: {mode, source, projectRoot}`。`mode` 为 AUTO/FIXED，`source` 为 MCP_ROOTS/AGENT_ARGUMENT/CONFIG。后续相关调用显式携带返回的真实路径；切换项目重新检查状态。任务 ID 不会触发跨项目查找。

同一服务进程可按请求访问不同项目，各项目的任务、知识、历史和锁分别绑定；一次操作开始后保持该次目录，下一次才重新查询 Roots。客户端的 Roots 变更通知只作为可忽略提示，不触发后台查询；下一次工具调用读取新列表。不缓存“上一个项目”，不扫描磁盘，也不回退到 MCP 启动目录、JAR 或用户目录。Roots 查询默认最多等待 10 秒，失败后应恢复客户端列表再重试。Roots 不是操作系统沙箱，实际访问仍受进程权限约束。

## 生成的文件

```text
your-project/
├── AGENTS.md                         # 只增加受管入口（已有则保留其他内容）
├── task.md                           # 如已有，原样保留
└── LocalWorkspace/
    ├── workspace.json                # 工作区 ID、schemaVersion、创建时间
    ├── AGENT_GUIDE.md                # Agent 操作规范，可手动补充
    ├── tasks/
    │   └── task-<UUID>/
    │       ├── task.md               # 目标、范围、约束、计划、验收标准
    │       ├── STATUS.md             # 已完成、当前工作、阻塞、下一步、验证结果
    │       ├── handoff.md            # 最新交接摘要、决策及原因、注意事项
    │       └── archive/
    │           └── checkpoint-<时间>-<UUID>/
    │               ├── checkpoint.json
    │               ├── task.md
    │               ├── STATUS.md
    │               └── handoff.md
    └── knowledge/
        └── knowledge-<UUID>.md       # 可复用知识，含标签、关联任务
```

`workspace_init` 先生成指南和元数据；创建任务后才生成该任务的三个文档；保存知识后才有知识文件。多个任务各自隔离，没有隐式“当前任务”。任务与知识的 YAML 元数据由服务管理，正文是可读 Markdown。内部暂存目录和所有权标记用于故障恢复，不要手动挪动。

## 十个工具

除表中说明外，带请求模型的工具参数均为 `{"request": {...}}`，`projectRoot` 始终与 `request` 同级。枚举 `state`、`document`、`documents` 使用大写。

| 工具 | 用处 / 主要参数 |
| --- | --- |
| `workspace_status` | 可带顶层 projectRoot；检查定位结果、初始化状态及规则入口 |
| `workspace_init` | 顶层 `guidanceTarget`，默认 `agents`；幂等创建工作区并返回指南 |
| `task_create` | `task: {title, goal, scope, constraints, plan, acceptanceCriteria, tags}` 与 `nextSteps` |
| `task_list` | 可选 `state, query, offset, limit`；标题/标签过滤，默认排除 ARCHIVED |
| `context_load` | `taskId`，可选 `documents, knowledgeIds, maxChars`；返回正文、路径和 revision |
| `task_update` | `taskId, document, expectedRevision`，以及所选 `task` 或 `status` 内容；可选 `checkpoint` |
| `handoff_save` | **顶层** `taskId, expectedRevision, content: {summary, decisions, cautions, relatedFiles, verificationResults}`；decision 包含 `decision, reason` |
| `history_read` | `taskId`；无 checkpointId 时列出元数据，有时读取快照；可选 `documents, maxChars, offset, limit` |
| `knowledge_save` | `input: {title, tags, taskIds, body}`；更新时另带 `knowledgeId, expectedRevision` |
| `knowledge_search` | 可选 `query, tags, taskId, limit`；标题优先、标签其次、正文再次，返回摘要和知识 revision |

`status` 字段为 `{state, current, completed, blockers, nextSteps, relatedFiles, verificationResults}`。`state` 支持 ACTIVE、BLOCKED、COMPLETED、ARCHIVED。ACTIVE/BLOCKED 必须有下一步，BLOCKED 必须有阻塞原因。更新采用所选文档的**整体替换**，请携带需要保留的内容；不可通过 task_update 写 handoff 或指南。

## 接续一个任务

以下展示 MCP 工具参数（外层 JSON-RPC 由客户端负责）。示例省略 `projectRoot`，适用于唯一 Root 或 FIXED；缺失或多 Roots 时，由 Agent 在每份参数顶层加上已选项目路径：

1. Agent A 调用 `workspace_init`，然后 `task_create`：

```json
{"request":{"task":{"title":"认证模块","goal":"实现登录和刷新令牌","constraints":["使用 JWT"],"acceptanceCriteria":["过期令牌被拒绝"]},"nextSteps":["实现登录接口"]}}
```

2. 记录返回的 `taskId` 与 `revision`。完成登录后调用 `task_update`：

```json
{"request":{"taskId":"task-替换为实际ID","document":"STATUS","expectedRevision":"替换为最新64位revision","status":{"state":"ACTIVE","current":"登录已完成","completed":["登录接口及测试"],"nextSteps":["实现刷新令牌接口并补充过期测试"],"verificationResults":["认证测试通过"]}}}
```

3. 用更新后返回的 revision 调用 `handoff_save`：

```json
{"taskId":"task-替换为实际ID","expectedRevision":"替换为更新后的revision","content":{"summary":"登录已完成，继续刷新令牌","decisions":[{"decision":"使用 Redis 保存可撤销令牌","reason":"支持主动失效"}],"cautions":["先核对 AuthService 当前实现"]}}
```

4. Agent B 连接同一项目，调用 `task_list` 找到任务，然后 `context_load`。默认读取 GUIDE/TASK/STATUS/HANDOFF，`STATUS.md` 是下一步的依据。需要共享知识时先 `knowledge_search`，再把选中的 `knowledgeIds` 加到 context_load。Agent B 应核对实际代码和测试，再继续执行。

交接后若状态又更新，context_load 会提示交接陈旧；即使两个写入时间相同，也通过状态文件哈希识别。初始任务尚未保存交接时也会提醒。

## 修订号、历史与体积

任务 revision 由三个当前文档的原始字节哈希计算；知识 revision 由自己的文件字节计算。手动编辑也会改变修订号。写入时必须带最近一次读取返回的 expectedRevision；冲突返回 `REVISION_CONFLICT`，应重读、合并，再保存。不要直接替换成错误响应里的 revision 重试旧内容。

每次 `handoff_save` 和显式 `checkpoint=true` 更新，会保存**写入前**的三份文档及哈希；普通状态更新不产生快照。历史不自动加入上下文，也不自动删除。`history_read` 只读取，不回滚覆盖当前状态。即使当前任务文档损坏或缺失，仍能列出和读取完好的历史快照，供 Agent 对照修复。任务 ARCHIVED 只改变状态和默认可见性，原路径、知识关联和历史保留。

| 限制 | 默认值 |
| --- | --- |
| 单个 task / STATUS / handoff 文档（含元数据） | 8,192 Unicode code points |
| 指南 | 8,192 code points |
| 知识正文 | 16,384 code points |
| 上下文 / 历史响应预算 | 24,000 code points，可传 4,000–64,000 |
| 单个磁盘输入文件 | 256 KiB |
| 项目锁等待 | 5 秒 |

预算计算完整工具 JSON 正文，含 warnings/包装，排除 MCP JSON-RPC 外壳；emoji 按 code point 计数，不是 token。默认上下文不包含历史；知识只按指定 ID 加载。超预算返回 `BUDGET_EXCEEDED`，可以分开选文档或增加 maxChars；文档写入过大返回 `SUMMARY_REQUIRED`，旧文档不变，由 Agent 总结后重试。服务本身不调用模型压缩内容。

默认分页：任务/历史每页20、最大100；知识命中默认10、最大20，每条正文预览200字符。标签过滤为 AND；检索使用大小写不敏感的子串匹配。

## 配置与边界

可追加启动参数，例如 `--local-workspace.directory-name=TeamWorkspace`。只能是一个安全目录名，不能是路径；修改名字会选择另一个工作区，不自动迁移旧数据。已有同名非空目录如果没有有效 workspace.json 会拒绝初始化。指南不会在初始化重试时重置。

限额可通过 `--local-workspace.limits.document-chars=8192`、`guide-chars`、`knowledge-chars`、`context-chars` 配置。增大字符数仍受单文件256KiB读写上限约束，超限会在写入或建立快照前拒绝。标签等元数据也有解析复杂度限制，过大时返回 `SUMMARY_REQUIRED`，请减少标签和元数据后重试，已有内容保留。

路径限制在本次选定项目的工作区和允许安装的根目录规则文件内；拒绝穿越路径、符号链接、Windows junction / reparse point。使用 JVM 锁和跨进程文件锁串行化本工具的操作，写入采用同目录临时文件与原子替换。每份文件单独提交；快照提交后当前文档写入失败时，会在错误详情中返回已保存 checkpointId。初始化中途失败可用相同参数重试。

当前验证平台为 Windows + JDK17；协议客户端通过 STDIO 运行。未提供多用户权限系统、HTTP 服务、向量搜索、聊天历史导入、自动执行任务或文件系统 watcher。项目锁约束合作的 MCP 进程；外部编辑器不遵守锁，外部恶意修改路径与断电持久性不属于事务保证。建议按项目策略使用 Git 或备份保存这些可读文件，注意其中可能包含项目内部信息。

## 代码结构

`project` 每次请求定位与业务服务构建；`workspace` 初始化与规则入口；`task` 状态与修订；`history` 快照；`knowledge` 知识检索；`context` 预算组装；`storage` 安全路径/原子写/锁；`mcp` 协议适配。业务服务与 MCP 注册分开，测试既覆盖真实文件，也通过独立客户端启动产物进行验收。

设计与实施计划见 [docs/superpowers/specs](superpowers/specs) 和 [docs/superpowers/plans](superpowers/plans)。
