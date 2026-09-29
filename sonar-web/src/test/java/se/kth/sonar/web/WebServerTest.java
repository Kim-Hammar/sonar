package se.kth.sonar.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.TestDataset;
import se.kth.sonar.app.Workspace;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;
import se.kth.sonar.web.runs.RunLauncher;

class WebServerTest {
  private static final String RUN =
      "{\"name\":\"t\",\"scenarios\":[\"scenario-001\",\"scenario-002\"],\"settings\":"
          + "{\"specificationPercent\":10,\"verificationWorkers\":1,\"decisionConcurrency\":1,\"generationParallelism\":1,"
          + "\"scenarioParallelism\":2,\"prefetchDecisions\":0,\"limits\":{\"verifications\":8},\"seed\":5}}";

  @TempDir Path dataset;
  @TempDir Path output;
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<WebServer> servers = new ArrayList<>();

  @AfterEach
  void stop() {
    servers.forEach(WebServer::close);
  }

  private WebServer start(boolean demo) throws IOException {
    TestDataset.create(dataset, 2);
    WebServer server = WebServer.start(config(demo));
    servers.add(server);
    return server;
  }

  private WebServer start(RunLauncher launcher) throws IOException {
    TestDataset.create(dataset, 2);
    WebServer server = WebServer.start(config(false), launcher);
    servers.add(server);
    return server;
  }

  private WebServer.Config config(boolean demo) {
    return new WebServer.Config(
        "127.0.0.1",
        0,
        new Workspace(dataset, output),
        HttpTransport.Settings.openRouter(),
        demo,
        Duration.ZERO,
        List.of());
  }

