import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionTool;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
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

        ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("anthropic/claude-haiku-4.5")
                        .addUserMessage(prompt)
                        .addTool(ChatCompletionTool.builder()
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
                            .build())
                        .build()
        );

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        var message = response.choices().get(0).message();
        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            var toolCall = message.toolCalls().get().get(0);
            if (!"Read".equals(toolCall.function().name())) {
                throw new RuntimeException("unsupported tool: " + toolCall.function().name());
            }

            JsonNode arguments;
            try {
                arguments = new ObjectMapper().readTree(toolCall.function().arguments());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new RuntimeException("invalid Read tool arguments", exception);
            }
            String filePath = arguments.path("file_path").asText(null);
            if (filePath == null) {
                throw new RuntimeException("Read tool call is missing file_path");
            }

            try {
                System.out.print(Files.readString(Path.of(filePath)));
            } catch (java.io.IOException exception) {
                throw new RuntimeException("failed to read file: " + filePath, exception);
            }
        } else {
            System.out.print(message.content().orElse(""));
        }
    }
}
