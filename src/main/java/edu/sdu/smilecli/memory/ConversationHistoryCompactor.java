package edu.sdu.smilecli.memory;

import edu.sdu.smilecli.llmclient.LlmClient;

import java.util.List;

public class ConversationHistoryCompactor {
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    static final int INTTRIGGER_TOKENS = 800_000; //压缩上下文的阈值
    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办
            
            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。
            
            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;
    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    /**
     * 判断是否需要压缩短期记忆
     * */
    public boolean compactIfNeeded(List<LlmClient.Message> conversationHistory) {
        if(conversationHistory==null||conversationHistory.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
        if (currentTokens < INTTRIGGER_TOKENS) return false;
    }
}
