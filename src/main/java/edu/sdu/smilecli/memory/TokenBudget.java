package edu.sdu.smilecli.memory;

import edu.sdu.smilecli.llmclient.LlmClient;

import java.util.List;

public class TokenBudget {

    /**
     * 估算消息列表的 token 总数
     */
    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (LlmClient.Message msg : messages) {
            if(msg==null) continue;
            total += estimateTokens(msg.role());
            total += estimateTokens(msg.content());
            total += estimateTokens(msg.toolCallId());

            if(msg.toolCalls()!=null){
                for (LlmClient.ToolCall toolCall : msg.toolCalls()) {
                    if (toolCall == null) {
                        continue;
                    }

                    total += estimateTokens(toolCall.id());

                    if (toolCall.function() != null) {
                        total += estimateTokens(toolCall.function().name());
                        total += estimateTokens(toolCall.function().arguments());
                    }
                }
            }
        }
        // 每条消息额外开销约 4 tokens（role、separator 等）
        total += messages.size() * 4;
        return total;
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }
}
