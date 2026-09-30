package se.kth.sonar.web.api;

import java.util.List;
import java.util.Optional;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.web.http.Call;
import se.kth.sonar.web.http.HttpError;

/** Validation of the scenario and specification file that requests about a scenario name. */
final class ScenarioRequests {
  private ScenarioRequests() {}

  /**
   * Returns the scenario named by the route's {@code name} parameter.
   *
   * @throws HttpError 404 if the dataset has no such scenario
   */
  static String scenario(Call call, ScenarioRepository repository) {
    String name = call.parameter("name");
    if (!repository.contains(name)) {
      throw HttpError.notFound("no scenario " + name);
    }
    return name;
  }

  /**
   * Returns the specification file of {@code name} that {@code requested} names as a percentage, or
   * the default one, which is 10 per cent when it exists and otherwise the smallest.
   *
   * @throws HttpError 404 if the scenario has no such file, or 400 if the request is not a number
   */
  static int percent(Optional<String> requested, String name, ScenarioRepository repository) {
    List<Integer> available = repository.specificationPercents(name);
    if (requested.isEmpty()) {
      if (available.isEmpty()) {
        throw HttpError.notFound(name + " has no specifications");
      }
      return available.contains(10) ? 10 : available.getFirst();
    }
    int percent;
    try {
      percent = Integer.parseInt(requested.get());
    } catch (NumberFormatException e) {
      throw HttpError.badRequest("percent must be a whole number");
    }
    if (!available.contains(percent)) {
      throw HttpError.notFound(name + " has no specifications for " + percent + "%");
    }
    return percent;
  }

  /**
   * Returns the query parameter {@code value} named {@code name} as a whole number, or {@code
   * fallback} when it is absent.
   *
   * @throws HttpError 400 if it is not a whole number or is negative
   */
  static int wholeNumber(Optional<String> value, String name, int fallback) {
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      int number = Integer.parseInt(value.get());
      if (number < 0) {
        throw HttpError.badRequest(name + " must not be negative");
      }
      return number;
    } catch (NumberFormatException e) {
      throw HttpError.badRequest(name + " must be a whole number");
    }
  }
}
