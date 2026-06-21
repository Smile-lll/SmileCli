package edu.sdu.smilecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();//用于制作JsonSchema

    public ToolRegistry() {
        //注册自带工具集
        registerFileTools();
        registerShellTools();
//        registerCodeTools();
    }

    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令，用于编译、运行、Git操作等",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> {
                    String command = args.get("command");
                    try {
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        // 读取命令输出
                        StringBuilder output = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        }

                        int exitCode = process.waitFor();
                        return String.format("命令执行完成 (exit code: %d)\n%s",
                                exitCode, output);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerFileTools() {
        tools.put("file_read", new Tool(
                "file_read",
                "读取文件内容，用于查看代码、配置文件等",
                createParameters(new Param("file_path", "string", "文件路径", true)),
                args -> {
                    String filePath = args.get("file_path");
                    try {
                        String content = Files.readString(Path.of(filePath));
                        return "文件内容：\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败" + e.getMessage();
                    }
                }
        ));

        tools.put("file_write", new Tool(
                "file_write",
                "写入文件内容",
                createParameters(new Param("file_path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)),
                args -> {
                    String filePath = args.get("file_path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    try {
                        Files.writeString(Path.of(filePath), content);
                        return "文件写入成功";
                    } catch (Exception e) {
                        return "写入文件失败" + e.getMessage();
                    }
                }
        ));

        tools.put("file_delete", new Tool(
                "file_delete",
                "删除文件",
                createParameters(new Param("file_path", "string", "文件路径", true)),
                args -> {
                    String filePath = args.get("file_path");
                    try {
                        Files.delete(Path.of(filePath));
                        return "文件删除成功";
                    } catch (Exception e) {
                        return "删除文件失败" + e.getMessage();
                    }
                }
        ));

        tools.put("file_rename", new Tool(
                "file_rename",
                "重命名文件",
                createParameters(new Param("file_path", "string", "文件路径", true),
                        new Param("new_name", "string", "新文件名", true)),
                args -> {
                    String filePath = args.get("file_path");
                    String newName = args.get("new_name");
                    try {
                        Files.move(Path.of(filePath), Path.of(newName));
                        return "重命名成功";
                    } catch (IOException e) {
                        return "重命名失败" + e.getMessage();
                    }
                }
        ));

        //list files
        tools.put("file_list", new Tool(
                "file_list",
                "列出指定目录下的文件",
                createParameters(new Param("dir_path", "string", "目录路径", true)),
                args -> {
                    String dirPath = args.get("dir_path");
                    try {
                        StringBuilder sb = new StringBuilder();
                        Files.list(Path.of(dirPath)).forEach(path -> {
                            sb.append(path.getFileName()).append("\n");
                        });
                        return "文件列表：\n" + sb.toString();
                    } catch (IOException e) {
                        return "列出指定目录下的文件失败" + e.getMessage();
                    }
                }
        ));
    }

    /*实际的Tool类  LlmClient.Tool是给客户端展示的 到时候也是从这边读出相应内容 封装到LlmClient.Tool类中
     * name: tool的name
     * description: tool的描述
     * parameters: tool的参数的描述
     * executor: tool的调用方法
     * */
    record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
    }

    /*接口 然后实际的Tool对象的ToolExecutor executor 写一个对应的匿名内部类(对象)重写execute方法完成对应的功能
     *通过调用execute方法来完成tool的调用 第四个参数是对象
     * */
    public interface ToolExecutor {
        String execute(Map<String, String> args);//args 对应LLM返回的tool参数
    }

    private record Param(String name, String type, String description, boolean required) {
    }

    /*
     * 对Tool将要接收LLM输入的Json形式的参数进行约束
     * */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }
}
