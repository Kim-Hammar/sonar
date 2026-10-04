package se.kth.sonar.app.theta;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.kth.sonar.app.Json;

/**
 * Estimates θ for several decision models from their recorded runs and writes {@code theta.json}
 * with each model's estimate and counts, {@code observations.csv} with every observed rank, and
 * {@code theta.tex} with the comparison figure.
 */
public final class ThetaReport {
  private ThetaReport() {}

  /**
   * Estimates θ for every model in {@code runs}, which maps a model's display name to its runs, and
   * writes the report to {@code output}.
   *
   * @return each model's series, in the order of {@code runs}
   * @throws IllegalArgumentException if a model's runs hold no ranking with a valid repair
   */
  public static List<ThetaFigure.Series> write(Map<String, Path> runs, Path output, long seed)
      throws IOException {
    List<ThetaFigure.Series> series = new ArrayList<>();
    ArrayNode models = Json.mapper().createArrayNode();
    StringBuilder csv = new StringBuilder("model,scenario,rank\n");
    for (Map.Entry<String, Path> entry : runs.entrySet()) {
      RankObservations observations = RankObservations.read(List.of(entry.getValue()));
      if (observations.episodes() == 0) {
        throw new IllegalArgumentException(
            entry.getKey() + ": no ranking with a valid repair in " + entry.getValue());
      }
      ThetaEstimate estimate = ThetaEstimate.of(observations.ranks(), seed);
      series.add(new ThetaFigure.Series(entry.getKey(), observations, estimate));
      ObjectNode model = models.addObject();
      model.put("model", entry.getKey());
      model.put("runs", entry.getValue().toString());
      model.put("theta", estimate.theta());
      model.put("low", estimate.low());
      model.put("high", estimate.high());
      model.put("episodes", observations.episodes());
      model.put("censored", observations.censored());
      model.put("incomplete", observations.incomplete());
      for (int index = 0; index < observations.episodes(); index++) {
        csv.append(entry.getKey().replace(',', ' '))
            .append(',')
            .append(observations.scenarios().get(index))
            .append(',')
            .append(observations.ranks().get(index))
            .append('\n');
      }
    }
    Files.createDirectories(output);
    Json.mapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("theta.json").toFile(), models);
    Files.writeString(output.resolve("observations.csv"), csv);
    Files.writeString(output.resolve("theta.tex"), ThetaFigure.tex(series));
    return series;
  }
}
