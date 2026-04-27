package top.ellan.ecobridge.application.control;

/**
 * Structured recommendation from an AI economic advisor.
 * Mirrors MacroControlDecision but with an additional confidence score.
 */
public record AiRecommendation(
    double lambdaMultiplier,
    double sinkBoost,
    double faucetBoost,
    double confidence,    // 0.0–1.0: how confident the AI is in this recommendation
    String reasoning       // natural-language explanation from the AI
) {
    public static final AiRecommendation UNAVAILABLE =
        new AiRecommendation(1.0, 0.0, 0.0, 0.0, "AI unavailable");

    public boolean isValid() {
        return confidence > 0.0
            && Double.isFinite(lambdaMultiplier) && lambdaMultiplier >= 0.3 && lambdaMultiplier <= 4.0
            && Double.isFinite(sinkBoost) && sinkBoost >= 0.0 && sinkBoost <= 1.0
            && Double.isFinite(faucetBoost) && faucetBoost >= 0.0 && faucetBoost <= 1.0;
    }
}
