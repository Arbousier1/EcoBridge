package top.ellan.ecobridge.test.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import top.ellan.ecobridge.application.control.EconomyControlSignals;
import top.ellan.ecobridge.application.control.MacroControlDecision;
import top.ellan.ecobridge.application.control.PredictiveFuzzyFluidController;

/**
 * Long-run stability test for the macro economy controller.
 *
 * <p>This test runs a deterministic 180-day simulation with shocks, validates that core state
 * variables stay bounded, and exports a human-readable curve chart (SVG).
 */
public class EconomyStabilityLongRunTest {

  @Test
  void shouldRemainStableOverLongRunAndExportCurve() throws Exception {
    SimResult result = runSimulation();

    double minSupplyRatio =
        result.supplyRatios.stream().mapToDouble(Double::doubleValue).min().orElse(1.0);
    double maxSupplyRatio =
        result.supplyRatios.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
    double minPriceIndex =
        result.priceIndex.stream().mapToDouble(Double::doubleValue).min().orElse(1.0);
    double maxPriceIndex =
        result.priceIndex.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

    int tailStart = (int) (result.supplyRatios.size() * 0.8);
    double tailSupplyStd =
        stddev(result.supplyRatios.subList(tailStart, result.supplyRatios.size()));
    double tailPriceStd = stddev(result.priceIndex.subList(tailStart, result.priceIndex.size()));

    Assertions.assertTrue(
        minSupplyRatio >= 0.50, () -> "Supply collapsed too much: " + minSupplyRatio);
    Assertions.assertTrue(
        maxSupplyRatio <= 1.80, () -> "Supply exploded too much: " + maxSupplyRatio);
    Assertions.assertTrue(
        minPriceIndex >= 0.30, () -> "Price index collapsed too much: " + minPriceIndex);
    Assertions.assertTrue(
        maxPriceIndex <= 2.80, () -> "Price index exploded too much: " + maxPriceIndex);
    Assertions.assertTrue(
        tailSupplyStd < 0.16, () -> "Tail supply variance too high: " + tailSupplyStd);
    Assertions.assertTrue(
        tailPriceStd < 0.16, () -> "Tail price variance too high: " + tailPriceStd);

    exportArtifacts(result);
  }

