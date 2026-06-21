# SmileCli 项目思路与实现过程

## 1. 项目定位

SmileCli 是一个用于学习和实现 Agent CLI 的 Java 项目，参考对象是 `paicli`。

当前目标不是一次性复刻完整的 `paicli`，而是按阶段理解并实现核心能力：

1. 先实现纯文本 LLM 对话。
2. 再接入 tool calling。
3. 后续再考虑文件安全、命令审批、上下文管理、记忆、RAG、TUI 等高级能力。

目前项目已经从纯文本聊天推进到 tool call 接线阶段。

## 2. 当前核心架构

项目当前主链路是：

```text
Main
  -> Agent
      -> LlmClient / DeepSeekClient
      -> ToolRegistry
```

各模块职责如下。

### Main

`Main` 是 CLI 入口。

它现在负责：

1. 从 `.env` 中读取 `DEEPSEEK_API_KEY`。
2. 创建 `DeepSeekClient`。
3. 创建 `ToolRegistry`。
4. 创建 `Agent`。
5. 使用 JLine 读取用户输入。
6. 处理基础命令，例如 `/exit`、`/clear`。
7. 把普通用户输入交给 `Agent.run()`。
8. 打印 Agent 的最终回复。

`Main` 不直接处理 LLM 请求，也不直接执行工具。这样可以让 CLI 输入输出和 Agent 逻辑分开。

### LlmClient

`LlmClient` 是 LLM 客户端抽象。

它定义了几类核心数据结构：

```text
Message
Tool
ToolCall
ChatResponse
```

它们分别对应 OpenAI-compatible Chat API 中的主要结构。

`Message` 表示对话消息，包含：

```text
role
content
toolCalls
toolCallId
```

常见 role 有：

```text
system
user
assistant
tool
```

`Tool` 表示提供给模型的工具定义，包含：

```text
name
description
parameters
```

`ToolCall` 表示模型返回的一次工具调用请求，包含：

```text
id
function.name
function.arguments
```

这里的 `id` 不是工具名，而是本次工具调用的编号。执行完工具后，程序需要用这个 id 构造 `tool` 消息，把结果回传给模型。

`ChatResponse` 表示一次 LLM 调用结果，包含：

```text
content
toolCalls
```

如果模型没有调用工具，就只返回 content。如果模型需要工具，就返回 toolCalls。

### DeepSeekClient

`DeepSeekClient` 是当前的 LLM 客户端实现。

它负责：

1. 把 `List<Message>` 转成请求体里的 `messages`。
2. 把 `List<Tool>` 转成请求体里的 `tools`。
3. 发送 HTTP 请求到 DeepSeek Chat Completions API。
4. 解析普通文本回复。
5. 解析响应中的 `message.tool_calls`。

普通文本响应时，主要读取：

```text
choices[0].message.content
```

工具调用响应时，主要读取：

```text
choices[0].message.tool_calls
```

每个 tool call 会被解析为：

```java
new ToolCall(id, new ToolCall.Function(name, arguments))
```

这样 `Agent` 才能根据工具名和参数执行本地工具。

### Agent

`Agent` 是当前项目的对话与工具调用编排层。

它维护：

```text
conversationHistory
```

初始化时会加入 system prompt。用户每输入一句话，`Agent` 会把用户消息加入历史，然后进入一个循环。

当前循环逻辑是：

```text
1. 调用 LLM，传入 conversationHistory 和工具定义
2. 如果 LLM 返回 toolCalls：
   1. 把 assistant 的 tool_calls 消息加入历史
   2. 遍历 toolCalls
   3. 调用 ToolRegistry.executeTool()
   4. 把工具结果作为 role=tool 的消息加入历史
   5. 继续下一轮 LLM 调用
3. 如果 LLM 没有返回 toolCalls：
   1. 把 assistant 文本回复加入历史
   2. 返回最终回复给 Main
```

这就是一个简化版 ReAct 循环。

`MAX_ITERATIONS` 用来避免模型无限调用工具。

### ToolRegistry

`ToolRegistry` 是工具注册与执行中心。

它内部维护：

```java
Map<String, Tool> tools
```

每个内部工具包含：

```text
name
description
parameters
executor
```

其中：

1. `name` 是工具名，会和模型返回的 `function.name` 对应。
2. `description` 用来告诉模型工具的用途。
3. `parameters` 是 JSON Schema，用来告诉模型工具需要哪些参数。
4. `executor` 是本地实际执行逻辑。

当前已经注册的工具包括：

```text
file_read
file_write
file_delete
file_rename
file_list
execute_command
```

`getToolDefinitions()` 会把内部工具转换为 `LlmClient.Tool`，用于发给模型。

`executeTool(name, arguments)` 会：

1. 根据 name 找到工具。
2. 把模型返回的 arguments JSON 字符串解析成 `Map<String, String>`。
3. 调用工具 executor。
4. 返回工具执行结果。

