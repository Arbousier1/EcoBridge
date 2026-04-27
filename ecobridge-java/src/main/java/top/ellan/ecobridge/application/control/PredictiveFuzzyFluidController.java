package top.ellan.ecobridge.application.control;

/**
 * Predictive + Fuzzy + Sink/Faucet controller.
 * Replaces legacy PID macro tuning with a behavior-oriented policy engine.
 */
public class PredictiveFuzzyFluidController implements MacroControlEngine {

    private final double horizonSeconds;
    private final double maxLambdaMultiplier;
    private final double minLambdaMultiplier;

    public PredictiveFuzzyFluidController(double horizonSeconds, double minLambdaMultiplier, double maxLambdaMultiplier) {
        this.horizonSeconds = Math.max(60.0, horizonSeconds);
        this.minLambdaMultiplier = minLambdaMultiplier;
        this.maxLambdaMultiplier = maxLambdaMultiplier;
    }

    @Override
    public MacroControlDecision decide(EconomyControlSignals s) {
        double targetHeat = Math.max(0.1, s.onlinePlayers() * s.targetVelocity());
        double netFlowRate = s.faucetRate() - s.sinkRate();
        double predictedM1 = s.m1Supply() + netFlowRate * horizonSeconds;
        double supplyRatio = safeRatio(predictedM1, s.targetM1Supply());

        // [v1.7.0] Direct M1 delta feedback — the strongest signal for economic stability.
        // When M1 deviates from target, the controller directly adjusts faucet/sink
        // rather than relying solely on fuzzy inference of derived indicators.
        double m1Deviation = supplyRatio - 1.0; // positive = oversupply, negative = undersupply
        double m1FeedbackStrength = 0.40; // how strongly M1 deviation drives correction

        double inflationHigh = membershipRise(s.inflationRate(), 0.03, 0.18);
        double heatHigh = membershipRise(s.marketHeat(), targetHeat * 0.9, targetHeat * 2.4);
        double overflowHigh = membershipRise(supplyRatio, 1.03, 1.45);

        double deflationHigh = membershipFall(s.inflationRate(), -0.10, -0.005);
        double underflowHigh = membershipFall(supplyRatio, 0.72, 0.98);
        double heatLow = membershipFall(s.marketHeat(), targetHeat * 0.4, targetHeat * 0.9);

        // Fuzzy inference: inflation/overflow + high activity -> stronger sink pressure.
        double sinkBoost = clamp01(Math.max(
                overflowHigh,
                Math.max(inflationHigh * 0.9 + heatHigh * 0.4, s.ecoSaturation() * 0.6)
        ));

        // Fuzzy inference: deflation/underflow + low activity -> faucet support.
        double faucetBoost = clamp01(Math.max(
                underflowHigh,
                Math.max(deflationHigh * 0.9 + heatLow * 0.4, (1.0 - s.ecoSaturation()) * 0.35)
        ));

        // [v1.7.0] Direct M1 target correction.
        // Oversupply (supplyRatio > 1): increase sink, decrease faucet
        // Undersupply (supplyRatio < 1): increase faucet, decrease sink
        if (m1Deviation > 0) {
            sinkBoost = clamp01(sinkBoost + m1Deviation * m1FeedbackStrength);
        } else {
            faucetBoost = clamp01(faucetBoost - m1Deviation * m1FeedbackStrength);
        }

        double lambdaMultiplier = 1.0 + sinkBoost * 0.85 - faucetBoost * 0.55;
        lambdaMultiplier = clamp(lambdaMultiplier, minLambdaMultiplier, maxLambdaMultiplier);

        String reason = "predM1Ratio=" + format(supplyRatio)
                + ", infl=" + format(s.inflationRate())
                + ", heat=" + format(s.marketHeat())
                + ", sink=" + format(sinkBoost)
                + ", faucet=" + format(faucetBoost)
                + ", m1Dev=" + format(m1Deviation);

        return new MacroControlDecision(lambdaMultiplier, sinkBoost, faucetBoost, reason);
    }

    private static double membershipRise(double x, double from, double to) {
        if (x <= from) return 0.0;
        if (x >= to) return 1.0;
        return (x - from) / (to - from);
    }

    private static double membershipFall(double x, double from, double to) {
        if (x <= from) return 1.0;
        if (x >= to) return 0.0;
        return 1.0 - ((x - from) / (to - from));
    }

    private static double safeRatio(double v, double baseline) {
        return baseline <= 0.0 ? 1.0 : v / baseline;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }
}

