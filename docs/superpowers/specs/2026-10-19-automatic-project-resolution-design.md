# Local Workspace MCP 自动项目定位升级设计

日期：2026-10-19。

状态：用户于2026-10-19明确确认本书面设计，并要求制定实施计划；产品代码尚未修改。

位置说明：设计与计划均已获用户确认；2026-10-19恢复目标项目写权限后同步至开发目录。此前暂存件仅用于评审。

## 1. 目标与已确认需求

用户希望安装一次 MCP 后，不再为每个业务项目手动修改 project-root 配置。已确认：优先读取 MCP 客户端提供的 Roots；目录缺失或同时有多个项目时，由 Agent 从自身工作上下文传入当前项目路径；无法明确定位时返回提示；旧固定目录配置继续兼容。

成功条件：自动模式配置只保留 Java/JAR 参数。单一有效 Root 自动定位；缺失或多个目录时由 Agent 填写工具参数，用户无需修改连接配置。一次请求只访问选定的一个项目；不同项目的任务、知识、历史及锁隔离。

本升级不读取 Agent 的私有会话或窗口，不扫描磁盘猜项目，不引入 HTTP、数据库、模型调用或 Agent Hooks。客户端与 Agent 均未提供目录时，不承诺能够自动定位。

## 2. 选择与技术依据

使用 Java 进程启动目录虽然改动较小，但它可能是客户端安装目录、用户目录或 JAR 所在目录，不能可靠代表业务项目。采用已确认的 Roots 与 Agent 参数组合，保留固定目录作为兼容方式。

技术版本继续为 Java17 / Maven / Spring Boot3.5.16 / Spring AI1.1.8、同步 STDIO，保留现有十个工具，不增加产品依赖。

- MCP Roots 是可选能力，可能返回多个目录，并不保证指出活动项目：https://modelcontextprotocol.io/specification/2025-06-18/client/roots
- Spring AI 的工具方法可注入 McpSyncRequestContext：https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-annotations-server.html
- 已检查实际依赖 JAR：上下文提供 rootsEnabled()/roots()；服务配置支持 request-timeout；SDK 的 ListRootsResult 包含 roots/nextCursor。

## 3. 模式及兼容

**FIXED**：启动时设置了非空 local-workspace.project-root。启动时验证现有目录；无效配置仍启动失败。不查询 Roots、不跟随客户端切换。工具省略 projectRoot 时使用配置目录；传入时必须解析到同一真实目录，否则 PATH_NOT_ALLOWED。空白配置无效，不自动解释成 AUTO。

**AUTO**：完全省略启动配置 project-root。服务可正常启动并发现十个工具，但启动和列出工具不会初始化或写入任何项目。每次工具请求独立定位，不保留可变的进程级/会话级“当前项目”，也不沿用上次查询的 Roots。

所有十个工具增加可选的顶层 String 参数 projectRoot。现有 request、taskId、expectedRevision 等参数保持原位置；固定模式的旧请求不需要修改。

## 4. 自动模式选择规则

Agent 传入的 projectRoot 必须是非空、本机绝对路径、已存在目录，可解析为真实路径；不接受相对路径、文件、不存在目录、UNC 网络目录或文件系统根目录。无效参数返回 INVALID_ARGUMENT，无文件副作用。

| 客户端情况 | 未传 projectRoot | 已传有效 projectRoot |
| --- | --- | --- |
| 不支持 Roots | PROJECT_REQUIRED，提示 Agent 补充当前项目 | 使用 Agent 提供的目录 |
| 正常返回空 Roots | PROJECT_REQUIRED | 使用 Agent 提供的目录，作为目录信息缺失的补充 |
| 一个有效 Root | 自动使用它；若为文件系统根目录则要求具体项目 | 必须在该 Root 内，可明确选择子项目 |
| 多个有效 Roots | PROJECT_AMBIGUOUS，返回候选路径 | 必须在至少一个 Root 内，使用明确指定的目录 |
| Roots 查询失败、超时或无效 | ROOTS_UNAVAILABLE | 同样报错，不借参数绕过未知边界 |

Roots 非空时，用真实路径的 Path.startsWith 检查包含关系，不用字符串前缀；相似目录名和经别名解析后越界的目录必须拒绝。Roots 空列表或能力未提供时，Agent 显式传入的目录是选择依据；Roots 不等于操作系统沙箱，实际访问仍受进程权限约束。

Roots 只接受本机 file: 绝对目录 URI，authority 允许空或 localhost，拒绝远程 authority、query、fragment、opaque/相对 URI、不可访问目录和文件。支持中文、空格和百分号编码。等价真实路径去重；任一条目无效则整次列表不可用，不过滤后擅自改选项目。重叠但不同的 Roots 仍是多个候选。

本版本对非空 nextCursor 返回 ROOTS_UNAVAILABLE，提示提供完整列表；不能只取第一页后误认为只有一个项目。Roots 请求采用 SDK 的10秒 request-timeout。每次请求重新获取列表，不必依赖 Roots 变更通知修改全局状态。

不回退到 user.dir、JAR 位置、用户主目录、上一个项目，也不按任务标题猜目录。Agent 可在自己的项目执行环境查询工作目录，但不能把 MCP 进程的启动目录当作项目。Agent 自己也无法确定项目时才询问用户。

## 5. 组件与调用流程

