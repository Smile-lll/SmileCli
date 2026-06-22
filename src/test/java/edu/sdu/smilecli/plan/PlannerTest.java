package edu.sdu.smilecli.plan;

import edu.sdu.smilecli.llmclient.DeepSeekClient;
import edu.sdu.smilecli.llmclient.LlmClient;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class PlannerTest extends TestCase {
    private static String loadEnvValue(String key) {
        File envFile = new File(".env");

        if (!envFile.exists()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取 .env 文件失败", e);
        }

        return null;
    }
    public void testCreatePlan() {

        String apiKey;
        apiKey = loadEnvValue("DEEPSEEK_API_KEY");
        if (apiKey == null) {
            System.err.println("❌ 未找到可用 API Key");
            return;
        }
        LlmClient llmClient = new DeepSeekClient(apiKey);

        new Planner(llmClient).createPlan("这是一个测试PLAN的goal,请给我规划一个创建一个SpringBoot项目的规划。");
    }
}