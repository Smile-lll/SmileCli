package edu.sdu.smilecli.llmclient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface LlmClient {
    // 定义Message、Tool--> 完成chat

    static final long MAX_TOKEN = 1_000_000L;

    /*
     * 1. 创建一个Message类，包含role、content、toolCalls、toolCallId
     * role: system、user、assistant、tool
     * content: 内容
     * toolCalls: 工具调用 assistant指明要调用哪些tool,输入的参数是什么
     * toolCallId: 对应的toolCalls的 id
     * */
    record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, toolCalls, null);
        }

        //无tool调用的返回
        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        public static Message tool(String content, String toolCallId) {
            return new Message("tool", content, null, toolCallId);
        }
    }

    /*
     * Tool: name、description、JSonSchema
     * name: tool的name
     * description: tool的描述
     * parameters: tool的参数的描述 以Json的形式 约定该tool需要哪些参数 参数类型是什么
     * */
    record Tool(String name, String description, JsonNode parameters) {
    }

    /*
     * ToolCall: id、function
     * id: ToolCall的id -> 表明这次工具调用的id 。注意 不是tool的id
     * function: tool的调用参数------->受Tool的parameters约束
     * */
    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {
        }
    }

    /*
     * chat()
     * */
    ChatResponse chat(List<Message> messages, List<Tool> tools);

    /*
     * ChatResponse: content、toolCalls
     * content: 模型返回的答案
     * toolCalls: 模型返回的tool调用参数
     * */
    record ChatResponse(String content, List<ToolCall> toolCalls, UserToken usertoken) {
        public ChatResponse(String content) {
            this(content, null, null);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * promptTokens: 模型输入的token数量
     * completionTokens: 模型输出的token数量
     * totalTokens: 模型输入输出的token数量
     */
    record UserToken(long promptTokens, long completionTokens, long totalTokens, long availableContextTokens) {
    }

}
