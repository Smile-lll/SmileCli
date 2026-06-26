# SmileCli Plan & DAG 阶段总结

## 1. 阶段定位

`React_ToolCall.md` 总结的是前一阶段：让普通 ReAct Agent 能够完成“LLM 生成 tool call -> 本地执行工具 -> 把工具结果回传给 LLM -> 得到最终回答”的闭环。

这一阶段的重点从“单轮/多轮工具调用”继续往前推进，开始实现更复杂任务的规划与执行：

```text
用户复杂目标
  -> Planner 生成任务计划
  -> ExecutionPlan / Task 表达任务和依赖
  -> DAG 校验和排序
  -> PlanExecuteAgent 按顺序执行每个 task
  -> Main 通过 /plan 接入 CLI
  -> 执行结果回填到普通 ReAct Agent 上下文
```

也就是说，原来的 `Agent` 更像是一个即时反应式执行器；现在新增的 `PlanExecuteAgent` 更像是一个“先规划、再执行”的子 Agent。

## 2. 为什么需要 Plan & DAG

普通 ReAct 循环适合处理局部任务，比如读一个文件、写一个文件、执行一条命令、再总结结果。

但对于类似下面这种任务：

```text
在当前目录创建一个 Spring Boot 项目，并验证是否创建成功
```

如果完全依赖一个 ReAct 循环，模型可能会边想边做，过程不够稳定，也不容易展示中间状态。

Plan & DAG 阶段的目标是先把复杂任务拆成多个明确步骤：

```text
task_1: 分析项目需求
task_2: 创建项目结构，依赖 task_1
task_3: 写入 pom.xml，依赖 task_2
task_4: 写入启动类，依赖 task_2
task_5: 验证项目，依赖 task_3/task_4
```

这样程序就可以知道：

1. 有哪些任务。
2. 每个任务是什么类型。
3. 每个任务依赖哪些前置任务。
4. 哪些任务必须先执行，哪些任务可以后执行。
5. 执行失败时应该在哪个 task 停下来。

## 3. 当前核心模块

### Planner

`Planner` 位于：

```text
src/main/java/edu/sdu/smilecli/plan/Planner.java
```

它的职责是把用户目标转换成 `ExecutionPlan`。

主流程是：

```text
createPlan(goal)
  -> 判断是否是简单任务
  -> 简单任务生成 minimal plan
  -> 复杂任务调用 LLM 生成 plan JSON
  -> parsePlan(goal, planJson)
  -> 构建 ExecutionPlan
```

复杂任务时，`Planner` 会给 LLM 一个规划 prompt，要求模型只返回 JSON：

```json
{
  "summary": "任务摘要",
  "tasks": [
    {
      "id": "task_1",
      "description": "任务描述",
      "type": "FILE_READ",
      "dependencies": []
    }
  ]
}
```

当前支持的任务类型包括：

```text
FILE_READ
FILE_WRITE
COMMAND
ANALYSIS
VERIFICATION
```

`Planner` 解析 JSON 时做了几个重要处理：

1. 去掉 LLM 可能返回的 ```json 代码块标记。
2. 读取 `summary` 和 `tasks`。
3. 不完全信任 LLM 给的 task id，而是重新映射为 `task_1`、`task_2`。
4. 先创建所有 task，再第二遍建立 dependencies / dependents。
5. 调用 `executionPlan.computeExecutionOrder()` 校验是否有环。

这个“两遍解析”的设计是对的，因为 LLM 返回的任务不一定严格按依赖顺序排列。如果第一遍就处理依赖，可能遇到依赖任务还没有创建的问题。

### Task

`Task` 位于：

```text
src/main/java/edu/sdu/smilecli/plan/Task.java
```

它表示执行计划中的单个任务。

当前主要字段是：

```text
id
description
type
status
result
error
dependencies
dependents
startTime
endTime
```

其中两个关系字段很关键：

```text
dependencies: 当前 task 依赖哪些前置 task
dependents: 哪些后续 task 依赖当前 task
```

可以理解成：

```text
task_A -> task_B