- ProjectResolver：解析 FIXED/AUTO、验证补充路径、按决策表返回真实项目和来源，不写工作区。
- ClientRootsSource：封装当前 MCP 请求上下文的能力判断、列表查询和错误，使解析逻辑可独立测试。
- WorkspaceRuntimeFactory：对已选定的真实目录构建存储、项目锁、Repository 和业务服务；复用不可变配置、Clock、ObjectMapper、Markdown 编解码器。
- WorkspaceRuntime：一次请求持有的不可变服务集合，按调用创建，无无界运行时缓存或全局项目切换。
- MCP 工具层：由框架注入上下文，接收可选 projectRoot；在现有 ToolExecutor 错误包装内部完成解析和业务调用。
- WorkspaceProperties/WorkspaceConfiguration：允许未配置根目录时启动公共组件；固定配置仍提前验证；只有已绑定具体目录的配置才能进入业务 Repository。

调用顺序：接收参数 → 解析当前请求的项目 → 构建该目录 Runtime → 执行原业务服务的项目锁/版本校验/文件操作 → 返回原响应。

请求开始后即固定本次 Runtime；执行期间 Roots 变化不重定向已经进行的操作，下一次调用再查询新列表。相同真实目录继续使用原 JVM/OS 文件锁，不同项目不会共享业务状态。

## 6. 接口与错误

可选顶层参数示例：

```json
{"projectRoot":"C:/work/order-service","request":{"query":"认证"}}
```

任务、知识、快照仍按安全 ID 在所选项目中读取。找不到返回 NOT_FOUND，不去其他项目搜索或移动数据。

workspace_status / workspace_init 保留原 data 字段，新增可选 resolution：mode=FIXED/AUTO、source=CONFIG/MCP_ROOTS/AGENT_ARGUMENT、最终真实 projectRoot。原 workspace.projectRoot 仍保留。其他业务响应沿用原结构，不在预算计算后添加包装字段。

继续使用 ok/data/error/warnings 和失败时 MCP isError=true。新增：

- PROJECT_REQUIRED：没有目录；提示 Agent 提供 projectRoot。
- PROJECT_AMBIGUOUS：存在多个候选；details 返回候选路径，不默认使用第一项。
- ROOTS_UNAVAILABLE：查询失败、超时、不完整或条目无效；返回可读原因，异常栈留在 stderr。

越界/固定目录不一致沿用 PATH_NOT_ALLOWED，格式问题沿用 INVALID_ARGUMENT。解析失败时所有工具均不能写文件。

## 7. Agent 流程和数据兼容

自动模式示例移除启动时 project-root，Java/JAR 路径保持原状，固定模式示例继续提供。不自动修改实际用户配置。

服务 instructions、工具描述和新建指南要求：先 workspace_status；缺失/多目录时由 Agent 补充路径；记录返回的真实 projectRoot，后续相关调用显式携带，尤其多项目并行时；切换项目重新检查。FIXED 模式尊重绑定目录。

根目录受管入口可幂等补充项目定位规则，区块外原字节保留。已有 AGENT_GUIDE.md 的用户修改仍保留，不为升级而覆盖；当前会话从服务 instructions/工具描述取得新流程。

工作区仍位于每个项目的 LocalWorkspace/（或已有配置目录名）；文档 schemaVersion=1、ID、哈希、历史格式不变，无数据迁移。256KiB写前上限、元数据预校验、字数预算、损坏当前文档时仍能读历史、修订冲突、入口保护、原子发布和跨进程锁全部保留。

## 8. 验收

1. 无根目录配置可启动并列出原十个工具，启动/发现不写文件；固定模式无效目录仍启动失败。
2. 唯一 Roots 不传路径即可定位/初始化，返回 AUTO/MCP_ROOTS；无 Roots/空列表补路径后成功。
3. 多 Roots 未补路径明确报错且无写入；补充一个合法路径后只影响该项目。
4. 绝对路径、子项目、中文/空格/URI编码、重复/重叠 Roots，以及非法 URI、越界、字符串前缀陷阱、目录失效和不完整分页都有测试。
5. Roots 等待超时配置为10秒，超时后返回协议错误；集成测试允许有限调度误差，不要求墙钟时间精确小于10秒。不使用旧目录；测试客户端持续排空 stderr。
6. 同一服务进程 Roots 切换后访问新项目，不重启；两项目并行明确路径调用不串任务、知识、历史或锁。
7. FIXED 不请求 Roots，旧调用不改参数即可工作，工具尝试切换固定目录被拒绝。
8. 真实 tools/list schema 中 projectRoot 为可选且位于顶层，原业务参数位置及字符预算不变。
9. 打包 JAR 的独立客户端可处理服务端 roots/list 反向请求，并覆盖自动定位、无 Roots 补充、项目切换、A/B交接和同项目写竞争；stdout 只有 JSON-RPC。
10. 保留原73项覆盖，按新启动语义调整“缺少根目录必启动失败”用例，更新通用 JSON/Codex TOML 和中文说明，不修改真实业务项目或用户配置。

## 9. 后续阶段

本书面设计经用户评审确认后，使用 writing-plans 生成实施计划；计划需评审并选择执行方法后开始实现。会话中的推荐方案确认不代表已批准尚未生成的计划。本次未修改产品代码或安装产品依赖。
