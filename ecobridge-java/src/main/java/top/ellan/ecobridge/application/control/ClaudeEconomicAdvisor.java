package top.ellan.ecobridge.application.control;

import com.google.gson.Gson;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Claude API economic advisor.
 * Sends economic state to the Anthropic Claude API and parses structured
 * recommendations from the response.
 *
 * <p>Config keys (config.yml):
 * <pre>
 * ai-co-pilot:
 *   enabled: false
 *   provider: "claude"            # claude | openai | custom
 *   api-key: ""                   # or env ANTHROPIC_API_KEY
 *   model: "claude-haiku-4-5"     # fast & cheap for control decisions
 *   base-url: "https://api.anthropic.com"
 *   timeout-seconds: 5
 *   weight: 0.30                  # 0.0 = pure algorithm, 1.0 = pure AI
 *   system-prompt: "..."          # optional custom system prompt
 * </pre>
 */
public class ClaudeEconomicAdvisor implements AiEconomicAdvisor {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private volatile boolean available;

    public ClaudeEconomicAdvisor() {
        var plugin = EcoBridge.getInstanceOrNull();
        var config = plugin != null ? plugin.getConfig() : null;

        // API key: config first, then env var
        String configuredKey = config != null ? config.getString("ai-co-pilot.api-key", "") : "";
        this.apiKey = !configuredKey.isEmpty() && !configuredKey.equals("change_me")
            ? configuredKey
            : System.getenv("ANTHROPIC_API_KEY");

        this.model = config != null ? config.getString("ai-co-pilot.model", "claude-haiku-4-5-20251001") : "claude-haiku-4-5-20251001";
        this.baseUrl = config != null ? config.getString("ai-co-pilot.base-url", "https://api.anthropic.com") : "https://api.anthropic.com";
        int timeoutSec = config != null ? config.getInt("ai-co-pilot.timeout-seconds", 5) : 5;
        this.timeout = Duration.ofSeconds(Math.max(3, Math.min(timeoutSec, 15)));

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

        this.available = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("change_me");

        if (available) {
            LogUtil.info("<gradient:blue:purple>AI Co-Pilot: Claude enabled (model=" + model + ", timeout=" + timeoutSec + "s)");
        } else {
            LogUtil.info("AI Co-Pilot: disabled (no API key configured). Using pure algorithmic control.");
        }
    }

    @Override
    public AiRecommendation advise(EconomyControlSignals s, String context) {
        if (!available) return AiRecommendation.UNAVAILABLE;

        try {
            String systemPrompt = buildSystemPrompt();
            String userMessage = buildUserMessage(s, context);
            String response = callClaudeApi(systemPrompt, userMessage);
            return parseResponse(response);
        } catch (Exception e) {
            LogUtil.warnOnce("AI_ADVISOR_ERR", "AI economic advisor call failed: " + e.getMessage());
            return AiRecommendation.UNAVAILABLE;
        }
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public String name() { return "Claude-" + model; }

    // --- API call ---

    private String callClaudeApi(String systemPrompt, String userMessage) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 300);
        body.addProperty("temperature", 0.3); // low temp for consistent decisions

        // System prompt
        JsonObject system = new JsonObject();
        system.addProperty("text", systemPrompt);
        body.add("system", gson.toJsonTree(java.util.List.of(
            Map.of("type", "text", "text", systemPrompt)
        )));

        // Messages
        JsonObject userContent = new JsonObject();
        userContent.addProperty("type", "text");
        userContent.addProperty("text", userMessage);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", gson.toJsonTree(java.util.List.of(
            Map.of("type", "text", "text", userMessage)
        )));

        body.add("messages", gson.toJsonTree(java.util.List.of(userMsg)));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    // --- Prompt engineering ---

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
            - Price Index: derived from macro model
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

    // --- Response parsing ---

    private AiRecommendation parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Extract content from Claude's response format
            String content = null;
            if (root.has("content")) {
                var contentArray = root.getAsJsonArray("content");
                if (!contentArray.isEmpty()) {
                    content = contentArray.get(0).getAsJsonObject().get("text").getAsString();
                }
            }
            if (content == null) return AiRecommendation.UNAVAILABLE;

            // Extract JSON from content (Claude may wrap it in markdown code blocks)
            String jsonBlock = content;
            if (content.contains("```json")) {
                jsonBlock = content.substring(content.indexOf("```json") + 7);
                if (jsonBlock.contains("```")) {
                    jsonBlock = jsonBlock.substring(0, jsonBlock.indexOf("```"));
                }
            } else if (content.contains("```")) {
                jsonBlock = content.substring(content.indexOf("```") + 3);
                if (jsonBlock.contains("```")) {
                    jsonBlock = jsonBlock.substring(0, jsonBlock.indexOf("```"));
                }
            } else if (content.contains("{")) {
                jsonBlock = content.substring(content.indexOf("{"));
                if (jsonBlock.contains("}")) {
                    jsonBlock = jsonBlock.substring(0, jsonBlock.lastIndexOf("}") + 1);
                }
            }

            JsonObject decision = JsonParser.parseString(jsonBlock.trim()).getAsJsonObject();

            double lambda = getDouble(decision, "lambdaMultiplier", 1.0);
            double sink = getDouble(decision, "sinkBoost", 0.0);
            double faucet = getDouble(decision, "faucetBoost", 0.0);
            double confidence = getDouble(decision, "confidence", 0.5);
            String reasoning = decision.has("reasoning") ? decision.get("reasoning").getAsString() : "";

            return new AiRecommendation(lambda, sink, faucet, confidence, reasoning);
        } catch (JsonSyntaxException e) {
            LogUtil.debug("AI advisor: failed to parse response JSON: " + e.getMessage());
            return AiRecommendation.UNAVAILABLE;
        }
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (!obj.has(key)) return defaultValue;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception e) { return defaultValue; }
    }
}
