import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        var apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(prompt).build()));

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                                .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of(
                                        "file_path", Map.of("type", "string")
                                )))
                                .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("file_path")))
                                .build())
                        .build())
                .build();

                ChatCompletionTool writeTool = ChatCompletionTool.builder()
                    .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                            .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of(
                                "file_path", Map.of(
                                    "type", "string",
                                    "description", "The path of the file to write"
                                ),
                                "content", Map.of(
                                    "type", "string",
                                    "description", "The content to write to the file"
                                )
                            )))
                            .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("file_path", "content")))
                            .build())
                        .build())
                    .build();

                ChatCompletionTool bashTool = ChatCompletionTool.builder()
                    .function(FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                            .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of(
                                "command", Map.of(
                                    "type", "string",
                                    "description", "The shell command to execute"
                                )
                            )))
                            .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("command")))
                            .build())
                        .build())
                    .build();

        while (true) {
            ChatCompletion response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model("anthropic/claude-haiku-4.5")
                            .messages(messages)
                            .addTool(readTool)
                            .addTool(writeTool)
                            .addTool(bashTool)
                            .build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            List<com.openai.models.chat.completions.ChatCompletionMessageToolCall> toolCalls =
                    message.toolCalls().orElse(List.of());
            ChatCompletionAssistantMessageParam.Builder assistantMessage =
                    ChatCompletionAssistantMessageParam.builder().toolCalls(toolCalls);
            message.content().ifPresent(assistantMessage::content);
            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.build()));

            if (toolCalls.isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
            }

            for (var toolCall : toolCalls) {
                JsonNode arguments;
                try {
                    arguments = new ObjectMapper().readTree(toolCall.function().arguments());
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new RuntimeException("invalid tool arguments", exception);
                }
                String toolResult;
                if ("Bash".equals(toolCall.function().name())) {
                    String command = arguments.path("command").asText(null);
                    if (command == null) {
                        throw new RuntimeException("Bash tool call is missing command");
                    }
                    toolResult = executeBash(command);
                } else if ("Read".equals(toolCall.function().name())) {
                    String filePath = arguments.path("file_path").asText(null);
                    if (filePath == null) {
                        throw new RuntimeException("Read tool call is missing file_path");
                    }
                    try {
                        toolResult = Files.readString(Path.of(filePath));
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException("failed to read file: " + filePath, exception);
                    }
                } else if ("Write".equals(toolCall.function().name())) {
                    String filePath = arguments.path("file_path").asText(null);
                    if (filePath == null) {
                        throw new RuntimeException("Write tool call is missing file_path");
                    }
                    String content = arguments.path("content").asText(null);
                    if (content == null) {
                        throw new RuntimeException("Write tool call is missing content");
                    }
                    try {
                        Files.writeString(Path.of(filePath), content);
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException("failed to write file: " + filePath, exception);
                    }
                    toolResult = "File written successfully";
                } else {
                    throw new RuntimeException("unsupported tool: " + toolCall.function().name());
                }

                messages.add(ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.id())
                                .content(toolResult)
                                .build()));
            }
        }
    }

    private static String executeBash(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return output;
            }
            return output + "\nCommand exited with code " + exitCode;
        } catch (java.io.IOException exception) {
            throw new RuntimeException("failed to execute Bash command", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Bash command was interrupted", exception);
        }
    }
}
