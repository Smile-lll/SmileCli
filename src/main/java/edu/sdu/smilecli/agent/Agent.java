package edu.sdu.smilecli.agent;

import edu.sdu.smilecli.llmclient.LlmClient;
import edu.sdu.smilecli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public class Agent {
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private static final int MAX_ITERATIONS = 10;//最大React次数

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();

        // 添加系统提示
        conversationHistory.add(LlmClient.Message.system(SYSTEM_PROMPT));
    }

    //    private static final String SYSTEM_PROMPT = """
//    你是一个智能编程助手，可以帮助用户完成各种任务。
//
//    你可以使用以下工具来完成任务：
//    1. file_read - 读取文件内容
//    2. file_write - 写入文件内容
//    3. file_delete - 删除文件
//    4. file_rename - 重命名文件
//    5. file_list - 列出指定目录下的文件
//    6. execute_command - 执行powershell命令
//
//    当需要操作文件、执行命令或创建项目时，请使用工具调用。
//    使用工具后，根据工具返回的结果继续思考下一步行动。
//
//    请用中文回复用户。
//    """;
    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，可以帮助用户完成各种任务。
            
            请用中文回复用户。
            """;

    public String run(String userInput) {
        // 添加用户输入
        conversationHistory.add(LlmClient.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 调用 LLM
            LlmClient.ChatResponse response = llmClient.chat(
                    conversationHistory,
//                    toolRegistry.getToolDefinitions()
                    null
            );

            // 如果有工具调用
//            if (response.hasToolCalls()) {
            if (false) {
//                // 记录助手消息
//                conversationHistory.add(
//                        LlmClient.Message.assistant(response.content(), response.toolCalls())
//                );
//
//                // 执行每个工具调用
//                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
//                    String result = toolRegistry.executeTool(
//                            toolCall.function().name(),
//                            toolCall.function().arguments()
//                    );
//
//                    // 记录工具结果
//                    conversationHistory.add(
//                            LlmClient.Message.tool(toolCall.id(), result)
//                    );
//                }
                // 继续循环，让 LLM 根据结果继续思考
                continue;
            } else {
                // 没有工具调用，任务完成
                conversationHistory.add(
                        LlmClient.Message.assistant(response.content())
                );
                return response.content();
            }
        }

        return "达到最大迭代次数限制";
    }
}
