# SmileCli Memory 阶段总结

## 1. 阶段定位

`React_ToolCall.md` 记录的是 SmileCli 从普通聊天推进到 ReAct + Tool Call 的过程。

`Plan_DAG.md` 记录的是在 ReAct 基础上增加 Planner、DAG、PlanExecuteAgent 的过程。

这一阶段的主题是 Memory，也就是让 Agent 不只依赖当前一次请求里的 messages，而是开始具备两类记忆能力：

```text
短期记忆
  -> 当前 conversationHistory
  -> 负责维持本轮会话上下文
  -> 太长时需要压缩

长期记忆
  -> 落盘保存的 MemoryEntry
  -> 跨会话、跨运行复用
  -> 可以手动保存，也可以在压缩时自动提取
```

这一步的核心目标不是让记忆系统一次性变得很智能，而是先把结构搭起来：

```text
Token 估算
  -> 短期历史压缩
  -> 手动保存长期记忆
  -> 查看长期记忆
  -> 压缩时自动提取长期记忆
  -> 每次请求时注入长期记忆
```

## 2. 为什么需要 Memory

在 ReAct 和 Plan & DAG 阶段，Agent 的主要上下文来自：

```java
conversationHistory
```

这能解决当前会话内的连续对话问题，但会遇到两个限制。

第一个限制是上下文窗口有限。随着用户持续对话、工具返回内容、Plan 执行结果加入历史，`conversationHistory` 会越来越长。超过模型上下文后，请求就会失败，或者不得不丢弃旧消息。

第二个限制是会话之间没有记忆。比如用户已经多次说明：

```text
这个项目是为了学习 paicli 的实现
希望一步一步实现，不希望一次性帮他写完
```

这类信息不应该每次都重新说。它适合保存成长期记忆，在以后需要时重新注入给 Agent。

因此 Memory 阶段解决的是两个问题：

```text
短期记忆太长怎么办
长期稳定信息怎么保存和复用
```

## 3. 当前核心模块

### TokenBudget

`TokenBudget` 位于：

```text
src/main/java/edu/sdu/smilecli/memory/TokenBudget.java
```

它负责粗略估算当前 messages 占用的 token。

当前核心方法是：

```java
estimateMessagesTokens(List<LlmClient.Message> messages)
```

它会估算：

```text
message.role
message.content
message.toolCallId
message.toolCalls.id
message.toolCalls.function.name
message.toolCalls.function.arguments
每条 message 的固定额外开销
```

当前文本估算方法是：

```text
中文约 1.5 字/token
英文约 4 字符/token
```

另外还提供：

```java
getAvailableTokens(List<LlmClient.Message> messages)
```

用来估算当前会话还剩多少上下文 token。

这个模块的定位是“粗略预算”，不是精确 tokenizer。当前阶段用它判断是否需要压缩即可。

### ConversationHistoryCompactor

`ConversationHistoryCompactor` 位于：

```text
src/main/java/edu/sdu/smilecli/memory/ConversationHistoryCompactor.java
```

它负责短期记忆压缩。

当前触发阈值是：

```java
INTTRIGGER_TOKENS = 700_000
```

这个阈值从之前的 `800_000` 调低到 `700_000`，目的是给实际请求中的额外内容留空间，例如：

```text
长期记忆注入
tool definitions
模型输出 token
估算误差
```

压缩流程是：

```text
compactIfNeeded(conversationHistory, longTermMemory)
  -> 估算 conversationHistory token
  -> 未超过阈值则跳过
  -> 找出 user 消息的位置
  -> 保留最近 retainRecentRounds 轮用户输入
  -> 将更早的 oldMsgs 作为压缩对象
  -> 从 oldMsgs 中提取可长期保存的记忆
  -> 调用 LLM 总结 oldMsgs
  -> 用摘要替换旧历史
  -> 保留最近几轮原始消息
```

重建后的结构大致是：

```text
system prompt
user: [已压缩的历史对话摘要] + summary
assistant: 好的，我已了解之前的上下文，请继续。
最近几轮原始 user / assistant / tool 消息
```

这里的重点是：旧消息不会直接丢掉，而是先被压缩为摘要，保证后续对话仍能知道之前发生过什么。

### MemoryEntry

`MemoryEntry` 位于：

```text
src/main/java/edu/sdu/smilecli/memory/MemoryEntry.java
```

它表示一条长期记忆。

当前字段包括：

```text
id
content
scope
projectPath
createdTime
```

其中 `scope` 当前有两类：

