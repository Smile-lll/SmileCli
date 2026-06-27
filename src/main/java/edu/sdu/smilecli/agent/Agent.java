package edu.sdu.smilecli.agent;

import edu.sdu.smilecli.llmclient.LlmClient;
import edu.sdu.smilecli.memory.ConversationHistoryCompactor;
import edu.sdu.smilecli.memory.LongTermMemory;
import edu.sdu.smilecli.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class Agent {
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private static final int MAX_ITERATIONS = 10;//最大React次数
    LlmClient.UserToken usertoken;
    private final Consumer<String> output;
    private final ConversationHistoryCompactor historyCompactor;
    private final LongTermMemory longTermMemory;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, Consumer<String> output) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.output = output;
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);// 压缩也是用LLM压缩 所以需要传入一个LLMlient
        this.longTermMemory = new LongTermMemory();

        // 添加系统提示
        conversationHistory.add(LlmClient.Message.system(SYSTEM_PROMPT));
    }

    private static final String SYSTEM_PROMPT = """
            你现在是SmileCli的智能编程助手，可以帮助用户完成各种任务。
            
            你可以使用以下工具来完成任务：
            1. file_read - 读取文件内容
            2. file_write - 写入文件内容
            3. file_delete - 删除文件
            4. file_rename - 重命名文件
            5. file_list - 列出指定目录下的文件
            6. execute_command - 执行powershell命令
            
            当需要操作文件、执行命令或创建项目时，请使用工具调用。
            使用工具后，根据工具返回的结果继续思考下一步行动。
            
            请用中文回复用户。
            """;
//    private static final String SYSTEM_PROMPT = """
//            你现在是SmileCli的智能编程助手，可以帮助用户完成各种任务。
//
//            请用中文回复用户。
//            """;

//    //用于Agent的run方法返回给Main的封装
//    public record Result(String content, LlmClient.UserToken usertoken){}

    public String run(String userInput) {
        // 添加用户输入
        conversationHistory.add(LlmClient.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 短期记忆管理 如果需要token预估达到800_000就压缩，没有不压缩
            historyCompactor.compactIfNeeded(conversationHistory);

            // 调用 LLM
            LlmClient.ChatResponse response = llmClient.chat(
                    conversationHistory,
                    toolRegistry.getToolDefinitions()
//                    null
            );

            //取出response中关于token的内容
            usertoken = response.usertoken();

            // 如果有工具调用
            if (response.hasToolCalls()) {
//            if (false) {
                // 记录助手消息
                conversationHistory.add(
                        LlmClient.Message.assistant(response.content(), response.toolCalls())
                );

                // 执行每个工具调用
                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
//                    log.info("执行工具调用 id: {}", toolCall.id());
//                    log.info("function.name: {}", toolCall.function().name());
//                    log.info("function.arguments: {}", toolCall.function().arguments());
                    String result = toolRegistry.executeTool(
                            toolCall.function().name(),
                            toolCall.function().arguments()
                    );

                    // 记录工具结果
                    conversationHistory.add(
                            LlmClient.Message.tool(result, toolCall.id())
                    );
                }
                // 继续循环，让 LLM 根据结果继续思考
                continue;
            } else {
                // 没有工具调用，任务完成
                conversationHistory.add(
                        LlmClient.Message.assistant(response.content())
                );

                output.accept("═══════════════════════════════════════════");
                output.accept("输入TOKEN: " + usertoken.promptTokens());
                output.accept("输出TOKEN: " + usertoken.completionTokens());
                output.accept("本次询问消耗TOKEN: " + usertoken.totalTokens());
                output.accept("本次会话还剩TOKEN: " + usertoken.availableContextTokens());
                output.accept("═══════════════════════════════════════════");
                output.accept("");
                return response.content();
//                return new Result(response.content(), usertoken);
            }
        }
//        return new Result("达到最大迭代次数限制", usertoken);
        return "达到最大迭代次数限制";
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(SYSTEM_PROMPT));
    }

    /**
     * 存储Plan的执行结果
     * */
    public void rememberPlanResult(String goal, String result) {
        conversationHistory.add(
                LlmClient.Message.user("/plan " + goal)
        );
        conversationHistory.add(
                LlmClient.Message.assistant(
                        "我刚刚执行了一个计划任务。\n" +
                                "用户目标：" + goal + "\n" +
                                "执行结果：\n" + result
                )
        );
    }

    public List<LlmClient.Message> getConversationHistory() {
        return conversationHistory;
    }

    public void saveLongTermMemory(String content, String scope) {
        longTermMemory.store(content, scope);
    }
}