task_B.dependencies 包含 task_A
task_A.dependents 包含 task_B
```

`TaskStatus` 当前包括：

```text
PENDING
RUNNING
COMPLETED
FAILED
SKIPPED
```

这为后面的执行过程、失败处理、进度显示提供了基础。

### ExecutionPlan

`ExecutionPlan` 位于：

```text
src/main/java/edu/sdu/smilecli/plan/ExecutionPlan.java
```

它表示一个完整计划。

当前主要字段是：

```text
id
goal
tasks
executionOrder
status
summary
startTime
endTime
```

`tasks` 使用 `LinkedHashMap<String, Task>` 保存，既能通过 id 查找 task，也能保留插入顺序。

最重要的方法是：

```java
computeExecutionOrder()
```

它使用 DFS 做 DAG 检查和拓扑排序：

```text
visited = 0: 未访问
visited = 1: 正在访问
visited = 2: 访问完成
```

如果 DFS 过程中再次遇到 `visited = 1` 的节点，说明存在循环依赖：

```text
task_1 -> task_2 -> task_3 -> task_1
```

这时返回 `false`，`Planner` 会抛出异常，阻止执行一个不合法计划。

当前 DFS 是沿着 `dependents` 往后遍历，然后用：

```java
executionOrder.add(0, task.getId());
```

倒序插入执行队列。对于简单依赖：

```text
task_1 -> task_2 -> task_3
```

最终会得到：

```text
task_1, task_2, task_3
```

另外，`ExecutionPlan.visualize()` 用来生成执行计划的可视化文本，展示任务列表、状态、依赖和进度。

### PlanExecuteAgent

`PlanExecuteAgent` 位于：

```text
src/main/java/edu/sdu/smilecli/agent/PlanExecuteAgent.java
```

它是 Plan & DAG 阶段的执行器。

当前流程是：

```text
run(userInput)
  -> planner.createPlan(userInput)
  -> output.accept(executionPlan.visualize())
  -> executePlan(executionPlan)
```

`executePlan()` 会：

```text
1. 标记 plan 为 RUNNING
2. 获取 executionOrder
3. 按顺序取出每个 task
4. 调用 executeTask()
5. 如果 task FAILED，标记 plan FAILED 并返回失败信息
6. 每执行完一个 task，输出最新 plan 状态
7. 全部完成后标记 plan COMPLETED
8. 返回最终结果
```

`executeTask()` 内部为每个 task 单独构造一份 LLM messages：

```text
system: 你是任务执行 Agent
user: 总目标 + 当前任务 + 依赖任务结果
```

这里没有直接复用普通 `Agent` 的 `conversationHistory`，原因是 Plan 中每个 task 需要一个更干净、更聚焦的上下文。否则普通对话历史和其他 task 细节可能干扰当前 task。

每个 task 内部仍然是一个简化的 ReAct 循环：

```text
调用 LLM
  -> 如果返回 tool_calls，执行工具并把 tool 结果加入 messages
  -> 如果没有 tool_calls，认为当前 task 完成
  -> 最多循环 30 轮，避免无限工具调用
```

这说明 PlanExecuteAgent 不是替代 ReAct，而是把 ReAct 放进了每个 task 里。

## 4. Main 中的接入

`Main` 位于：

```text
src/main/java/edu/sdu/smilecli/cli/Main.java
```

当前 CLI 增加了 `/plan` 分支：

```text
/plan 复杂任务描述
```

主流程变成：

```text
普通输入
  -> agent.run(input)

/plan 输入
  -> planExecuteAgent.run(goal)
  -> cliPrint(result)
  -> agent.rememberPlanResult(goal, result)
```

这里有两个重要变化。

第一个变化是 `PlanExecuteAgent` 和 `Agent` 都接收了：

```java
Consumer<String> output
```

也就是把“怎么输出”从 Agent 内部抽出来，交给 `Main` 决定。

当前在 `Main` 中传入的是：

```java
text -> cliPrint(terminal, text)
```

这样 Agent 不再直接依赖 `System.out.println()`，而是通过回调把内容交给 CLI 终端打印。

第二个变化是 `/plan` 执行完后，会调用：

```java
agent.rememberPlanResult(goal, result);
```

这一步把 PlanExecuteAgent 的最终结果写回普通 ReAct Agent 的上下文历史中。

这样用户后续再问：

```text
刚才创建的项目继续加一个接口
```

普通 `Agent` 就能知道前面 `/plan` 做过什么。

## 5. Plan 结果如何进入 ReAct 上下文

当前设计不是让 `PlanExecuteAgent` 和 `Agent` 共享完整 messages，也不是把每个 task 的 tool call 细节全部塞入历史。

当前思路是：

```text
PlanExecuteAgent 执行复杂任务
  -> 得到最终 result
  -> Agent.rememberPlanResult(goal, result)
  -> 转成一组 user/assistant 消息放入 conversationHistory