```text
project
global
```

`project` 表示只对当前项目有意义，例如：

```text
SmileCli 是一个学习 paicli 实现的 Java CLI Agent 项目。
```

`global` 表示对所有项目都有意义，例如：

```text
用户希望回答使用中文。
```

`projectPath` 用于区分不同项目的 project 级长期记忆。

### LongTermMemory

`LongTermMemory` 位于：

```text
src/main/java/edu/sdu/smilecli/memory/LongTermMemory.java
```

它负责长期记忆的内存维护和磁盘持久化。

当前默认存储路径是：

```text
${user.home}/.smilecli/memory/long_term_memory.json
```

运行时，长期记忆维护在：

```java
List<MemoryEntry> memories
```

启动时：

```text
loadFromDisk()
```

保存时：

```text
store(content, scope)
  -> 过滤空内容
  -> 规范化 scope
  -> 检查是否已经存在
  -> 创建 MemoryEntry
  -> 加入 memories
  -> saveToDisk()
```

当前去重逻辑是精确去重：

```text
content 相同
scope 相同
project 记忆还要求 projectPath 相同
global 记忆不再比较 projectPath
```

这说明当前已经避免了最简单的重复保存问题。后续可以把精确去重升级为语义去重或向量相似度判断。

## 4. 短期记忆压缩流程

短期记忆压缩发生在 `Agent.run()` 中。

用户输入进入后：

```java
conversationHistory.add(LlmClient.Message.user(userInput));
```

随后在每次 ReAct 循环调用 LLM 之前执行：

```java
historyCompactor.compactIfNeeded(conversationHistory, longTermMemory);
```

压缩判断不是每次都会真正压缩。只有当 `conversationHistory` 的估算 token 超过阈值时才会继续。

压缩时不会保留全部旧消息，而是保留最近几轮对话，将更早的消息总结成一段摘要。

这个设计的意义是：

```text
短期历史继续保持连续性
旧消息不直接塞满上下文
模型仍能知道早期对话中的关键目标和结论
```

当前需要注意的是：压缩逻辑更适合处理“多轮对话累计变长”的情况。对于“单次工具返回超大内容”的问题，后续更适合在工具层做结果截断或工具结果摘要。

## 5. 压缩时自动提取长期记忆

在短期压缩过程中，当前已经加入了长期记忆提取：

```java
List<MemoryEntry> memories = extractLongTermMemories(oldMsgs);
for (MemoryEntry memory : memories) {
    longTermMemory.store(memory.content(), "project");
}
```

设计思路是合理的，因为压缩时正好要处理即将从短期历史中移除的旧消息。此时提取长期记忆可以避免重要信息只存在于旧上下文里。

`extractLongTermMemories()` 会要求 LLM 从旧对话中提取适合长期保存的信息，并只返回 JSON 数组：

```json
[
  {
    "content": "记忆内容",
    "scope": "project"
  }
]
```

然后代码会：

```text
去掉 ```json 代码块标记
解析 JSON 数组
读取 content 和 scope
构造 MemoryEntry
返回候选记忆
```

当前这一步的重点是把“短期历史压缩”和“长期信息沉淀”连接起来。

不过这里仍有一个后续可以改进的点：`extractLongTermMemories()` 当前返回的是 `MemoryEntry`，但它本质上还只是“候选记忆”。更清晰的设计可以是：

```java
private record MemoryCandidate(String content, String scope) {}
```

然后由 `LongTermMemory.store()` 负责创建正式的 `MemoryEntry`。

## 6. 手动长期记忆

手动长期记忆通过 CLI 命令完成。

当前支持：

```text
/save <内容>
/save --global <内容>
/memory list
```

流程是：

```text
Main
  -> 解析 /save 命令
  -> 判断 scope
  -> agent.saveLongTermMemory(toSave, scope)
  -> LongTermMemory.store(content, scope)
  -> 写入 long_term_memory.json
```

默认：

```text
/save <内容>
```

保存为 `project` 级长期记忆。

如果使用：

```text
/save --global <内容>
```

保存为 `global` 级长期记忆。

查看长期记忆：

```text
/memory list
```

会调用：

```java
agent.listLongTermMemory()
```

然后打印每条记忆的 `scope` 和 `content`。

这一步完成后，SmileCli 已经有了基础的“用户主动告诉 Agent 需要记住什么”的能力。

## 7. 长期记忆注入

长期记忆保存下来以后，还需要在 LLM 请求时重新注入，否则模型不会知道这些记忆存在。

当前在 `Agent.run()` 中构造了一个临时请求历史：

```java
List<LlmClient.Message> longTermHistory = new ArrayList<>();
longTermHistory.add(conversationHistory.get(0));
longTermHistory.add(LlmClient.Message.system(buildLongTermMemoryContext()));
longTermHistory.addAll(conversationHistory.subList(1, conversationHistory.size()));
```

也就是说：

```text
conversationHistory
  -> 保存真实短期对话历史