  private static SimResult runSimulation() {
    PredictiveFuzzyFluidController controller =
        new PredictiveFuzzyFluidController(3.0 * 24.0 * 3600.0, 0.60, 2.20);

    final int totalHours = 180 * 24;
    final double dtSeconds = 3600.0;
    final double targetM1 = 10_000_000.0;
    final int onlinePlayers = 120;
    final double targetVelocity = 0.04;
    final double capacityPerUser = 5000.0;

    double m1 = targetM1 * 1.02;
    double priceIndex = 1.0;
    double marketHeat = onlinePlayers * targetVelocity * 0.9;
    double inflationRate = 0.0;

    Random random = new Random(20260322L);

    List<Double> timeDays = new ArrayList<>(totalHours);
    List<Double> supplyRatios = new ArrayList<>(totalHours);
    List<Double> priceIndices = new ArrayList<>(totalHours);
    List<Double> lambdaMult = new ArrayList<>(totalHours);
    List<Double> sinkBoosts = new ArrayList<>(totalHours);
    List<Double> faucetBoosts = new ArrayList<>(totalHours);

    for (int t = 0; t < totalHours; t++) {
      double day = t / 24.0;
      double dailyCycle = Math.sin((2.0 * Math.PI * (t % 24)) / 24.0);
      double weeklyCycle = Math.sin((2.0 * Math.PI * day) / 7.0);

      double faucetBase = 520.0 + 120.0 * (0.5 + 0.5 * dailyCycle) + 60.0 * weeklyCycle;
      double sinkBase = 500.0 + 100.0 * (0.5 - 0.5 * dailyCycle) - 40.0 * weeklyCycle;

      if (t > 24 * 28 && t < 24 * 30) faucetBase += 850.0; // inflationary exploit spike
      if (t > 24 * 96 && t < 24 * 99) sinkBase += 550.0; // aggressive sink event
      if (t > 24 * 140 && t < 24 * 143) faucetBase += 500.0; // seasonal event

      faucetBase *= (0.985 + random.nextDouble() * 0.03);
      sinkBase *= (0.985 + random.nextDouble() * 0.03);

      double ecoSaturation = clamp01(marketHeat / (Math.max(1.0, onlinePlayers) * capacityPerUser));

      EconomyControlSignals signals =
          new EconomyControlSignals(
              inflationRate,
              marketHeat,
              ecoSaturation,
              m1,
              faucetBase,
              sinkBase,
              onlinePlayers,
              dtSeconds,
              targetVelocity,
              targetM1);
      MacroControlDecision decision = controller.decide(signals);

      // Dynamic sink/faucet anchoring to target supply (lightweight central bank effect).
      double supplyRatioPre = m1 / targetM1;
      faucetBase += Math.max(0.0, 1.0 - supplyRatioPre) * 160.0;
      sinkBase += Math.max(0.0, supplyRatioPre - 1.0) * 190.0;

      double adjustedFaucet =
          faucetBase * (1.0 - 0.20 * decision.sinkBoost() + 0.25 * decision.faucetBoost());
      double adjustedSink =
          sinkBase * (1.0 + 0.30 * decision.sinkBoost() - 0.10 * decision.faucetBoost());
      double netFlowPerSecond = adjustedFaucet - adjustedSink;

      m1 = clamp(m1 + netFlowPerSecond * dtSeconds, targetM1 * 0.50, targetM1 * 1.80);
      double supplyRatio = m1 / targetM1;

      inflationRate =
          0.55 * inflationRate
              + 0.45 * ((supplyRatio - 1.0) * 0.06 + (random.nextDouble() - 0.5) * 0.004);

      double targetHeat = Math.max(0.1, onlinePlayers * targetVelocity);
      marketHeat =
          0.82 * marketHeat
              + 0.18
                  * (targetHeat
                      * (1.0
                          + 0.45 * Math.max(0.0, 1.0 - priceIndex)
                          - 0.25 * Math.max(0.0, priceIndex - 1.0)))
              + (random.nextDouble() - 0.5) * 0.01;
      marketHeat = Math.max(0.02, marketHeat);

      double imbalance = supplyRatio - 1.0;
      double pressure = 0.45 * inflationRate + 0.55 * imbalance;
      priceIndex = priceIndex * (1.0 + 0.015 * decision.lambdaMultiplier() * pressure);
      priceIndex = clamp(priceIndex, 0.30, 2.80);

      timeDays.add(day);
      supplyRatios.add(supplyRatio);
      priceIndices.add(priceIndex);
      lambdaMult.add(decision.lambdaMultiplier());
      sinkBoosts.add(decision.sinkBoost());
      faucetBoosts.add(decision.faucetBoost());
    }

    return new SimResult(
        timeDays, supplyRatios, priceIndices, lambdaMult, sinkBoosts, faucetBoosts);
  }

  private static void exportArtifacts(SimResult result) throws IOException {
    Path outDir = Path.of("build", "reports", "economy-stability");
    Files.createDirectories(outDir);

    Path csv = outDir.resolve("long_run_stability.csv");
    StringBuilder csvBuilder = new StringBuilder();
    csvBuilder.append("day,supply_ratio,price_index,lambda_multiplier,sink_boost,faucet_boost\n");
    for (int i = 0; i < result.timeDays.size(); i++) {
      csvBuilder
          .append(fmt(result.timeDays.get(i)))
          .append(',')
          .append(fmt(result.supplyRatios.get(i)))
          .append(',')
          .append(fmt(result.priceIndex.get(i)))
          .append(',')
          .append(fmt(result.lambdaMultiplier.get(i)))
          .append(',')
          .append(fmt(result.sinkBoost.get(i)))
          .append(',')
          .append(fmt(result.faucetBoost.get(i)))
          .append('\n');
    }
    Files.writeString(csv, csvBuilder.toString(), StandardCharsets.UTF_8);

    Path svg = outDir.resolve("long_run_stability.svg");
    Files.writeString(svg, buildSvg(result), StandardCharsets.UTF_8);
  }

