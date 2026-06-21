package edu.sdu.smilecli.llmclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

//@Slf4j
public class DeepSeekClient implements LlmClient {

    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build();

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, API_URL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, API_URL);
    }

    DeepSeekClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : API_URL;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    // 转换对象和JsonString
    protected static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);

        ArrayNode messagesArr = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArr.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            // 如果是toolCall
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArr = msgNode.putArray("tool_calls");
                for (ToolCall toolCall : msg.toolCalls()) {
                    ObjectNode toolCallNode = toolCallsArr.addObject();
                    toolCallNode.put("id", toolCall.id());
                    toolCallNode.put("type", "function");
                    ObjectNode functionNode = toolCallNode.putObject("function");
                    functionNode.put("name", toolCall.function().name());
                    functionNode.put("arguments", toolCall.function().arguments());
                }
            }

            //如果是tool消息
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        //添加tools
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArr.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.put("parameters", tool.parameters());

            }
        }

        // 发送http
        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                throw new IOException("API请求失败" + response.code());
            }
            if (responseBody == null) {
                throw new IOException("API请求失败, responseBody为空");
            }
//            log.info("Response: {}", responseBody.toString());
//            System.out.println(responseBody.string());
            //解析content
            JsonNode responseJsonNode = mapper.readTree(responseBody.string());
            JsonNode messageJsonNode = responseJsonNode.path("choices").path(0).path("message");
            String content = messageJsonNode.path("content").asText("");
//            System.out.println( content);

            //解析 toolCall
            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode toolCallJsonNodeArr = messageJsonNode.path("tool_calls");
            if (toolCallJsonNodeArr.isArray()) {
                for (JsonNode toolCallJsonNode : toolCallJsonNodeArr) {
                    String id = toolCallJsonNode.path("id").asText("");

                    JsonNode functionJsonNode = toolCallJsonNode.path("function");
                    String name = functionJsonNode.path("name").asText("");
                    String arguments = functionJsonNode.path("arguments").asText("");
                    toolCalls.add(new ToolCall(id, new ToolCall.Function(name, arguments)));
                }
            }

            //解析token
            JsonNode usageNode = responseJsonNode.path("usage");
            UserToken userToken = new UserToken(usageNode.path("prompt_tokens").asLong(0),
                    usageNode.path("completion_tokens").asLong(0),
                    usageNode.path("total_tokens").asLong(0),
                    MAX_TOKEN - usageNode.path("total_tokens").asInt(0));


            //如果toolCalls为空/没有toolCall字段 上面解析代码不会进入 所以应该返回纯文本的响应
            return new ChatResponse(content, toolCalls.isEmpty() ? null : toolCalls, userToken);
//            return new ChatResponse(content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
