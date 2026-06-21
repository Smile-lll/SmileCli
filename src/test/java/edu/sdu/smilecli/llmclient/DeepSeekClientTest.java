package edu.sdu.smilecli.llmclient;

import edu.sdu.smilecli.llmclient.DeepSeekClient;
import edu.sdu.smilecli.llmclient.LlmClient;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DeepSeekClientTest {

    private final static String apiKey="sk-65a4f09b32554c008749560c32258b07";
    @Test
    public void testDeepSeekClient() {
        List<LlmClient.Message> messages=new ArrayList<>();
        messages.add(LlmClient.Message.system("你是一个专业的LLM模型"));
        messages.add(LlmClient.Message.user("我正在写我自己的agent项目，这是一次LLMClient的测试"));
        DeepSeekClient deepSeekClient = new DeepSeekClient(apiKey);
        deepSeekClient.chat(messages,null);
    }
}
