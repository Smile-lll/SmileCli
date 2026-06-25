package edu.sdu.smilecli.agent;

import edu.sdu.smilecli.llmclient.LlmClient;
import edu.sdu.smilecli.tool.ToolRegistry;
import junit.framework.TestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PlanExecuteAgentTest extends TestCase {

    public void testTestRun() throws IOException {
        List<String> executedTaskPrompts = new ArrayList<>();

        LlmClient fakeClient = new LlmClient() {
            private int executeCallCount = 0;

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                if (tools == null) {
                    String planJson = """
                            {
                              "summary": "Create Spring Boot project plan",
                              "tasks": [
                                {
                                  "id": "analyze",
                                  "description": "Analyze project requirements",
                                  "type": "ANALYSIS",
                                  "dependencies": []
                                },
                                {
                                  "id": "create",
                                  "description": "Create Spring Boot project structure",
                                  "type": "COMMAND",
                                  "dependencies": ["analyze"]
                                },
                                {
                                  "id": "verify",
                                  "description": "Verify project creation",
                                  "type": "VERIFICATION",
                                  "dependencies": ["create"]
                                }
                              ]
                            }
                            """;
                    return new ChatResponse(planJson);
                }

                executeCallCount++;
                executedTaskPrompts.add(messages.get(messages.size() - 1).content());
                return new ChatResponse(
                        "task executed " + executeCallCount,
                        null,
                        new LlmClient.UserToken(10, 2, 12, LlmClient.MAX_TOKEN - 12)
                );
            }
        };

        PlanExecuteAgent agent = new PlanExecuteAgent(fakeClient, new ToolRegistry());

        String result = agent.run("Create a Spring Boot project");

        assertNotNull(result);
        assertTrue(result.contains("[task_1] task executed 1"));
        assertTrue(result.contains("[task_2] task executed 2"));
        assertTrue(result.contains("[task_3] task executed 3"));
        assertEquals(3, executedTaskPrompts.size());
        assertTrue(executedTaskPrompts.get(0).contains("Analyze project requirements"));
        assertTrue(executedTaskPrompts.get(1).contains("Create Spring Boot project structure"));
        assertTrue(executedTaskPrompts.get(2).contains("Verify project creation"));
    }
}