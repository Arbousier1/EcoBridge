package top.ellan.ecobridge.application.control;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible API economic advisor.
 * Supports OpenAI, Azure OpenAI, and any OpenAI-compatible endpoint
 * (Ollama, vLLM, LiteLLM, OpenRouter, etc.).
 *
 * <p>Config keys (config.yml):
 * <pre>
 * ai-co-pilot:
 *   provider: "openai"
 *   api-key: ""
 *   model: "gpt-4o-mini"
 *   base-url: "https://api.openai.com"  # or custom endpoint
 * </pre>
 */
public class OpenAiEconomicAdvisor implements AiEconomicAdvisor {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private volatile boolean available;

    public OpenAiEconomicAdvisor() {
        var plugin = EcoBridge.getInstanceOrNull();
        var config = plugin != null ? plugin.getConfig() : null;

        String configuredKey = config != null ? config.getString("ai-co-pilot.api-key", "") : "";
        this.apiKey = !configuredKey.isEmpty() && !configuredKey.equals("change_me")
            ? configuredKey
            : System.getenv("OPENAI_API_KEY");

        this.model = config != null
            ? config.getString("ai-co-pilot.model", "gpt-4o-mini")
            : "gpt-4o-mini";

        this.baseUrl = config != null
            ? config.getString("ai-co-pilot.base-url", "https://api.openai.com")
            : "https://api.openai.com";

        int timeoutSec = config != null ? config.getInt("ai-co-pilot.timeout-seconds", 5) : 5;
        this.timeout = Duration.ofSeconds(Math.max(3, Math.min(timeoutSec, 15)));

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

        this.available = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("change_me");

        if (available) {
            LogUtil.info("<gradient:green:teal>AI Co-Pilot: OpenAI enabled (model=" + model + ", timeout=" + timeoutSec + "s)");
        } else {
            LogUtil.info("AI Co-Pilot: OpenAI disabled (no API key). Set OPENAI_API_KEY env var or ai-co-pilot.api-key in config.yml");
        }
    }

    @Override
    public AiRecommendation advise(EconomyControlSignals s, String context) {
        if (!available) return AiRecommendation.UNAVAILABLE;

        try {
            String systemPrompt = buildSystemPrompt();
            String userMessage = buildUserMessage(s, context);
            String response = callOpenAiApi(systemPrompt, userMessage);
            return parseResponse(response);
        } catch (Exception e) {
            LogUtil.warnOnce("AI_OPENAI_ERR", "OpenAI advisor call failed: " + e.getMessage());
            return AiRecommendation.UNAVAILABLE;
        }
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public String name() { return "OpenAI-" + model; }

    // --- API call (OpenAI chat completions format) ---

    private String callOpenAiApi(String systemPrompt, String userMessage) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 300);
        body.addProperty("temperature", 0.3);

        // Messages array: system + user
        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        body.add("messages", messages);

        String endpoint = baseUrl.endsWith("/")
            ? baseUrl + "v1/chat/completions"
            : baseUrl + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    // --- Prompt (same as Claude, OpenAI handles it fine) ---

    private String buildSystemPrompt() {
        return """
            You are an economic control AI for a Minecraft server economy plugin called EcoBridge.
            You receive real-time economic indicators and must output a JSON control decision.

            ECONOMIC MODEL:
            - M1 is the total money supply. Target is pre-configured (typically 10M).
            - Lambda multiplier scales price sensitivity. >1.0 = prices change faster (volatile).
              <1.0 = prices change slower (stable). Range: 0.6–2.2.
            - Sink boost removes money from circulation (fights inflation). Range: 0.0–1.0.
            - Faucet boost injects money into circulation (fights deflation). Range: 0.0–1.0.
            - Price index tracks real prices vs baseline. Range: 0.30–2.80.

            YOUR GOALS (in priority order):
            1. Keep M1 within ±20% of target
            2. Keep price index between 0.70–1.30 (healthy range)
            3. Keep inflation rate between -2% and +5%
            4. Avoid oscillation — prefer gradual corrections over aggressive moves

            RESPOND WITH ONLY VALID JSON, NO OTHER TEXT:
            {
              "lambdaMultiplier": 1.0,
              "sinkBoost": 0.0,
              "faucetBoost": 0.0,
              "confidence": 0.8,
              "reasoning": "Brief explanation of your decision"
            }
            """;
    }

    private String buildUserMessage(EconomyControlSignals s, String context) {
        return String.format("""
            Current economic state:
            - M1 Supply: %.0f (target: %.0f, ratio: %.3f)
            - Inflation Rate: %.4f (%.2f%%)
            - Market Heat: %.2f (target velocity: %.3f, online: %d)
            - Faucet Rate: %.1f | Sink Rate: %.1f | Net Flow: %.1f/s
            - Eco Saturation: %.3f (0=idle, 1=overheated)
            - Time step: %.0f seconds

            %s

            What lambdaMultiplier, sinkBoost, and faucetBoost do you recommend?
            """,
            s.m1Supply(), s.targetM1Supply(), s.m1Supply() / Math.max(1.0, s.targetM1Supply()),
            s.inflationRate(), s.inflationRate() * 100.0,
            s.marketHeat(), s.targetVelocity(), s.onlinePlayers(),
            s.faucetRate(), s.sinkRate(), s.faucetRate() - s.sinkRate(),
            s.ecoSaturation(),
            s.dtSeconds(),
            context != null ? "Context: " + context : ""
        );
    }

    // --- Response parsing (OpenAI format: choices[0].message.content) ---

    private AiRecommendation parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // OpenAI format: choices → [0] → message → content
            String content = null;
            if (root.has("choices")) {
                var choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    var choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        content = choice.getAsJsonObject("message").get("content").getAsString();
                    }
                }
            }
            if (content == null) return AiRecommendation.UNAVAILABLE;

            // Extract JSON block from content
            String jsonBlock = extractJsonBlock(content);
            if (jsonBlock == null) return AiRecommendation.UNAVAILABLE;

            JsonObject decision = JsonParser.parseString(jsonBlock).getAsJsonObject();

            double lambda = getDouble(decision, "lambdaMultiplier", 1.0);
            double sink = getDouble(decision, "sinkBoost", 0.0);
            double faucet = getDouble(decision, "faucetBoost", 0.0);
            double confidence = getDouble(decision, "confidence", 0.5);
            String reasoning = decision.has("reasoning")
                ? decision.get("reasoning").getAsString() : "";

            return new AiRecommendation(lambda, sink, faucet, confidence, reasoning);
        } catch (JsonSyntaxException e) {
            LogUtil.debug("OpenAI advisor: failed to parse response JSON: " + e.getMessage());
            return AiRecommendation.UNAVAILABLE;
        }
    }

    private String extractJsonBlock(String content) {
        if (content.contains("```json")) {
            int start = content.indexOf("```json") + 7;
            int end = content.indexOf("```", start);
            return end > start ? content.substring(start, end).trim() : null;
        }
        if (content.contains("```")) {
            int start = content.indexOf("```") + 3;
            int end = content.indexOf("```", start);
            return end > start ? content.substring(start, end).trim() : null;
        }
        if (content.contains("{")) {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            return end > start ? content.substring(start, end + 1).trim() : null;
        }
        return null;
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (!obj.has(key)) return defaultValue;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception e) { return defaultValue; }
    }
}