```

`rememberPlanResult()` 当前做的是：

```text
user: /plan + goal
assistant: 我刚刚执行了一个计划任务 + 用户目标 + 执行结果
```

这个方向是合理的。

原因是：

1. `role=tool` 消息必须对应前面的 `tool_call_id`，而 `/plan` 是 Main 主动触发的，不是普通 Agent 的 tool call。
2. Plan 内部可能包含很多工具调用，全部保存会快速消耗 token。
3. 普通 ReAct Agent 后续需要知道的是“做了什么、结果是什么”，不一定需要知道每一轮工具返回的全部细节。

后面可以进一步把 `String result` 升级成结构化结果：

```java
public record PlanResult(
        String goal,
        boolean success,
        String summary,
        String detail
) {}
```

其中 `summary` 用于写入上下文，`detail` 用于终端展示。

## 6. Git 变更脉络

根据最近的 Git 记录，这段时间的修改大致可以分成几步。

### Planner 主体逻辑

相关提交：

```text
533b7de Planner的主体逻辑完成 （修正ExecutionPlan中addTask方法的小bug）
83a708c Planner的主体逻辑完成 （修正ExecutionPlan中addTask方法的小bug）
```

这一阶段主要完成：

1. `Planner` 初步把 LLM 返回的计划 JSON 转成 `ExecutionPlan`。
2. `Task` 增加依赖关系维护能力。
3. 修正 `ExecutionPlan.addTask()` 中 dependents 关系维护的小问题。

这里的关键点是从“字符串计划”走向“对象计划”。

### DAG 与执行顺序

相关提交：

```text
b75e5bf Planner的主体逻辑完成-DAG完成
```

这一阶段主要完成：

1. `ExecutionPlan.computeExecutionOrder()`。
2. 使用 DFS 判断循环依赖。
3. 保存拓扑排序后的 `executionOrder`。
4. 增加 `PlannerTest` 的初步测试入口。

这个提交让计划从“任务列表”升级成了“有依赖约束的 DAG”。

### PlanExecuteAgent 接入

相关提交：

```text
23e4274 Planner的PlanExecuteAgent和main连接完成-返回值的显示还未完成
```

这一阶段主要完成：

1. 新增 `PlanExecuteAgent`。
2. `PlanExecuteAgent` 能创建计划、显示计划、执行 task。
3. `Main` 增加 `/plan` 命令入口。
4. 增加 `PlanExecuteAgentTest`，用 fake LLM 测试规划和执行链路。

这个提交是 Plan & DAG 从数据结构进入实际 CLI 流程的关键一步。

### 失败处理与重试探索

相关提交：

```text
0f15fb6 Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
a3e41ef Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
8bc91ea Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
5d78a3b Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
b9500f7 Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
```

这一阶段主要围绕失败情况迭代：

1. task 执行失败时标记 `TaskStatus.FAILED`。
2. plan 中某个 task 失败时标记 `PlanStatus.FAILED`。
3. task 内部加入最大 tool call 轮数限制。
4. 讨论并尝试了“失败后重新规划执行 5 次”的方案。
5. 后来认识到重复执行可能带来文件重复写入、命令重复执行等副作用，因此当前版本回到了更清晰的单次执行流程。

这一阶段的重要收获是：计划执行中的重试不能简单粗暴地从头再跑，因为工具有副作用。

### 输出回调与上下文回填

相关提交：

```text
4375845 Plan&DAG功能完成、PlanExecuteAgent&Agent显示调用完成
af74bb6 Plan&DAG功能完成、PlanExecuteAgent&Agent显示调用完成
158fb16 memory
```

这一阶段主要完成：

1. `Agent` 和 `PlanExecuteAgent` 使用 `Consumer<String> output` 输出内容。
2. `Main` 在创建 Agent 时传入 `text -> cliPrint(terminal, text)`。
3. `PlanExecuteAgent` 不再直接使用 `System.out.println()` 打印计划。
4. `/plan` 执行结束后调用 `agent.rememberPlanResult(goal, result)`。
5. 普通 ReAct Agent 可以记住 PlanExecuteAgent 的执行结果。

这一步让 CLI 展示职责回到 `Main`，也让 Plan 阶段和 ReAct 阶段真正连接起来。

### IDE 配置处理

相关提交：

```text
b9500f7 Planner的PlanExecuteAgent和main连接完成-Failed的情况考虑进来了
```

该提交中 `.idea/misc.xml` 被删除，主要是为了避免 IntelliJ IDEA 的本地 JDK 名称被提交到 GitHub 后影响其他机器。

这个问题的本质是：`.idea/misc.xml` 里可能包含 `project-jdk-name="17"` 这种本机 IDE 配置。不同电脑上的 JDK 名称不一定一致，所以拉取后可能出现“未定义 JDK”。

## 7. 当前完成度

从功能角度看，Plan & DAG 阶段已经初步完成。

当前已经具备：

1. `/plan` CLI 命令入口。
2. LLM 生成计划 JSON。
3. JSON 解析为 `ExecutionPlan`。
4. `Task` 表达任务类型、状态、依赖和结果。
5. DAG 循环依赖检查。
6. DAG 拓扑执行顺序计算。
7. `PlanExecuteAgent` 按 task 顺序执行。
8. 每个 task 内部支持 tool call 循环。
9. task 结果可以传给后续依赖 task。
10. 执行计划可以可视化展示。
11. 执行结果可以写回普通 Agent 上下文。
12. fake LLM 测试覆盖了基本 PlanExecuteAgent 链路。

可以认为现在已经从：

```text
纯 ReAct 工具调用 Agent
```

推进到了：

```text
ReAct + Plan/DAG 的双执行模式
```

## 8. 当前仍需注意的问题

### 工具结果还是字符串

`ToolRegistry.executeTool()` 当前返回 `String`。

这导致 `PlanExecuteAgent` 不能可靠判断工具是否成功。

比如命令执行失败可能返回：

```text
命令执行完成 (exit code: 1)
```

但程序层面仍然只是一个普通字符串。只要 LLM 后续返回普通 content，task 就可能被标记为 `COMPLETED`。

后续更好的设计是让工具返回结构化结果：

```java
public record ToolResult(
        boolean success,
        String content,
        String error
) {}
```

这样 PlanExecuteAgent 可以根据 `success` 决定是否继续、重试或失败。

### PlanResult 仍然是 String

`PlanExecuteAgent.run()` 当前返回 `String`。

这对展示来说够用，但对于上下文记忆、失败处理、后续自动总结不够方便。

后续可以升级为：

```java
public record PlanResult(
        String goal,
        boolean success,
        String summary,
        String detail
) {}
```

### 输出展示与业务逻辑仍可继续分离

现在已经用 `Consumer<String> output` 替代了直接 `System.out.println()`，这是一个正确方向。

后面可以进一步考虑：

```text
PlanExecuteAgent 只产生事件
Main 负责渲染事件
```

例如：

```text
PLAN_CREATED
TASK_STARTED
TASK_COMPLETED
TASK_FAILED
PLAN_COMPLETED
```

不过当前学习阶段使用 `Consumer<String>` 已经足够。

### `/plan` 命令匹配还可以更严谨

当前 `Main` 中使用：

```java
input.toLowerCase().startsWith("/plan")
```

这会让 `/planet xxx` 也进入 `/plan` 分支。

更严谨的写法是：

```java
String lowerInput = input.toLowerCase();
if (lowerInput.equals("/plan") || lowerInput.startsWith("/plan ")) {
    ...
}
```

### 简单任务判断仍比较粗糙

`Planner.isSimpleGoal()` 当前通过关键词和长度判断是否直接生成 minimal plan。

这是一个可用的启发式方案，但不是稳定语义判断。后续可以继续调整，也可以先保留，等真实使用中观察哪些任务被误判。

### 可视化宽度和中文对齐

`ExecutionPlan.visualize()` 当前用 `String.format("%-Ns")` 对齐。

但中文、emoji、框线字符在终端里显示宽度不一定等于 Java 字符数量，所以可能出现右侧边框不齐。

后续如果要修，可以增加 `displayWidth()` 和 `padRight()`，按终端显示宽度补空格。

### 文件副作用与重试策略

之前讨论过失败后重新规划执行 5 次，但这个策略会带来副作用：

```text
重复写文件
重复执行命令
重复删除或覆盖
```

因此当前单次执行更清晰。

后续如果要做重试，建议只重试失败 task，并且重试前区分工具是否幂等。

## 9. 建议下一步路线

建议接下来按这个顺序推进：

1. 保持当前 PlanExecuteAgent 主流程稳定，不急着加入复杂重试。
2. 把 `ToolRegistry.executeTool()` 的返回值升级成结构化 `ToolResult`。
3. 让 task 能根据工具失败真正 `markFailed()`。
4. 把 `PlanExecuteAgent.run()` 返回值升级成 `PlanResult`。
5. `Agent.rememberPlanResult()` 只保存 `PlanResult.summary`，避免 token 过度增长。
6. 为 `Planner.parsePlan()`、`ExecutionPlan.computeExecutionOrder()`、`PlanExecuteAgent.run()` 增加 fake LLM 单元测试。
7. 再考虑失败 task 重试、跳过 task、人工确认危险工具等能力。

## 10. 当前阶段评价

这一阶段最大的价值不是多了一个 `/plan` 命令，而是项目的 Agent 架构开始分层：

```text
Main: CLI 输入输出
Agent: 普通 ReAct 对话与工具调用
Planner: 把目标拆成计划
ExecutionPlan / Task: 表达 DAG
PlanExecuteAgent: 按计划执行 task
ToolRegistry: 本地工具注册与执行
LlmClient: LLM 交互抽象
```

现在 SmileCli 已经具备一个小型 coding agent 的核心雏形：

```text
能聊天
能调用工具
能规划复杂任务
能按 DAG 执行
能展示进度
能把计划结果记回上下文
```

下一步的重点不是继续堆功能，而是把“工具执行结果、计划执行结果、错误处理”从字符串升级成结构化对象。这样项目会从“能跑通”继续走向“能稳定地判断成功和失败”。
