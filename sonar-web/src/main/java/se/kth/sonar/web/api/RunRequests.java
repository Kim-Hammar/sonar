package se.kth.sonar.web.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.web.http.HttpError;

/** The body of a request to start a run, and its validation. */
final class RunRequests {
  static final Map<String, Integer> MAXIMA =
      Map.of(
          "scenarios", 1000,
          "verificationWorkers", 16,
          "decisionConcurrency", 64,
          "generationParallelism", 256,
          "scenarioParallelism", 64,
          "prefetchDecisions", 16);

  private RunRequests() {}

  /**
   * A request to start a run.
   *
   * @param name the display name, possibly empty
   * @param scenarios the scenarios to repair
   * @param settings the run settings
   */
  record CreateRun(String name, List<String> scenarios, RunDocument.Settings settings) {}

  static RunSettings validate(CreateRun request, ScenarioRepository repository) {
    if (request.scenarios() == null || request.scenarios().isEmpty()) {
      throw HttpError.badRequest("choose at least one scenario");
    }
    if (request.scenarios().size() > MAXIMA.get("scenarios")) {
      throw HttpError.badRequest("choose at most " + MAXIMA.get("scenarios") + " scenarios");
    }
    if (new HashSet<>(request.scenarios()).size() != request.scenarios().size()) {
      throw HttpError.badRequest("each scenario may be chosen once");
    }
    List<String> known = repository.scenarios();
    for (String scenario : request.scenarios()) {
      if (!known.contains(scenario)) {
        throw HttpError.badRequest("unknown scenario '" + scenario + "'");
      }
    }
    if (request.settings() == null) {
      throw HttpError.badRequest("the execution settings are missing");
    }
    RunDocument.Settings settings = request.settings();
    within("verificationWorkers", settings.verificationWorkers());
    within("decisionConcurrency", settings.decisionConcurrency());
    within("generationParallelism", settings.generationParallelism());
    within("scenarioParallelism", settings.scenarioParallelism());
    within("prefetchDecisions", settings.prefetchDecisions());
    RunSettings parsed;
    try {
      parsed = settings.toRunSettings();
    } catch (IllegalArgumentException e) {
      throw HttpError.badRequest(e.getMessage());
    }
    for (String scenario : request.scenarios()) {
      if (!repository.specificationPercents(scenario).contains(parsed.specificationPercent())) {
        throw HttpError.badRequest(
            scenario + " has no specifications for " + parsed.specificationPercent() + "%");
      }
    }
    return parsed;
  }

  private static void within(String name, int value) {
    if (value > MAXIMA.get(name)) {
      throw HttpError.badRequest(name + " may be at most " + MAXIMA.get(name));
    }
  }
}
