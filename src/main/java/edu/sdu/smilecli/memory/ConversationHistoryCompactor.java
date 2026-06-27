package edu.sdu.smilecli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.sdu.smilecli.llmclient.LlmClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class ConversationHistoryCompactor {
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    static final int INTTRIGGER_TOKENS = 700_000; //压缩上下文的阈值 从800000改为700000 留一部分TOKEN给实际的时候发送的LLM加了长期记忆。
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;//压缩摘要的最大输入字符数
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
    private static final String LongTerm_PROMPT = """
             请从下面的对话中提取适合长期保存的记忆。
            
             只提取长期稳定、有复用价值的信息，例如：
             1. 用户的长期偏好
             2. 用户对项目的长期目标
             3. 项目的稳定背景信息
             4. 已经明确达成的设计决策
            
             不要保存：
             1. 临时问题
             2. 一次性命令结果
             3. 过长的文件内容
             4. 不确定的推测
             5. 已经在短期摘要中临时保留即可的信息
            
             === 待提取的对话 ===
             %s
             === 待提取的对话（结束）===
            
            请只返回 JSON 数组：
            [
              {
                "content": "记忆内容",
                "scope": "project"
              }
            ]
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
    public boolean compactIfNeeded(List<LlmClient.Message> conversationHistory, LongTermMemory longTermMemory){
        if (conversationHistory == null || conversationHistory.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
        if (currentTokens < INTTRIGGER_TOKENS) return false;

        int systemEnd = "system".equals(conversationHistory.get(0).role()) ? 1 : 0; //0 Message是系统消息 从1开始找用户信息
        //assistant和tool信息不管

        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < conversationHistory.size(); i++) {
            if ("user".equals(conversationHistory.get(i).role())) {
                userIndices.add(i);
            }
        }
        if (userIndices.size() <= retainRecentRounds) {
            log.info("compactIfNeeded skip: only {} user turns, < retain {}",
                    userIndices.size(), retainRecentRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        List<LlmClient.Message> oldMsgs = new ArrayList<>(conversationHistory.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        //确定需要压缩->
        // 1、提取长期记忆 (设计是压缩时候才自动提取长期记忆，其他时候需要主动存储/save ||/save --global)
        try {
            List<MemoryEntry> memories = extractLongTermMemories(oldMsgs);
            for (MemoryEntry memory : memories) {
                longTermMemory.store(memory.content(), "project");//默认是project级别的
            }
        } catch (Exception e) {
            log.warn("extract long-term memories failed; skip long-term memory storing", e);
        }

        // 2、准备总结
        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }//summary是LLM返回的总结

        // 重构conversationHistory
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(conversationHistory.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(conversationHistory.subList(splitIdx, conversationHistory.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        conversationHistory.clear();
        conversationHistory.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, summary chars %d",
                currentTokens, afterTokens, userIndices.size() + systemEnd /* 估值 */, rebuilt.size(),
                summary.length()));
        return true;
    }

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    ObjectMapper mapper = new ObjectMapper();

    private List<MemoryEntry> extractLongTermMemories(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }

        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }

        String prompt = String.format(LongTerm_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个长期记忆提取助手，只返回 JSON 数组。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        String content = response == null ? null : response.content();

        if (content == null || content.isBlank()) {
            return List.of();
        }

        String cleaned = content
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        if (!root.isArray()) {
            return List.of();
        }

        List<MemoryEntry> result = new ArrayList<>();

        for (JsonNode node : root) {
            String memoryContent = node.path("content").asText("").trim();
            String scope = node.path("scope").asText("project").trim();

            if (!memoryContent.isBlank()) {
                result.add(new MemoryEntry(memoryContent, scope));
            }
        }

        return result;

    }

}
