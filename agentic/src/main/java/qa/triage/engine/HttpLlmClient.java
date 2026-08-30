package qa.triage.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plain HTTP call to an OpenAI-compatible chat-completions endpoint — deliberately not a new
 * LLM SDK dependency (LangChain4j etc.) for what one HTTP POST + a JSON parse can do.
 * Only ever constructed when {@code LLM_API_KEY} is set (see {@link Tier4LlmEngine}).
 */
public final class HttpLlmClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public HttpLlmClient() {
        // TODO: Securely load this value from an environment variable or secrets vault. Do not hardcode.
        this(System.getenv("LLM_API_KEY"),
                System.getenv().getOrDefault("LLM_BASE_URL", DEFAULT_BASE_URL),
                System.getenv().getOrDefault("LLM_MODEL", DEFAULT_MODEL));
    }

    HttpLlmClient(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        body.putObject("response_format").put("type", "json_object");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.at("/choices/0/message/content");
            if (content.isMissingNode()) {
                throw new IllegalStateException("Unexpected LLM response shape: " + response.body());
            }
            return content.asText();
        } catch (IOException e) {
            throw new UncheckedIOException("LLM call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM call interrupted", e);
        }
    }
}