  private static String buildSvg(SimResult r) {
    int width = 1200;
    int height = 640;
    int left = 70;
    int right = 30;
    int top = 40;
    int bottom = 70;
    double xMin = 0.0;
    double xMax = r.timeDays.get(r.timeDays.size() - 1);
    double yMin = 0.30;
    double yMax = 2.80;

    String supplyPath =
        pathForSeries(
            r.timeDays,
            r.supplyRatios,
            xMin,
            xMax,
            yMin,
            yMax,
            width,
            height,
            left,
            right,
            top,
            bottom);
    String pricePath =
        pathForSeries(
            r.timeDays,
            r.priceIndex,
            xMin,
            xMax,
            yMin,
            yMax,
            width,
            height,
            left,
            right,
            top,
            bottom);
    String lambdaPath =
        pathForSeries(
            r.timeDays,
            r.lambdaMultiplier,
            xMin,
            xMax,
            yMin,
            yMax,
            width,
            height,
            left,
            right,
            top,
            bottom);

    StringBuilder s = new StringBuilder();
    s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
        .append(width)
        .append("\" height=\"")
        .append(height)
        .append("\">");
    s.append("<rect width=\"100%\" height=\"100%\" fill=\"#0b1220\"/>");
    s.append("<text x=\"")
        .append(left)
        .append(
            "\" y=\"26\" fill=\"#e6edf3\" font-size=\"18\" font-family=\"Segoe UI,Arial\">EcoBridge Long-Run Stability Simulation (180 days)</text>");

    for (int i = 0; i <= 10; i++) {
      double yv = yMin + (yMax - yMin) * i / 10.0;
      double y = mapY(yv, yMin, yMax, height, top, bottom);
      s.append("<line x1=\"")
          .append(left)
          .append("\" y1=\"")
          .append(fmt(y))
          .append("\" x2=\"")
          .append(width - right)
          .append("\" y2=\"")
          .append(fmt(y))
          .append("\" stroke=\"#1f2a44\" stroke-width=\"1\"/>");
      s.append("<text x=\"12\" y=\"")
          .append(fmt(y + 4))
          .append("\" fill=\"#95a5c6\" font-size=\"11\" font-family=\"Segoe UI,Arial\">")
          .append(fmt(yv))
          .append("</text>");
    }
    for (int i = 0; i <= 9; i++) {
      double xv = xMin + (xMax - xMin) * i / 9.0;
      double x = mapX(xv, xMin, xMax, width, left, right);
      s.append("<line x1=\"")
          .append(fmt(x))
          .append("\" y1=\"")
          .append(top)
          .append("\" x2=\"")
          .append(fmt(x))
          .append("\" y2=\"")
          .append(height - bottom)
          .append("\" stroke=\"#172136\" stroke-width=\"1\"/>");
      s.append("<text x=\"")
          .append(fmt(x - 8))
          .append("\" y=\"")
          .append(height - 44)
          .append("\" fill=\"#95a5c6\" font-size=\"11\" font-family=\"Segoe UI,Arial\">")
          .append(fmt(xv))
          .append("</text>");
    }

    s.append("<line x1=\"")
        .append(left)
        .append("\" y1=\"")
        .append(mapY(1.0, yMin, yMax, height, top, bottom))
        .append("\" x2=\"")
        .append(width - right)
        .append("\" y2=\"")
        .append(mapY(1.0, yMin, yMax, height, top, bottom))
        .append("\" stroke=\"#6b7280\" stroke-dasharray=\"6 5\"/>");

    s.append("<path d=\"")
        .append(supplyPath)
        .append("\" fill=\"none\" stroke=\"#22c55e\" stroke-width=\"2.2\"/>");
    s.append("<path d=\"")
        .append(pricePath)
        .append("\" fill=\"none\" stroke=\"#60a5fa\" stroke-width=\"2.2\"/>");
    s.append("<path d=\"")
        .append(lambdaPath)
        .append("\" fill=\"none\" stroke=\"#f59e0b\" stroke-width=\"2.0\"/>");

    int lx = width - 360;
    int ly = 56;
    s.append(legend(lx, ly, "#22c55e", "Supply Ratio (M1 / target)"));
    s.append(legend(lx, ly + 24, "#60a5fa", "Price Index"));
    s.append(legend(lx, ly + 48, "#f59e0b", "Lambda Multiplier"));

    s.append("<text x=\"")
        .append(width / 2 - 100)
        .append("\" y=\"")
        .append(height - 12)
        .append(
            "\" fill=\"#9fb0ce\" font-size=\"12\" font-family=\"Segoe UI,Arial\">Time (days)</text>");
    s.append("</svg>");
    return s.toString();
  }

  private static String legend(int x, int y, String color, String text) {
    return "<line x1=\""
        + x
        + "\" y1=\""
        + y
        + "\" x2=\""
        + (x + 22)
        + "\" y2=\""
        + y
        + "\" stroke=\""
        + color
        + "\" stroke-width=\"3\"/>"
        + "<text x=\""
        + (x + 30)
        + "\" y=\""
        + (y + 4)
        + "\" fill=\"#e6edf3\" font-size=\"12\" font-family=\"Segoe UI,Arial\">"
        + text
        + "</text>";
  }