longTermHistory
  -> 本次实际发送给 LLM 的 messages
  -> 在 system prompt 后临时插入长期记忆
```

这点很重要：长期记忆没有直接永久写回 `conversationHistory`，而是作为本次请求的额外上下文临时注入。

当前 `buildLongTermMemoryContext()` 是全量注入：

```text
以下是可参考的长期记忆：
- ...
- ...
```

这能先跑通长期记忆复用链路。后续可以改为：

```text
根据当前 userInput 检索相关记忆
只注入最相关的几条
最多注入固定数量
```

## 8. Git 变更脉络

根据 Git 提交记录，Memory 阶段大致经历了以下几步。

### 初始 memory 标记

相关提交：

```text
158fb16 memory
```

这一阶段只是开始在 `Agent` 中引入 memory 思路，为后面的短期压缩和长期记忆做准备。

### Token 预算与短期压缩雏形

相关提交：

```text
8e580e7 回顾
323bd86 短期记忆压缩完成
```

这一阶段主要完成：

1. 新增 `TokenBudget`。
2. 为 `LlmClient.Message` 设计 token 粗估逻辑。
3. 新增 `ConversationHistoryCompactor`。
4. 在上下文过长时调用 LLM 摘要旧历史。
5. 保留最近几轮对话，避免旧消息无限增长。

这一步解决的是“短期历史太长”的问题。

### 长期记忆设计

相关提交：

```text
a6d783e 长期记忆的设计完成
```

这一阶段主要完成：

1. 新增 `MemoryEntry`。
2. 新增 `LongTermMemory`。
3. 明确 `project/global` 两级记忆。
4. 设计长期记忆的磁盘存储路径。
5. 增加 `/memory` 查看 token 使用情况。

这一步把长期记忆从概念变成了独立模块。

### 手动存储与查看

相关提交：

```text
0215725 长期记忆的存储和查看完成
846905c 长期记忆的存储和查看完成
```

这一阶段主要完成：

1. 在 `Main` 中加入 `/save` 命令。
2. 支持 `/save --global`。
3. 在 `Agent` 中加入 `saveLongTermMemory()`。
4. 支持 `/memory list` 查看长期记忆。
5. 完善 `MemoryEntry` 构造和 `LongTermMemory` 存取。

这一步完成了用户主动保存长期记忆的闭环。

### 自动提取与长期记忆注入

相关提交：

```text
31ad75c 长期记忆的存储、查看、提取完成（提取目前是全量提取，这部分以及存储长期记忆的时候避免重复部分->之后改成基于向量搜索的）
```

这一阶段主要完成：

1. 在短期压缩时调用 `extractLongTermMemories()`。
2. 用 LLM 从旧对话中提取长期记忆候选。
3. 解析 LLM 返回的 JSON 数组。
4. 将提取结果写入 `LongTermMemory`。
5. 每次请求时将长期记忆注入到临时 messages 中。
6. 初步处理长期记忆重复保存问题。

这一步让 Memory 系统从“手动保存”推进到“自动沉淀 + 请求时复用”。

## 9. 当前完成度

从功能角度看，Memory 阶段已经完成了第一版闭环：

```text
短期历史可估算 token
短期历史过长可压缩
长期记忆可手动保存
长期记忆可查看
压缩时可自动提取长期记忆
长期记忆可注入本次 LLM 请求
```

现在 SmileCli 的 Agent 已经不只是：

```text
conversationHistory 驱动的一次会话 Agent
```

而是开始拥有：

```text
短期上下文
长期持久化记忆
短期到长期的信息沉淀
长期到请求上下文的信息注入
```

## 10. 当前仍需注意的问题

### 长期记忆目前是全量注入

`buildLongTermMemoryContext()` 当前读取：

```java
longTermMemory.list()
```

然后把所有长期记忆都注入请求。

这能帮助第一版跑通，但长期记忆变多后会有两个问题：

```text
token 变大
无关记忆干扰当前问题
```

后续应改成检索式注入，例如：

```text
基于 userInput 搜索相关记忆
最多注入 3-5 条
每条限制最大长度
```

再后面可以升级为向量检索。

### token 估算仍只估 conversationHistory

当前压缩阈值从 `800_000` 调整到 `700_000`，给长期记忆和工具定义留出 buffer。

这是合理的工程折中。

不过严格来说，实际请求是：

```text
conversationHistory
+ 长期记忆 system message
+ tool definitions
```

所以后续更精确的做法是估算实际请求体：

```text
estimateMessagesTokens(requestMessages)
+ estimateToolsTokens(tools)
+ reservedCompletionTokens
```

当前阶段先用保守阈值是可以的。

### 自动提取返回 MemoryEntry 稍微混淆

当前 `extractLongTermMemories()` 返回：

```java
List<MemoryEntry>
```

但这些对象其实还没有真正进入长期记忆库，只是 LLM 提取出的候选结果。

更清晰的命名和结构是：

```java
private record MemoryCandidate(String content, String scope) {}
```

然后由 `LongTermMemory.store()` 负责正式创建 `MemoryEntry`。

### 自动提取时 scope 还没有完全利用

LLM 返回 JSON 中包含：

```json
"scope": "project"
```

当前存储时仍然默认存为 project。

后续可以使用提取结果中的 scope：

```java
longTermMemory.store(memory.content(), memory.scope());
```

同时要谨慎让 LLM 自动写入 global。比较稳的策略是：

```text
自动提取默认 project
global 更适合用户手动 /save --global
```

### /save 命令匹配可以更严谨

当前使用：

```java
input.toLowerCase().startsWith("/save")
```

这会让 `/savexxx` 也进入保存分支。

后续可以改成：

```java
String lowerInput = input.toLowerCase();
if (lowerInput.equals("/save") || lowerInput.startsWith("/save ")) {
    ...
}
```

`/plan` 也有类似问题。

### store 返回 void，无法告诉 CLI 是否真的保存

当前 `LongTermMemory.store()` 返回 `void`。

如果内容为空或已经存在，它会直接 return，但 `Main` 仍然会打印“已保存”。

后续可以改成：

```java
public boolean store(String content, String scope)
```

这样 CLI 可以区分：

```text
保存成功
内容为空
已经存在，未重复保存
```

### 压缩和提取都依赖 LLM

短期摘要和长期记忆提取都调用 LLM。

这意味着压缩时可能发生额外网络请求，也可能失败。

当前长期记忆提取失败不会阻断压缩，这是合理的。摘要失败会跳过压缩，也是合理的。

后续可以考虑：

```text
更明确的日志
压缩失败后的降级策略
避免在 tool call 链中频繁压缩
```

## 11. 建议下一步路线

建议下一步按这个顺序推进：

1. 保持当前全量注入方案，先确认长期记忆能真实影响回答。
2. 给长期记忆注入加数量限制，例如最多 5 条。
3. `LongTermMemory.store()` 改成返回 boolean，改善 CLI 提示。
4. 将自动提取结果从 `MemoryEntry` 改成 `MemoryCandidate`。
5. 自动提取默认只存 project，global 继续由用户手动保存。
6. 给 `/save` 和 `/plan` 的命令匹配加边界判断。
7. 为 `LongTermMemory.store()`、`extractLongTermMemories()`、`compactIfNeeded()` 补 fake LLM 测试。
8. 做简单关键词检索，替换全量注入。
9. 最后再考虑向量数据库和语义去重。

## 12. 当前阶段评价

Memory 阶段的关键进展是：SmileCli 开始从“只保存当前对话历史”的 Agent，变成“能管理上下文生命周期”的 Agent。

现在项目里已经出现了比较完整的记忆分层：

```text
conversationHistory
  -> 当前会话短期记忆

ConversationHistoryCompactor
  -> 短期记忆过长时压缩

LongTermMemory
  -> 长期记忆落盘保存

MemoryEntry
  -> 长期记忆数据结构

TokenBudget
  -> token 预算与压缩触发
```

从架构演进看，SmileCli 当前已经走过了三条主线：

```text
ReAct Tool Call
  -> 让 Agent 能行动

Plan & DAG
  -> 让 Agent 能规划复杂任务

Memory
  -> 让 Agent 能保留和复用经验
```

下一阶段的重点不是立刻做复杂向量数据库，而是先把长期记忆的“存、查、注入、去重、测试”稳定下来。等这条链路可靠后，再升级为基于向量检索的相关记忆召回。
