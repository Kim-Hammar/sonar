package se.kth.sonar.web.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.app.Versions;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.web.WebServer;
import se.kth.sonar.web.http.Router;

/** Routes that describe the server: versions, locations, the decision model and run defaults. */
public final class InfoRoutes {
  private InfoRoutes() {}

  /** Adds the routes to {@code router}. */
  public static void register(Router router, WebServer.Config config) {
    router.get(
        "/api/info",
        call -> {
          Map<String, Object> info = new LinkedHashMap<>();
          info.put("sonar", Versions.sonar());
          info.put("batfish", Versions.batfish());
          info.put("java", Versions.java());
          info.put("dataset", config.workspace().dataset().toString());
          info.put("output", config.workspace().output().toString());
          info.put("model", config.decisions().model());
          info.put("endpoint", config.decisions().endpoint().toString());
          info.put("demo", config.demo());
          info.put("defaults", RunDocument.Settings.of(RunSettings.defaults()));
          info.put("limits", RunRequests.MAXIMA);
          info.put(
              "resources",
              Arrays.stream(Budget.Resource.values())
                  .filter(resource -> !resource.name().startsWith("SPECULATIVE"))
                  .map(resource -> resource.name().toLowerCase(Locale.ROOT))
                  .toList());
          call.json(200, info);
        });
  }
}