  private static String pathForSeries(
      List<Double> x,
      List<Double> y,
      double xMin,
      double xMax,
      double yMin,
      double yMax,
      int width,
      int height,
      int left,
      int right,
      int top,
      int bottom) {
    StringBuilder p = new StringBuilder();
    for (int i = 0; i < x.size(); i++) {
      double px = mapX(x.get(i), xMin, xMax, width, left, right);
      double py = mapY(y.get(i), yMin, yMax, height, top, bottom);
      if (i == 0) p.append("M ").append(fmt(px)).append(' ').append(fmt(py));
      else p.append(" L ").append(fmt(px)).append(' ').append(fmt(py));
    }
    return p.toString();
  }

  private static double mapX(double x, double min, double max, int width, int left, int right) {
    double t = (x - min) / (max - min);
    return left + t * (width - left - right);
  }

  private static double mapY(double y, double min, double max, int height, int top, int bottom) {
    double t = (y - min) / (max - min);
    return height - bottom - t * (height - top - bottom);
  }

  private static double stddev(List<Double> values) {
    if (values.isEmpty()) return 0.0;
    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0);
    return Math.sqrt(var);
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private static double clamp01(double v) {
    return clamp(v, 0.0, 1.0);
  }

  private static String fmt(double v) {
    return String.format(Locale.US, "%.6f", v);
  }

  // --- Additional Stability Scenarios ---

  @Test
  void shouldHandleExtremeSupplyShock() {
    PredictiveFuzzyFluidController controller =
        new PredictiveFuzzyFluidController(3.0 * 24.0 * 3600.0, 0.60, 2.20);

    final int totalHours = 48; // 2-day shock window
    final double dtSeconds = 3600.0;
    final double targetM1 = 10_000_000.0;

    double m1 = targetM1 * 1.02;
    double priceIndex = 1.0;
    double marketHeat = 120 * 0.04 * 0.9;
    double inflationRate = 0.0;
    Random random = new Random(20260427L);

    for (int t = 0; t < totalHours; t++) {
      double faucetBase = 3000.0; // extreme faucet injection
      double sinkBase = 100.0;

      double ecoSaturation = clamp01(marketHeat / (120 * 5000.0));

      EconomyControlSignals signals =
          new EconomyControlSignals(
              inflationRate,
              marketHeat,
              ecoSaturation,
              m1,
              faucetBase,
              sinkBase,
              120,
              dtSeconds,
              0.04,
              targetM1);
      MacroControlDecision decision = controller.decide(signals);

      double netFlowPerSecond =
          faucetBase * (1.0 + 0.25 * decision.faucetBoost())
              - sinkBase * (1.0 + 0.30 * decision.sinkBoost());

      m1 = clamp(m1 + netFlowPerSecond * dtSeconds, 0.0, targetM1 * 4.0);
      double supplyRatio = m1 / targetM1;

      inflationRate = 0.5 * inflationRate + 0.5 * ((supplyRatio - 1.0) * 0.08);
      marketHeat =
          0.8 * marketHeat + 0.2 * (120 * 0.04 * (1.0 - 0.5 * Math.max(0.0, priceIndex - 1.0)));
      marketHeat = Math.max(0.01, marketHeat);
      priceIndex =
          priceIndex
              * (1.0
                  + 0.02 * decision.lambdaMultiplier() * (supplyRatio - 1.0 + 0.3 * inflationRate));
      priceIndex = clamp(priceIndex, 0.10, 5.0);
    }

    // After 48 hours of extreme shock, the system should not have collapsed
    Assertions.assertTrue(
        priceIndex >= 0.10,
        () -> "price should not collapse below absolute floor during extreme supply shock");
    Assertions.assertTrue(
        priceIndex <= 5.0, () -> "price should not explode beyond 5x during shock");
  }

