package edu.sdu.smilecli.agent;

import edu.sdu.smilecli.llmclient.LlmClient;
import edu.sdu.smilecli.plan.ExecutionPlan;
import edu.sdu.smilecli.plan.Planner;
import edu.sdu.smilecli.plan.Task;
import edu.sdu.smilecli.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan的时候不考虑上下文了 因为单独LLM chat了 直接返回结果”String“
 * */

@Slf4j
public class PlanExecuteAgent {
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
    }

    public String run(String userInput) throws IOException {
        // 1. 创建执行计划
        ExecutionPlan executionPlan = planner.createPlan(userInput);

        // 2. 显示计划
        String plan = executionPlan.visualize();
        System.out.println(plan);

        // 3. 执行计划
        String result = "";
        for (int i = 0; i < 5; i++) {
            result = executePlan(executionPlan);
            if (executionPlan.getStatus() == ExecutionPlan.PlanStatus.COMPLETED) {
                break;
            }
        }
        if (executionPlan.getStatus() == ExecutionPlan.PlanStatus.FAILED) {
            return "重试5次，该计划依旧失败\n" + result;
        }

        return result;

        //TODO 把PlanExecuteAgent的结果加入到ReAct的上下文里
        //TODO 约束规划必须规划>=5个task  或者 完成简单任务

        // 4. 返回结果
//        return buildResult(plan);
//        return results;
    }

    private String executePlan(ExecutionPlan executionPlan) {
        log.info("Executing plan: goal='{}', taskCount={}", executionPlan.getGoal(), executionPlan.getAllTasks().size());
        executionPlan.markStarted();

        StringBuilder finalResult = new StringBuilder();

        List<String> temp = executionPlan.getExecutionOrder();
        for (String taskId : temp) {
            Task task = executionPlan.getTask(taskId);
            if (task == null) continue;

            String result = executeTask(executionPlan, task);

            if (task.getStatus() == Task.TaskStatus.FAILED) {
                executionPlan.markFailed();
                return "计划执行失败：任务 " + task.getId() + " 失败\n" + result;
            }
            if (!taskId.equals(temp.get(temp.size() - 1))) {
                String plan = executionPlan.visualize();
                System.out.println(plan);
            }

        }

        executionPlan.markCompleted();

        String plan = executionPlan.visualize();
        System.out.println(plan);
        return "计划执行完成\n" + buildFinalResult(executionPlan);
    }

    private String executeTask(ExecutionPlan executionPlan, Task task) {
        try {
            task.markStarted();

            List<LlmClient.Message> messages = new ArrayList<>();//不用agent中共享的上下文 单独构建任务上下文 发送内容--->因为如果共享一个上下文的话 没办法扩张之后的并行
            //PLAN结束之后把PLAN的结果 构建进Agent的历史中
            messages.add(LlmClient.Message.system("你是一个任务执行 Agent，请根据任务上下文完成当前任务。"));
            messages.add(LlmClient.Message.user(buildTaskContext(executionPlan, task)));

            StringBuilder toolResults = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                LlmClient.ChatResponse response = llmClient.chat(
                        messages,
                        toolRegistry.getToolDefinitions()
                );
                if (!response.hasToolCalls()) {
                    task.markCompleted(response.content());
//                    System.out.println(response.content());
                    return response.content();
                }
                messages.add(LlmClient.Message.assistant(
                        response.content(),
                        response.toolCalls()
                ));
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    String result = toolRegistry.executeTool(
                            toolCall.function().name(),
                            toolCall.function().arguments()
                    );
                    toolResults.append(result).append("\n");
                    messages.add(LlmClient.Message.tool(
                            result, toolCall.id()

                    ));
                }
            }

            task.markFailed("单次task调用Tool超过最大限制");
            return "单次task调用Tool超过最大限制";

//            System.out.println(result);

        } catch (Exception e) {
            task.markFailed(e.getMessage());
            return "任务执行失败: " + e.getMessage();
        }
    }

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();

        for (Task task : plan.getAllTasks()) {
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            result.append("[")
                    .append(task.getId())
                    .append("] ")
                    .append(task.getResult())
                    .append("\n");
        }

        return result.toString();
    }

    private String buildTaskContext(ExecutionPlan executionPlan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(executionPlan.getGoal()).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = executionPlan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ")
                        .append(dep.getId())
                        .append("：")
                        .append(dep.getDescription())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("\n请完成当前任务，并返回结果。");
        return context.toString();
    }
}
