# GitHub 项目简介与发布文案

下面的文案可用于创建仓库时的 Description、仓库首页 About 和项目介绍。README 已使用英文首页，并提供[中文版本](../README.zh-CN.md)。

## 建议的仓库名称

`local-workspace-mcp`

名称直接体现本地工作区与 MCP，和 README 标题 Local Workspace MCP 对应。本地项目目录无需改名。

## GitHub Description / About

推荐英文版，方便不同语言的开发者直接理解用途：

```text
Project-local task memory for AI coding agents. Save goals, progress, decisions and knowledge as Markdown, then resume across chats and MCP clients. Built with Java and Spring AI.
```

中文版：

```text
面向 AI 编程 Agent 的本地工作区 MCP：用 Markdown 保存任务目标、进度、决策和知识，让新会话或另一个 Agent 接着做。基于 Java 和 Spring AI。
```

## 首页短标语

英文：

> Carry your task across chats and AI coding agents.

中文：

> 换个会话、换个 Agent，接着做同一个任务。

## Topics

建议添加以下与实际用途、协议和技术栈相关的标签：

```text
mcp
mcp-server
model-context-protocol
ai-agents
agent-memory
agent-handoff
context-engineering
local-first
markdown
java
spring-boot
spring-ai
developer-tools
```

仓库首页 About 的设置入口可以添加 Topics。GitHub 支持通过主题查找项目；标签要求小写字母、数字或连字符，单个不超过 50 字符，最多 20 个。依据：[GitHub 官方 Topics 文档](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/classifying-your-repository-with-topics)。

## 中文项目介绍

### 标题

Local Workspace MCP：让不同 AI Agent 接续同一个开发任务

### 正文

用 AI 写项目时，一个任务经常做不完就要换会话。换个 Agent 软件后，又得重新解释需求、当前进度和之前的决策。

我做了 Local Workspace MCP，把任务的目标、约束、进度、下一步、交接记录和可复用知识保存成项目里的 Markdown 文件。Agent 主动调用 MCP 保存，新会话或另一个兼容客户端再读取这些状态，核对代码后继续工作。

目前支持多任务、自动项目定位、修订号冲突检查、历史检查点和按预算加载上下文。使用 Java 17、Spring Boot 和 Spring AI 实现，通过本地 STDIO 接入，无需数据库或模型 Key。

适合长任务、跨会话开发，以及在不同 Agent 之间交接项目。欢迎试用，尤其希望收到不同 MCP 客户端的接入体验和实际交接反馈。

发布这段介绍时，在末尾附上实际 GitHub 仓库链接。

## English introduction

### Title

Local Workspace MCP — task handoff across AI coding agents

### Copy

A coding task often outlasts a single chat. Local Workspace MCP gives that task a home inside your project.

Agents save goals, constraints, progress, next steps, handoff notes and reusable knowledge as readable Markdown. A new chat or another compatible MCP client can load the saved state, check the code and continue.

Built with Java 17, Spring Boot and Spring AI, it runs locally over STDIO with no database or model API key. It includes per-task files, automatic project selection, revision checks, historical checkpoints and bounded context loading.

I would love feedback on real task handoffs and compatibility with different MCP clients.

Add the actual repository link when sharing.

## Demo

建议录制一次 45–60 秒的真实交接，放在 README 的交接示例附近。以下是录制提纲，需实际执行后再使用录屏或截图。

1. **展示任务。** 在测试项目中让 Agent A 创建“登录与刷新令牌”任务，展示返回的 taskId。
2. **保存进度。** 完成一个真实阶段后更新 STATUS.md，写清已完成工作、实际验证结果和下一步，再保存交接。
3. **查看文件。** 展示 LocalWorkspace/tasks 下的 task.md、STATUS.md 和 handoff.md。
4. **切换会话或客户端。** 新会话连接同一项目，调用 task_list 和 context_load。
5. **确认接续信息。** 让 Agent B 复述保存的约束与下一步，核对代码后继续。若使用不同客户端，明确展示两个客户端名称。

建议录屏标题：**Save in one agent. Continue in another.**

首页优先讲清用途、价值和开始方式，详细协议保留在[技术参考](reference.zh-CN.md)。这种信息安排参考 [GitHub 官方 README 文档](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes)。
