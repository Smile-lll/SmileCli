package edu.sdu.smilecli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.sdu.smilecli.llmclient.LlmClient;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//用于和Agent进行交互 Agent像个中转场 交换Planner和LLMClient
//Planner先通过LLMClient获取任务计划的一次询问，获取一系列TASK情况，然后再封装进TASK->EXECUTION_PLAN
public class Planner {
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // 规划提示词Prompt
    private static final String PLAN_PROMPT = """
                        你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。
            
             可用任务类型：
            
             - `FILE_READ`: 读取文件内容
             - `FILE_WRITE`: 写入文件内容
             - `COMMAND`: 执行 powershell 命令
             - `ANALYSIS`: 分析结果并做出决策
             - `VERIFICATION`: 验证结果是否正确
            
             请按以下 JSON 格式输出执行计划：
            
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
            
             规则：
            
             1. 每个任务必须有唯一 id，如 `task_1`、`task_2`。
             2. `dependencies` 列出依赖的任务 id。
             3. 任务应该按执行顺序排列。
             4. 任务描述要具体明确。
             5. 简单任务允许只生成 1-3 个任务，不要为了凑步数引入无关步骤。
             6. 复杂任务拆分为 5-10 个子任务。
             7. 不要为了“保存中间结果”额外创建 `FILE_WRITE` / `FILE_READ`，除非用户明确要求落盘。
             8. 如果一个任务一步就能完成，就保持最短计划。
            
             只输出 JSON，不要有其他内容。
            """;

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 与LLM交互 将用户内容交予LLM 得到planJson 之后调用 parsePlan
     *
     */
    public ExecutionPlan createPlan(String goal) throws IOException {

        // 构建规划请求
        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(PLAN_PROMPT),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal)
        );
        LlmClient.ChatResponse response = llmClient.chat(messages, null);//只是规划PLAN所以不用输入Tools
        String planJson = response.content();

        return parsePlan(goal, planJson);
    }


    /**
     * 解析LLM返回的计划JSON
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
//        System.out.println("LLM返回的JSON：\n"+cleaned);
        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText("");
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan executionPlan = new ExecutionPlan("plan_" + System.currentTimeMillis(), goal);
        executionPlan.setSummary(summary);

        //遍历两遍 先不处理依赖关系 避免LLM返回的内容不是按照依赖顺序（虽然在System级Prompt要求了，但是还是保险起见避免LLM幻觉）
        Map<String, String> idMapping = new HashMap<>();//新旧id的映射
        //最好将ID自己重新组织一下 避免LLM生成的可能存在ID重复
        int taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();

            // 对类型处理一下 保证和Task规定类型对应上（也是避免幻觉问题）
            Task.TaskType type = parseTaskType(typeStr); //其实感觉也有点多余 就是不会报错而已 如果真的出现幻觉 还是会对不到真确的位置 只是对应到“分析”Task上
            Task task = new Task(newId, description, type);
            executionPlan.addTask(task); //虽然addTask写了加入依赖 但是这里没用到 所以第一次的bug没发现
        }

        // 第二遍：建立依赖和被依赖关系
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = executionPlan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = executionPlan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }
        //已全部加入ExecutionPlan

        //判断executionPlan有没有环 如果环形存在 抛出异常 不存在给出Task的执行顺序保证依赖顺序--->DAG有向无环图的遍历问题(leetcode207/210)
        if (!executionPlan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return executionPlan;
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }
}