# Local Workspace 使用流程

## 开始或继续任务
0. 自动模式每次根据客户端 Roots 或顶层 projectRoot 定位项目。PROJECT_REQUIRED / PROJECT_AMBIGUOUS 时，从 Agent 自身执行环境补充当前项目绝对路径；不使用 MCP 进程启动目录、JAR 目录或上次项目猜测。ROOTS_UNAVAILABLE 时先恢复客户端完整 Roots，不绕过失败查询。
   记录 workspace_status / workspace_init 返回的真实 projectRoot，后续相关调用显式携带，尤其多项目并行时。切换项目重新检查状态；固定模式只允许配置的项目。
1. 调用 workspace_status；未初始化时调用 workspace_init。
2. 使用 task_list 查找对应事项；新事项使用 task_create，不合并无关任务。
3. 明确 taskId 后调用 context_load，再核对实际项目文件。
4. 多个任务都可能匹配而用户意图不明时，向用户确认。

## 保存进度
- 在阶段完成、决策改变、出现阻塞时使用 task_update。
- 保存目标、约束、关键进展、相关文件、验证结果和明确下一步。
- 使用最近一次读取返回的 revision 作为 expectedRevision。
- REVISION_CONFLICT 表示上下文已改变，先重读并合并，不强制覆盖。
- 只记录实际运行过的验证，不伪造完成状态。

## 交接与恢复
- 离开任务前使用 handoff_save 保存决策原因、接手注意事项、相关文件和验证结果。
- 最新进度和下一步以 STATUS.md 为准，交接提供背景。
- 交接比状态陈旧时，先核对状态及实际代码，不能照搬旧下一步。
- 未保存的对话内容不会自动成为工作区状态；中断后核对实际项目。

## 知识与长度预算
- 值得跨任务复用的经验使用 knowledge_save，使用 knowledge_search 查找。
- 默认上下文不加载历史；需要追查时使用 history_read。
- SUMMARY_REQUIRED：整理有效摘要后重新提交；先保留约束、阻塞和下一步。
- BUDGET_EXCEEDED：按文档分批读取或提高允许预算，不把省略的内容当作不存在。
- Agent 负责总结和执行，MCP 负责组织和保存；历史不会自动删除。