## 3. Tool Call 的完整数据流

一次工具调用的理想流程如下。

### 第一步：用户输入

用户在 CLI 输入：

```text
请读取 pom.xml 并告诉我这个项目叫什么
```

`Main` 调用：

```java
agent.run(input)
```

### 第二步：Agent 调用 LLM

`Agent` 把用户消息加入历史，然后调用：

```java
llmClient.chat(conversationHistory, toolRegistry.getToolDefinitions())
```

### 第三步：DeepSeekClient 构造请求

请求体中包含：

```json
{
  "model": "...",
  "messages": [...],
  "tools": [...]
}
```

其中 `tools` 里包含 `file_read` 等工具定义。

### 第四步：模型返回 tool_calls

模型可能返回：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "",
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "file_read",
              "arguments": "{\"file_path\":\"pom.xml\"}"
            }
          }
        ]
      }
    }
  ]
}
```

字段名如 `choices`、`message`、`tool_calls`、`function`、`name`、`arguments` 是协议字段，基本固定。

变化的是字段值，例如：

```text
id
function.name
function.arguments
```

### 第五步：Agent 执行工具

`DeepSeekClient` 把响应解析成 `ChatResponse`。

`Agent` 发现：

```java
response.hasToolCalls()
```

于是执行：

```java
toolRegistry.executeTool(
    toolCall.function().name(),
    toolCall.function().arguments()
)
```

### 第六步：工具结果回填历史

工具执行完成后，Agent 构造 tool 消息：

```java
Message.tool(result, toolCall.id())
```

这个消息对应 API 中的：

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "工具执行结果..."
}
```

`tool_call_id` 必须对应之前模型返回的 tool call `id`。

### 第七步：继续调用 LLM

Agent 带着工具结果继续调用 LLM。

模型看到工具结果后，生成最终回答。

## 4. 当前已经完成的内容

目前已经完成：

1. Maven Java 项目骨架。
2. `LlmClient` 抽象。
3. `DeepSeekClient` 基础 HTTP 调用。
4. 普通文本对话。
5. JLine CLI 输入循环。
6. `Agent` 对话历史维护。
7. `/clear` 清空历史并恢复 system prompt。
8. `ToolRegistry` 内置工具注册。
9. 工具定义转换为 `LlmClient.Tool`。
10. `DeepSeekClient` 请求体携带 tools。
11. `DeepSeekClient` 响应体解析 `tool_calls`。
12. `Agent` 根据 toolCalls 执行工具并继续循环。

## 5. 当前仍需注意的问题

### 密钥安全

`.env` 不能提交到 Git。测试代码中也不能硬编码 API key。

如果真实 key 曾经出现在代码里，应当去平台后台重置。

### 工具安全

当前工具里存在危险能力：

```text
file_write
file_delete
file_rename
execute_command
```

一旦 tool call 跑通，模型可能自动触发这些能力。

建议第一阶段只开放：

```text
file_read
file_list
```

确认闭环稳定后，再给危险工具加入人工确认。

### 路径语义

目前文件工具依赖 Java 进程的当前工作目录。

更好的设计是让 `ToolRegistry` 接收明确的 `projectRoot`：

```java
new ToolRegistry(Path.of(System.getProperty("user.dir")))
```

所有文件路径都基于 `projectRoot` 解析。

### 测试方式

目前测试还比较初级。

建议优先测试：

1. `ToolRegistry.getToolDefinitions()` 是否正确生成工具定义。
2. `ToolRegistry.executeTool()` 是否能解析 arguments 并执行工具。
3. `DeepSeekClient` 是否能正确解析包含 tool_calls 的模拟响应。
4. `Agent` 在 fake LLM 下是否能完成一次工具调用循环。

`Main.main()` 不适合直接作为单元测试，因为它会进入交互式 CLI 循环。

## 6. 推荐下一步路线

建议下一步按这个顺序推进：

1. 修稳 `ToolRegistry.executeTool()`，空参数也要执行工具，不要返回 null。
2. 暂时只开放 `file_read` 和 `file_list`。
3. 手动测试一次完整 tool call：

```text
请读取 pom.xml 并告诉我这个项目叫什么
```

4. 如果能完成“模型请求工具 -> 本地执行工具 -> 模型总结结果”，说明第一版 tool call 闭环跑通。
5. 增加危险工具审批机制。
6. 明确 `projectRoot` 和路径保护。
7. 再继续扩展文件写入、命令执行、代码搜索等能力。

## 7. 当前项目阶段评价

当前 SmileCli 已经不是单纯的 LLM 聊天 demo，而是一个简化版 Agent CLI 雏形。

它已经具备 Agent 的三个关键结构：

```text
对话历史
工具定义
工具执行循环
```

下一阶段的重点不是继续堆功能，而是让 tool call 闭环稳定、安全、可测试。

