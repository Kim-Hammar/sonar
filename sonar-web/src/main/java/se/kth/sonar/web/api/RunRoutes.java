package se.kth.sonar.web.api;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.RunLog;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.app.RunStore;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.web.http.Call;
import se.kth.sonar.web.http.HttpError;
import se.kth.sonar.web.http.Router;
import se.kth.sonar.web.runs.RunManager;

/**
 * Routes that start, list, cancel and follow runs.
 *
 * <p>History comes in pages from {@code /events}. New events stream from {@code /stream} as
 * Server-Sent Events: each message carries a batch of summaries with the last sequence number as
 * its identifier, idle periods carry heartbeats, and an {@code end} message follows the last event
 * of a finished run. A request for a finished run with nothing left is answered with 204, which
 * stops the browser from reconnecting.
 */
public final class RunRoutes {
  private static final int PAGE = 1000;
  private static final int BATCH = 500;
  private static final Duration HEARTBEAT = Duration.ofSeconds(15);
  private static final byte[] RETRY = "retry: 3000\n\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] END = "event: end\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);

  private RunRoutes() {}

  /** Adds the routes to {@code router}. */
  public static void register(
      Router router,
      RunManager runs,
      ScenarioRepository scenarios,
      boolean demo,
      String model,
      BooleanSupplier stopping) {
    router.get("/api/runs", call -> call.json(200, Map.of("runs", runs.runs())));
    router.post(
        "/api/runs",
        call -> {
          RunRequests.CreateRun request = call.body(RunRequests.CreateRun.class);
          RunSettings settings = RunRequests.validate(request, scenarios);
          String name = request.name() == null ? "" : request.name();
          if (name.length() > 200) {
            throw HttpError.badRequest("the name may have at most 200 characters");
          }
          RunDocument document = runs.submit(name, request.scenarios(), settings, demo, model);
          call.json(201, Map.of("run", document));
        });
    router.get(
        "/api/runs/{id}",
        call -> {
          RunDocument document = runs.run(id(call)).orElseThrow(() -> missing(call));
          long events = runs.log(document.id()).map(RunLog::size).orElse(0L);
          call.json(200, Map.of("run", document, "events", events));
        });
    router.post(
        "/api/runs/{id}/cancel",
        call -> {
          try {
            call.json(202, Map.of("run", runs.cancel(id(call)).orElseThrow(() -> missing(call))));
          } catch (IllegalStateException e) {
            throw HttpError.conflict(e.getMessage());
          }
        });
    router.get(
        "/api/runs/{id}/events",
        call -> {
          RunLog log = log(runs, call);
          long after = number(call.query("after").orElse("0"), "after");
          int limit =
              (int)
                  Math.min(
                      PAGE, number(call.query("limit").orElse(Integer.toString(PAGE)), "limit"));
          List<String> summaries = log.summaries(after, limit);
          String body =
              "{\"events\":["
                  + String.join(",", summaries)
                  + "],\"last\":"
                  + (after + summaries.size())
                  + ",\"size\":"
                  + log.size()
                  + ",\"sealed\":"
                  + log.sealed()
                  + "}";
          call.rawJson(200, body);
        });
    router.get(
        "/api/runs/{id}/events/{seq}",
        call -> {
          RunLog log = log(runs, call);
          long seq = number(call.parameter("seq"), "seq");
          call.rawJson(
              200, log.detail(seq).orElseThrow(() -> HttpError.notFound("no event " + seq)));
        });
    router.get("/api/runs/{id}/stream", call -> stream(call, log(runs, call), stopping));
  }

  private static void stream(Call call, RunLog log, BooleanSupplier stopping)
      throws IOException, InterruptedException {
    long after =
        number(
            call.header("Last-Event-ID").orElseGet(() -> call.query("after").orElse("0")), "after");
    if (log.sealed() && log.size() <= after) {
      call.noContent();
      return;
    }
    try (OutputStream output = call.stream("text/event-stream; charset=utf-8")) {
      output.write(RETRY);
      output.flush();
      while (!stopping.getAsBoolean()) {
        if (log.awaitAfter(after, HEARTBEAT)) {
          List<String> batch = log.summaries(after, BATCH);
          after += batch.size();
          String message =
              "event: events\nid: " + after + "\ndata: [" + String.join(",", batch) + "]\n\n";
          output.write(message.getBytes(StandardCharsets.UTF_8));
        } else if (log.sealed()) {
          output.write(END);
          output.flush();
          return;
        } else {
          output.write(PING);
        }
        output.flush();
      }
    }
  }

  private static String id(Call call) {
    String id = call.parameter("id");
    if (!RunStore.validId(id)) {
      throw HttpError.notFound("no run " + id);
    }
    return id;
  }

  private static RunLog log(RunManager runs, Call call) throws IOException {
    return runs.log(id(call)).orElseThrow(() -> missing(call));
  }

  private static HttpError missing(Call call) {
    return HttpError.notFound("no run " + call.parameter("id"));
  }

  private static long number(String text, String name) {
    try {
      long value = Long.parseLong(text);
      if (value < 0) {
        throw HttpError.badRequest(name + " must not be negative");
      }
      return value;
    } catch (NumberFormatException e) {
      throw HttpError.badRequest(name + " must be a whole number");
    }
  }
}