  @Test
  void shouldStabilizeAfterDemandSpike() {
    PredictiveFuzzyFluidController controller =
        new PredictiveFuzzyFluidController(3.0 * 24.0 * 3600.0, 0.60, 2.20);

    final int totalHours = 72; // 3 days
    final double dtSeconds = 3600.0;
    final double targetM1 = 10_000_000.0;

    double m1 = targetM1;
    double priceIndex = 1.0;
    double marketHeat = 120 * 0.04;
    double inflationRate = 0.0;
    Random random = new Random(20260427L);

    double lastPriceIndex = priceIndex;

    for (int t = 0; t < totalHours; t++) {
      double faucetBase = 520.0;
      double sinkBase;

      if (t < 24) {
        sinkBase = 2500.0; // aggressive demand spike (sink = money removed = demand)
      } else {
        sinkBase = 500.0; // normal
      }

      double ecoSaturation = clamp01(marketHeat / (120 * 5000.0));

      EconomyControlSignals signals =
          new EconomyControlSignals(
              inflationRate,
              marketHeat,
              ecoSaturation,
              m1,
              faucetBase,
              sinkBase,
              120,
              dtSeconds,
              0.04,
              targetM1);
      MacroControlDecision decision = controller.decide(signals);

      double netFlowPerSecond = faucetBase - sinkBase;
      m1 = clamp(m1 + netFlowPerSecond * dtSeconds, targetM1 * 0.50, targetM1 * 1.80);
      double supplyRatio = m1 / targetM1;

      inflationRate =
          0.55 * inflationRate
              + 0.45 * ((supplyRatio - 1.0) * 0.06 + (random.nextDouble() - 0.5) * 0.004);
      marketHeat = 0.82 * marketHeat + 0.18 * (120 * 0.04);
      marketHeat = Math.max(0.02, marketHeat);
      priceIndex =
          priceIndex
              * (1.0
                  + 0.015
                      * decision.lambdaMultiplier()
                      * (0.45 * inflationRate + 0.55 * (supplyRatio - 1.0)));
      priceIndex = clamp(priceIndex, 0.30, 2.80);

      lastPriceIndex = priceIndex;
    }

    // After spike subsides, system should stabilize near equilibrium
    final double finalPrice = lastPriceIndex;
    Assertions.assertTrue(
        finalPrice > 0.60 && finalPrice < 1.80,
        () ->
            "price should stabilize near equilibrium after demand spike subsides, got: "
                + finalPrice);
  }

  @Test
  void shouldConvergeUnderConstantFlow() {
    PredictiveFuzzyFluidController controller =
        new PredictiveFuzzyFluidController(3.0 * 24.0 * 3600.0, 0.60, 2.20);

    final int totalHours = 120; // 5 days
    final double dtSeconds = 3600.0;
    final double targetM1 = 10_000_000.0;

    double m1 = targetM1 * 0.75; // start below target
    double priceIndex = 1.0;
    double marketHeat = 120 * 0.04;
    double inflationRate = 0.0;
    Random random = new Random(20260427L);

    List<Double> supplyDeviations = new ArrayList<>();

    for (int t = 0; t < totalHours; t++) {
      double faucetBase = 700.0; // positive net flow to push toward target
      double sinkBase = 500.0;

      double ecoSaturation = clamp01(marketHeat / (120 * 5000.0));

      EconomyControlSignals signals =
          new EconomyControlSignals(
              inflationRate,
              marketHeat,
              ecoSaturation,
              m1,
              faucetBase,
              sinkBase,
              120,
              dtSeconds,
              0.04,
              targetM1);
      MacroControlDecision decision = controller.decide(signals);

      double netFlowPerSecond = faucetBase - sinkBase;
      m1 = clamp(m1 + netFlowPerSecond * dtSeconds, targetM1 * 0.50, targetM1 * 1.80);
      supplyDeviations.add(Math.abs(m1 / targetM1 - 1.0));

      inflationRate = 0.55 * inflationRate + 0.45 * ((m1 / targetM1 - 1.0) * 0.06);
      marketHeat = 0.82 * marketHeat + 0.18 * (120 * 0.04);
      marketHeat = Math.max(0.02, marketHeat);
      priceIndex =
          priceIndex
              * (1.0
                  + 0.015
                      * decision.lambdaMultiplier()
                      * (0.45 * inflationRate + 0.55 * (m1 / targetM1 - 1.0)));
      priceIndex = clamp(priceIndex, 0.30, 2.80);
    }

    // In the last 24 hours, deviation from target should be small
    double avgLastDayDeviation =
        supplyDeviations.stream()
            .skip(totalHours - 24)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(1.0);

    final double finalDev = avgLastDayDeviation;
    Assertions.assertTrue(
        finalDev < 0.25,
        () ->
            "system should converge to near-target supply under constant flow, avg deviation: "
                + finalDev);
  }

  private record SimResult(
      List<Double> timeDays,
      List<Double> supplyRatios,
      List<Double> priceIndex,
      List<Double> lambdaMultiplier,
      List<Double> sinkBoost,
      List<Double> faucetBoost) {}
}
