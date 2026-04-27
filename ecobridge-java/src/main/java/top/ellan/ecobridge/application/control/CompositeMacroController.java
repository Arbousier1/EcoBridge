package top.ellan.ecobridge.application.control;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Composite macro-economic controller that blends AI recommendations
 * with the deterministic fuzzy-fluid algorithm.
 *
 * <p>Decision blending (configurable via {@code ai-co-pilot.weight}):
 * <pre>
 *   final = algorithm × (1 - weight) + ai × weight
 * </pre>
 *
 * <p>Graceful degradation: if the AI call fails or times out, the algorithm
 * result is used unchanged. The AI is strictly a co-pilot — it advises,
 * but the algorithm always provides a safe fallback.
 */
public class CompositeMacroController implements MacroControlEngine {

    private final PredictiveFuzzyFluidController algorithm;
    private final AiEconomicAdvisor aiAdvisor;
    private final double aiWeight;
    private final boolean aiEnabled;

    public CompositeMacroController(PredictiveFuzzyFluidController algorithm) {
        this.algorithm = algorithm;

        var plugin = EcoBridge.getInstanceOrNull();
        var config = plugin != null ? plugin.getConfig() : null;
        boolean enabled = config != null && config.getBoolean("ai-co-pilot.enabled", false);

        if (enabled) {
            String provider = config.getString("ai-co-pilot.provider", "claude");
            this.aiAdvisor = switch (provider) {
                case "claude", "anthropic" -> new ClaudeEconomicAdvisor();
                default -> {
                    LogUtil.warn("AI Co-Pilot: unknown provider '" + provider + "', using Claude");
                    yield new ClaudeEconomicAdvisor();
                }
            };
            this.aiWeight = clampWeight(config.getDouble("ai-co-pilot.weight", 0.30));
            this.aiEnabled = aiAdvisor.isAvailable();
        } else {
            this.aiAdvisor = null;
            this.aiWeight = 0.0;
            this.aiEnabled = false;
        }

        if (aiEnabled) {
            LogUtil.info("<gradient:blue:purple>AI Co-Pilot active: " + aiAdvisor.name()
                + " (weight=" + String.format("%.0f%%", aiWeight * 100) + ")");
        }
    }

    private static double clampWeight(double w) {
        return Math.max(0.0, Math.min(1.0, w));
    }

    @Override
    public MacroControlDecision decide(EconomyControlSignals signals) {
        // 1. Algorithm always runs first — it's the safe baseline
        MacroControlDecision algoDecision = algorithm.decide(signals);

        // 2. If AI is disabled, return pure algorithm
        if (!aiEnabled || aiAdvisor == null) {
            return algoDecision;
        }

        // 3. Try AI — with timeout, the algorithm result is the fallback
        AiRecommendation aiRec;
        try {
            String context = "Previous algorithm decision: lambda="
                + String.format("%.3f", algoDecision.lambdaMultiplier())
                + ", sink=" + String.format("%.3f", algoDecision.sinkBoost())
                + ", faucet=" + String.format("%.3f", algoDecision.faucetBoost());
            aiRec = aiAdvisor.advise(signals, context);
        } catch (Exception e) {
            LogUtil.debug("AI advisor exception, falling back to algorithm: " + e.getMessage());
            return algoDecision;
        }

        // 4. If AI returned invalid result, use algorithm only
        if (!aiRec.isValid()) {
            return algoDecision;
        }

        // 5. Blend: algorithm × (1 - w) + AI × w
        double w = aiWeight * aiRec.confidence(); // weight scaled by AI confidence
        double blendedLambda = algoDecision.lambdaMultiplier() * (1.0 - w) + aiRec.lambdaMultiplier() * w;
        double blendedSink   = algoDecision.sinkBoost() * (1.0 - w) + aiRec.sinkBoost() * w;
        double blendedFaucet = algoDecision.faucetBoost() * (1.0 - w) + aiRec.faucetBoost() * w;

        // Clamp to safe ranges
        blendedLambda = Math.max(0.4, Math.min(3.0, blendedLambda));
        blendedSink = Math.max(0.0, Math.min(1.0, blendedSink));
        blendedFaucet = Math.max(0.0, Math.min(1.0, blendedFaucet));

        String reason = String.format(
            "blend(w=%.2f|conf=%.2f) algo(L=%.3f,S=%.3f,F=%.3f) ai(L=%.3f,S=%.3f,F=%.3f) => L=%.3f | %s",
            aiWeight, aiRec.confidence(),
            algoDecision.lambdaMultiplier(), algoDecision.sinkBoost(), algoDecision.faucetBoost(),
            aiRec.lambdaMultiplier(), aiRec.sinkBoost(), aiRec.faucetBoost(),
            blendedLambda,
            aiRec.reasoning().length() > 80
                ? aiRec.reasoning().substring(0, 80) + "..."
                : aiRec.reasoning()
        );

        if (aiWeight > 0.0 && aiRec.confidence() > 0.5) {
            LogUtil.logTransactionSampled(
                "<gradient:blue:purple>[AI-CoPilot]</gradient> <gray>" + reason);
        }

        return new MacroControlDecision(blendedLambda, blendedSink, blendedFaucet, reason);
    }

    /** Get the underlying algorithm controller (for testing). */
    public PredictiveFuzzyFluidController getAlgorithmController() {
        return algorithm;
    }

    /** Whether AI co-pilot is currently active. */
    public boolean isAiEnabled() {
        return aiEnabled;
    }

    /** Current AI blending weight. */
    public double getAiWeight() {
        return aiWeight;
    }
}
