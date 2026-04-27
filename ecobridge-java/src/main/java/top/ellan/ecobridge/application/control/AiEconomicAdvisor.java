package top.ellan.ecobridge.application.control;

/**
 * AI economic advisor interface.
 * Implementations call an LLM API to provide economic control recommendations.
 * The AI acts as a co-pilot, not a replacement — its output is blended with
 * the deterministic algorithm at a configurable weight.
 */
public interface AiEconomicAdvisor {

    /**
     * Query the AI for economic control recommendations.
     *
     * @param signals current economic state snapshot
     * @param context  optional human-readable context (recent events, anomalies)
     * @return AI recommendation, or {@code null} if unavailable / timed out
     */
    AiRecommendation advise(EconomyControlSignals signals, String context);

    /** Whether this advisor is available (API key configured, network reachable). */
    boolean isAvailable();

    /** Human-readable name for logging. */
    String name();
}
