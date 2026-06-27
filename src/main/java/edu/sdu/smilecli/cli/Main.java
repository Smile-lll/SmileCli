package edu.sdu.smilecli.cli;

import edu.sdu.smilecli.agent.Agent;
import edu.sdu.smilecli.agent.PlanExecuteAgent;
import edu.sdu.smilecli.llmclient.DeepSeekClient;
import edu.sdu.smilecli.llmclient.LlmClient;
import edu.sdu.smilecli.memory.MemoryEntry;
import edu.sdu.smilecli.memory.TokenBudget;
import edu.sdu.smilecli.tool.ToolRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.util.List;

public class Main {
    public static void main(String[] args) {

        String apiKey;
        apiKey = loadEnvValue("DEEPSEEK_API_KEY");
        if (apiKey == null) {
            System.err.println("❌ 未找到可用 API Key");
            return;
        }
        LlmClient llmClient = new DeepSeekClient(apiKey);

        ToolRegistry toolRegistry = new ToolRegistry();

//        System.out.println(apiKey);
//        llmClient.chat(null, null);

        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            Agent agent = new Agent(llmClient, toolRegistry, text -> cliPrint(terminal, text));
            PlanExecuteAgent planExecuteAgent = new PlanExecuteAgent(llmClient, toolRegistry, text -> cliPrint(terminal, text));
            refreshTerminalColumns(terminal);
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
            printBanner(terminal);
            cliPrint(terminal, "欢迎使用SmileCLI,以下是提供的一些常见指令:(/ 可查看详细指令)");
            for (String command : COMMANDS)
                cliPrint(terminal, command);
            cliPrint(terminal, " ");
            while (true) {
                String input;
//                String currentPath = System.getProperty("D:\\SmileCli");
                try {
                    input = lineReader.readLine("SmileInput> ");
                } catch (UserInterruptException e) {
                    continue;//ctrl + c 不退出
                } catch (EndOfFileException e) {
                    cliPrint(terminal, "再见 :)");
//                    System.out.println("👋 退出");//ctrl+d退出
                    break;
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                input = input.trim();
                if ("/".equalsIgnoreCase(input)) {
                    cliPrint(terminal, "当前支持命令");
                    for (String command : COMMANDS)
                        cliPrint(terminal, command);
                    continue;
                }

                if ("/exit".equalsIgnoreCase(input)) {
                    cliPrint(terminal, "再见 :)");
                    break;
                }

                if ("/clear".equalsIgnoreCase(input)) {
                    agent.clearHistory();
                    cliPrint(terminal, "已清空对话历史");
                    continue;
                }

                if ("/memory".equalsIgnoreCase(input)) {
                    int availableTokens = TokenBudget.getAvailableTokens(agent.getConversationHistory());
                    cliPrint(terminal, "总可用TOKEN数:1000000\n" + "当前可用TOKEN数:" + availableTokens);
                    continue;
                }

                if ("/memory list".equalsIgnoreCase(input)) {
                    List<MemoryEntry> memories = agent.listLongTermMemory();
                    cliPrint(terminal, "以下是你的长期记忆:");
                    for (MemoryEntry memory : memories) {
                        cliPrint(terminal, memory.scope() + ": " + memory.content());
                    }
                    continue;
                }

                if (input.toLowerCase().startsWith("/save")) {
                    String toSave = input.substring("/save".length()).trim();
                    if (toSave.isEmpty()) {
                        cliPrint(terminal, "请提供要保存的内容，例如：/save 这个项目使用 Java 17");
                        continue;
                    }

                    String scope = "project";

                    if (toSave.startsWith("--global")) {
                        scope = "global";
                        toSave = toSave.substring("--global".length()).trim();
                    }

                    if (toSave.isEmpty()) {
                        cliPrint(terminal, "请提供要保存的内容，例如：/save --global 默认用中文回答");
                        continue;
                    }

                    agent.saveLongTermMemory(toSave, scope);

                    cliPrint(terminal, "已保存到长期记忆(" + scope + "): " + toSave);
                    continue;
                }

                if (input.toLowerCase().startsWith("/plan")) {
                    String goal = input.substring("/plan".length()).trim();
                    if (goal.isEmpty()) {
                        cliPrint(terminal, "请输入要规划的任务，例如：/plan 创建一个 Spring Boot 项目");
                        continue;
                    }

                    try {
                        String result = planExecuteAgent.run(goal);
                        cliPrint(terminal, result);

                        agent.rememberPlanResult(goal, result);
                    } catch (IOException e) {
                        cliPrint(terminal, "❌ 执行计划失败: " + e.getMessage());
                    }
                    continue;
                }

//                Agent.Result result = agent.run(input);
                String response = agent.run(input);
//                LlmClient.UserToken usertoken = result.usertoken();

//                List<LlmClient.Message> messages = new ArrayList<>();
//                messages.add(LlmClient.Message.system("你是一个专业的LLM模型"));
//                messages.add(LlmClient.Message.user(input));

//                ChatResponse chat = llmClient.chat(messages, null);
                if (response != null && !response.isEmpty()) {
                    cliPrint(terminal, "SmileAgent: " + response);
                }
            }

//            cliPrint(terminal, "再见 :)");
        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        }


    }

    private static final String[] COMMANDS = new String[]{
            "/exit 退出SmileCli",
            "/clear 清空对话Message",
            "/plan Plan & DAG",
            "/memory 查看当前上下文TOKEN使用情况",
            "/save <内容> 保存项目级长期记忆",
            "/save --global <内容> 保存全局长期记忆",
            "/memory list 查看长期记忆"
    };

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

    private static void refreshTerminalColumns(Terminal terminal) {
        if (terminal == null || terminal.getSize() == null || terminal.getSize().getColumns() <= 0) {
            return;
        }
        System.setProperty("smilecli.render.columns", String.valueOf(Math.max(40, terminal.getSize().getColumns())));
    }

    private static void printBanner(Terminal terminal) {
        terminal.writer().println("""
                ╔══════════════════════════════════════════════════════════╗
                ║   ███████╗███╗   ███╗██╗██╗     ███████╗         █       ║
                ║   ██╔════╝████╗ ████║██║██║     ██╔════╝    ██    █      ║
                ║   ███████╗██╔████╔██║██║██║     █████╗             █     ║
                ║   ╚════██║██║╚██╔╝██║██║██║     ██╔══╝             █     ║
                ║   ███████║██║ ╚═╝ ██║██║███████╗███████╗    ██    █      ║
                ║   ╚══════╝╚═╝     ╚═╝╚═╝╚══════╝╚══════╝         █       ║
                ║              SmileCLI Java Agent CLI v1.0.0              ║
                ╚══════════════════════════════════════════════════════════╝
                """);
        terminal.writer().flush();
    }

    private static void cliPrint(Terminal terminal, String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }
}