  private HttpResponse<String> get(WebServer server, String path, String... headers)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(server.uri().resolve(path)).GET();
    for (int index = 0; index < headers.length; index += 2) {
      request.header(headers[index], headers[index + 1]);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(
      WebServer server, String path, String type, String body, String... headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(server.uri().resolve(path))
            .header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    for (int index = 0; index < headers.length; index += 2) {
      request.header(headers[index], headers[index + 1]);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return Json.mapper().readTree(response.body());
  }

  private JsonNode awaitStatus(WebServer server, String id, String status) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      JsonNode run = json(get(server, "api/runs/" + id)).path("run");
      if (run.path("status").asText().equals(status)) {
        return run;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("run " + id + " never became " + status);
  }

  private static String rawStatusLine(URI uri, String request) throws IOException {
    try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
      OutputStream output = socket.getOutputStream();
      output.write(request.getBytes(StandardCharsets.US_ASCII));
      output.flush();
      return new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }

  @Test
  void infoDescribesTheServerAndItsDefaults() throws Exception {
    WebServer server = start(false);
    HttpResponse<String> response = get(server, "api/info");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    JsonNode info = json(response);
    assertThat(info.path("batfish").asText()).isEqualTo("0.36.0");
    assertThat(info.path("model").asText()).isEqualTo("~typesafe/jev-latest");
    assertThat(info.path("demo").asBoolean()).isFalse();
    assertThat(info.path("defaults").path("specificationPercent").asInt()).isEqualTo(10);
    assertThat(info.path("limits").path("verificationWorkers").asInt()).isEqualTo(16);
    assertThat(info.path("resources"))
        .extracting(JsonNode::asText)
        .contains("verifications", "input_tokens");
  }

  @Test
  void theDatasetCanBeBrowsed() throws Exception {
    WebServer server = start(false);
    assertThat(json(get(server, "api/scenarios")).path("scenarios"))
        .extracting(JsonNode::asText)
        .containsExactly("scenario-001", "scenario-002");
    JsonNode summary = json(get(server, "api/scenarios/scenario-001"));
    assertThat(summary.path("routers").asInt()).isEqualTo(3);
    assertThat(summary.path("links").asInt()).isEqualTo(2);
    assertThat(summary.path("specificationPercents"))
        .extracting(JsonNode::asInt)
        .containsExactly(10);
    JsonNode topology = json(get(server, "api/scenarios/scenario-001/topology"));
    assertThat(topology.path("routers").get(0).path("asn").asLong()).isEqualTo(65001);
    assertThat(topology.path("links").get(0).path("aInterface").asText())
        .isEqualTo("GigabitEthernet0/0");
    JsonNode specifications =
        json(get(server, "api/scenarios/scenario-001/specifications?percent=10"));
    assertThat(specifications.path("specifications")).hasSize(8);
    assertThat(specifications.path("specifications").get(7).path("waypoint").asText())
        .isEqualTo("r2");
    HttpResponse<String> config = get(server, "api/scenarios/scenario-001/routers/r2/config");
    assertThat(config.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
    assertThat(config.body()).startsWith("hostname R2");
    assertThat(get(server, "api/scenarios/scenario-009").statusCode()).isEqualTo(404);
    assertThat(json(get(server, "api/scenarios/scenario-009")).path("error").asText())
        .contains("no scenario");
    assertThat(get(server, "api/scenarios/scenario-001/routers/r9/config").statusCode())
        .isEqualTo(404);
    assertThat(get(server, "api/scenarios/scenario-001/specifications?percent=50").statusCode())
        .isEqualTo(404);
  }

  @Test
  void requestsFromOtherSitesAreRefused() throws Exception {
    WebServer server = start(false);
    URI uri = server.uri();
    assertThat(
            rawStatusLine(
                uri,
                "GET /api/info HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n"))
        .contains(" 403 ");
    assertThat(
            rawStatusLine(
                uri, "GET /api/info HTTP/1.1\r\nHost: localhost:1\r\nConnection: close\r\n\r\n"))
        .contains(" 200 ");
    assertThat(
            post(server, "api/runs", "application/json", RUN, "Origin", "http://attacker.example")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            post(server, "api/runs", "application/json", RUN, "Sec-Fetch-Site", "cross-site")
                .statusCode())
        .isEqualTo(403);
    assertThat(post(server, "api/runs", "text/plain", RUN).statusCode()).isEqualTo(415);
    assertThat(post(server, "api/runs", "application/json", "{nope").statusCode()).isEqualTo(400);
    assertThat(post(server, "api/runs", "application/json", "x".repeat((1 << 20) + 1)).statusCode())
        .isEqualTo(413);
    assertThat(
            post(server, "api/runs", "application/json", RUN.replace("scenario-002", "../etc"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    server,
                    "api/runs",
                    "application/json",
                    RUN.replace("\"verificationWorkers\":1", "\"verificationWorkers\":99"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(
                    server,
                    "api/runs",
                    "application/json",
                    RUN.replace("\"specificationPercent\":10", "\"specificationPercent\":50"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post(server, "api/runs", "application/json", RUN, "Origin", "http://localhost:5173")
                .statusCode())
        .isEqualTo(201);
    assertThat(get(server, "api/runs/not-a-run").statusCode()).isEqualTo(404);
    assertThat(post(server, "api/info", "application/json", "{}").statusCode()).isEqualTo(405);
  }

  @Test
  void theBundleIsServedWithCacheRulesAndPageFallback() throws Exception {
    WebServer server = start(false);
    assertThat(server.bundled()).isTrue();
    HttpResponse<String> index = get(server, "");
    assertThat(index.statusCode()).isEqualTo(200);
    assertThat(index.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
    assertThat(index.headers().firstValue("Cache-Control")).contains("no-cache");
    assertThat(index.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(policy -> assertThat(policy).contains("default-src 'self'"));
    HttpResponse<String> asset = get(server, "assets/app-test.js");
    assertThat(asset.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/javascript"));
    assertThat(asset.headers().firstValue("Cache-Control"))
        .hasValueSatisfying(cache -> assertThat(cache).contains("immutable"));
    HttpResponse<String> page = get(server, "runs/20260101-000000-abcd", "Accept", "text/html");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
    assertThat(get(server, "assets/app-gone.js", "Accept", "text/html").statusCode())
        .isEqualTo(404);
    assertThat(get(server, "runs/x", "Accept", "application/json").statusCode()).isEqualTo(404);
    assertThat(
            rawStatusLine(
                server.uri(),
                "GET /assets/../../etc/passwd HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
        .contains(" 404 ");
  }

  @Test
  void aDemoRunCanBeFollowedAndInspected() throws Exception {
    WebServer server = start(true);
    HttpResponse<String> created = post(server, "api/runs", "application/json", RUN);
    assertThat(created.statusCode()).isEqualTo(201);
    String id = json(created).path("run").path("id").asText();
    JsonNode run = awaitStatus(server, id, "finished");
    assertThat(run.path("demo").asBoolean()).isTrue();
    JsonNode page = json(get(server, "api/runs/" + id + "/events?after=0"));
    JsonNode events = page.path("events");
    assertThat(page.path("sealed").asBoolean()).isTrue();
    assertThat(events.size()).isEqualTo(page.path("size").asInt()).isGreaterThan(6);
    assertThat(events.get(0).path("seq").asLong()).isEqualTo(1);
    List<String> types = new ArrayList<>();
    events.forEach(event -> types.add(event.path("type").asText()));
    assertThat(types).contains("loaded", "baseline", "space-built", "decided", "tried", "finished");
    long decided = 0;
    for (JsonNode event : events) {
      if (event.path("type").asText().equals("decided")) {
        decided = event.path("seq").asLong();
        assertThat(event.path("usage").path("inputTokens").asLong()).isPositive();
        break;
      }
    }
    JsonNode detail = json(get(server, "api/runs/" + id + "/events/" + decided));
    assertThat(detail.path("context").size()).isEqualTo(3);
    assertThat(detail.path("exchanges").get(0).path("request").asText()).contains("\"questions\"");
    assertThat(detail.path("answer").path("regions").size()).isPositive();
    assertThat(get(server, "api/runs/" + id + "/events/99999").statusCode()).isEqualTo(404);
    HttpResponse<Stream<String>> stream =
        client.send(
            HttpRequest.newBuilder(server.uri().resolve("api/runs/" + id + "/stream?after=0"))
                .header("Last-Event-ID", "3")
                .build(),
            HttpResponse.BodyHandlers.ofLines());
    assertThat(stream.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
    List<String> lines = stream.body().toList();
    assertThat(lines).contains("retry: 3000", "event: end");
    String firstData =
        lines.stream().filter(line -> line.startsWith("data: [")).findFirst().orElseThrow();
    assertThat(Json.mapper().readTree(firstData.substring(6)).get(0).path("seq").asLong())
        .isEqualTo(4);
    assertThat(lines).contains("id: " + events.size());
    HttpResponse<String> nothingLeft =
        get(server, "api/runs/" + id + "/stream?after=" + events.size());
    assertThat(nothingLeft.statusCode()).isEqualTo(204);
    assertThat(post(server, "api/runs/" + id + "/cancel", "application/json", "{}").statusCode())
        .isEqualTo(409);
  }

  @Test
  void eventsStreamWhileARunIsActive() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    RunLauncher launcher =
        (run, cancellation, sink) -> {
          sink.accept(
              new TraceRecord(
                  "scenario-001", Instant.now(), Map.of(), new TraceEvent.Failed("first")));
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          sink.accept(
              new TraceRecord(
                  "scenario-001", Instant.now(), Map.of(), new TraceEvent.Failed("second")));
          return List.of(new BenchmarkRunner.Outcome.Failed("scenario-001", "second"));
        };
    WebServer server = start(launcher);
    String id =
        json(post(server, "api/runs", "application/json", RUN)).path("run").path("id").asText();
    awaitStatus(server, id, "running");
    HttpResponse<Stream<String>> stream =
        client.send(
            HttpRequest.newBuilder(server.uri().resolve("api/runs/" + id + "/stream?after=0"))
                .build(),
            HttpResponse.BodyHandlers.ofLines());
    Iterator<String> lines = stream.body().iterator();
    String first = next(lines, "data: ");
    assertThat(first).contains("\"reason\":\"first\"");
    release.countDown();
    String second = next(lines, "data: ");
    assertThat(second).contains("\"reason\":\"second\"");
    assertThat(next(lines, "event: end")).isEqualTo("event: end");
    awaitStatus(server, id, "finished");
  }

  private static String next(Iterator<String> lines, String prefix) {
    while (lines.hasNext()) {
      String line = lines.next();
      if (line.startsWith(prefix)) {
        return line;
      }
    }
    throw new AssertionError("the stream ended before a line starting with " + prefix);
  }

  @Test
  void cancellingARunStopsIt() throws Exception {
    RunLauncher launcher =
        (run, cancellation, sink) -> {
          while (!cancellation.isCancelled()) {
            try {
              Thread.sleep(5);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
          return List.of();
        };
    WebServer server = start(launcher);
    String first =
        json(post(server, "api/runs", "application/json", RUN)).path("run").path("id").asText();
    String second =
        json(post(server, "api/runs", "application/json", RUN)).path("run").path("id").asText();
    awaitStatus(server, first, "running");
    assertThat(json(get(server, "api/runs/" + second)).path("run").path("status").asText())
        .isEqualTo("queued");
    assertThat(
            post(server, "api/runs/" + second + "/cancel", "application/json", "{}").statusCode())
        .isEqualTo(202);
    awaitStatus(server, second, "cancelled");
    assertThat(post(server, "api/runs/" + first + "/cancel", "application/json", "{}").statusCode())
        .isEqualTo(202);
    awaitStatus(server, first, "cancelled");
    assertThat(json(get(server, "api/runs")).path("runs").get(0).path("id").asText())
        .isEqualTo(second);
  }

  @Test
  void runsSurviveARestart() throws Exception {
    WebServer server = start(true);
    String id =
        json(post(server, "api/runs", "application/json", RUN)).path("run").path("id").asText();
    awaitStatus(server, id, "finished");
    long size = json(get(server, "api/runs/" + id)).path("events").asLong();
    server.close();
    servers.remove(server);
    WebServer restarted = WebServer.start(config(false));
    servers.add(restarted);
    JsonNode run = json(get(restarted, "api/runs/" + id));
    assertThat(run.path("run").path("status").asText()).isEqualTo("finished");
    assertThat(run.path("events").asLong()).isEqualTo(size);
    assertThat(json(get(restarted, "api/runs/" + id + "/events?after=0&limit=2")).path("events"))
        .hasSize(2);
  }
}
