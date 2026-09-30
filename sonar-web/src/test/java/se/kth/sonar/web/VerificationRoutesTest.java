package se.kth.sonar.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.TestDataset;
import se.kth.sonar.app.Workspace;
import se.kth.sonar.batfish.DataPlaneInspector;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.SpecificationSet;

class VerificationRoutesTest {

  @TempDir Path dataset;
  @TempDir Path output;
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<WebServer> servers = new ArrayList<>();
  private final AtomicInteger created = new AtomicInteger();
  private final AtomicInteger closed = new AtomicInteger();

  /** The scripted data plane, or an inspector that fails, counting creations and closings. */
  private final class FakeInspector implements DataPlaneInspector {
    private final boolean fails;

    FakeInspector(boolean fails) {
      this.fails = fails;
      created.incrementAndGet();
    }

    @Override
    public Report inspect(Snapshot snapshot, SpecificationSet specifications) {
      if (fails) {
        throw new IllegalStateException("Batfish exploded");
      }
      return new ScriptedDataPlane().inspect(snapshot, specifications);
    }

    @Override
    public void close() {
      closed.incrementAndGet();
    }
  }

  @AfterEach
  void stop() {
    servers.forEach(WebServer::close);
  }

  private WebServer start(int failures) throws IOException {
    TestDataset.create(dataset, 2);
    WebServer.Config config =
        new WebServer.Config(
            "127.0.0.1",
            0,
            new Workspace(dataset, output),
            HttpTransport.Settings.openRouter(),
            false,
            Duration.ZERO,
            List.of());
    WebServer server =
        WebServer.start(
            config,
            (run, cancellation, sink) -> List.of(),
            () -> new FakeInspector(created.get() < failures));
    servers.add(server);
    return server;
  }

  private HttpResponse<String> get(WebServer server, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(server.uri().resolve(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(WebServer server, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(server.uri().resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return Json.mapper().readTree(response.body());
  }

  private JsonNode awaitStatus(WebServer server, String scenario, String status) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      JsonNode verification =
          json(get(server, "api/scenarios/" + scenario + "/verification?percent=10"))
              .path("verification");
      if (verification.path("status").asText().equals(status)) {
        return verification;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the verification of " + scenario + " never became " + status);
  }

  @Test
  void aScenarioCanBeVerifiedAndItsReportBrowsed() throws Exception {
    WebServer server = start(0);
    String base = "api/scenarios/scenario-001/verification";
    assertThat(get(server, base + "?percent=10").statusCode()).isEqualTo(404);
    HttpResponse<String> started = post(server, base, "{\"percent\":10}");
    assertThat(started.statusCode()).isEqualTo(202);
    assertThat(json(started).path("verification").path("status").asText())
        .isIn("queued", "running", "finished");
    JsonNode finished = awaitStatus(server, "scenario-001", "finished");
    JsonNode summary = finished.path("summary");
    assertThat(summary.path("routers").asInt()).isEqualTo(3);
    assertThat(summary.path("forwardingRows").asInt()).isEqualTo(5);
    assertThat(summary.path("specifications").asInt()).isEqualTo(8);
    assertThat(summary.path("unresolved").asInt()).isEqualTo(4);
    assertThat(summary.path("fixed").asInt()).isEqualTo(1);
    assertThat(summary.path("sideEffects").asInt()).isEqualTo(1);
    assertThat(summary.path("holding").asInt()).isEqualTo(2);
    assertThat(summary.path("solved").asBoolean()).isFalse();
    assertThat(finished.path("elapsedMillis").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(post(server, base, "{\"percent\":10}").statusCode()).isEqualTo(200);
    assertThat(created.get()).isEqualTo(1);

    JsonNode specifications =
        json(get(server, base + "/specifications?percent=10")).path("specifications");
    assertThat(specifications).hasSize(8);
    assertThat(specifications)
        .extracting(row -> row.path("key").asText() + " " + row.path("state").asText())
        .contains(
            "reachability|r1|10.0.0.3/32 fixed",
            "reachability|r1|10.0.0.2/32 unresolved",
            "isolation|r3|10.9.0.0/24 unresolved",
            "reachability|r3|10.0.0.2/32 side-effect",
            "waypointing|r1|10.0.0.3/32|r2 holding");

    JsonNode traces =
        json(
            get(
                server,
                base + "/traces?percent=10&specification=reachability%7Cr1%7C10.0.0.2%2F32"));
    assertThat(traces.path("state").asText()).isEqualTo("unresolved");
    assertThat(traces.path("flows").get(0).path("hops"))
        .extracting(JsonNode::asText)
        .containsExactly("r1", "r2");
    assertThat(traces.path("flows").get(0).path("disposition").asText()).isEqualTo("no-route");
    JsonNode reached =
        json(
            get(
                server,
                base + "/traces?percent=10&specification=reachability%7Cr1%7C10.0.0.3%2F32"));
    assertThat(reached.path("routeHolders")).extracting(JsonNode::asText).containsExactly("r3");
    assertThat(reached.path("forwarders"))
        .extracting(JsonNode::asText)
        .containsExactly("r1", "r2", "r3");
    assertThat(get(server, base + "/traces?percent=10&specification=nothing").statusCode())
        .isEqualTo(404);

    JsonNode forwarding = json(get(server, base + "/forwarding?percent=10&node=r1"));
    assertThat(forwarding.path("total").asInt()).isEqualTo(5);
    assertThat(forwarding.path("matched").asInt()).isEqualTo(2);
    assertThat(forwarding.path("rows")).hasSize(2);
    assertThat(forwarding.path("rows").get(1).path("nextHopInterface").asText())
        .isEqualTo("GigabitEthernet0/0");
    assertThat(forwarding.path("nodes"))
        .extracting(JsonNode::asText)
        .containsExactly("r1", "r2", "r3");
    assertThat(forwarding.path("actions"))
        .extracting(JsonNode::asText)
        .containsExactly("accepted", "forwarded", "forwarded_blocked_incoming_filter", "no_route");
    JsonNode blocked =
        json(get(server, base + "/forwarding?percent=10&action=forwarded_blocked_incoming_filter"));
    assertThat(blocked.path("rows").get(0).path("receivers"))
        .extracting(JsonNode::asText)
        .containsExactly("r1");
    JsonNode page =
        json(get(server, base + "/forwarding?percent=10&prefix=10.0.0.3&offset=1&limit=1"));
    assertThat(page.path("matched").asInt()).isEqualTo(3);
    assertThat(page.path("rows")).hasSize(1);
    assertThat(page.path("rows").get(0).path("node").asText()).isEqualTo("r2");
    assertThat(get(server, base + "/forwarding?percent=10&limit=abc").statusCode()).isEqualTo(400);

    JsonNode routes = json(get(server, base + "/routes?percent=10")).path("routes");
    assertThat(routes).hasSize(1);
    assertThat(routes.get(0).path("prefix").asText()).isEqualTo("10.0.0.3/32");
    assertThat(routes.get(0).path("holders")).extracting(JsonNode::asText).containsExactly("r3");

    assertThat(post(server, base, "{\"percent\":50}").statusCode()).isEqualTo(404);
    assertThat(post(server, "api/scenarios/scenario-009/verification", "{}").statusCode())
        .isEqualTo(404);
    assertThat(get(server, "api/scenarios/scenario-002/verification/forwarding").statusCode())
        .isEqualTo(404);
  }

  @Test
  void aFailedVerificationReportsTheErrorAndCanBeRetried() throws Exception {
    WebServer server = start(1);
    String base = "api/scenarios/scenario-001/verification";
    assertThat(post(server, base, "{}").statusCode()).isEqualTo(202);
    JsonNode failed = awaitStatus(server, "scenario-001", "failed");
    assertThat(failed.path("error").asText()).isEqualTo("Batfish exploded");
    assertThat(failed.has("summary")).isFalse();
    assertThat(get(server, base + "/specifications?percent=10").statusCode()).isEqualTo(409);
    assertThat(closed.get()).isEqualTo(1);
    assertThat(post(server, base, "{}").statusCode()).isEqualTo(202);
    assertThat(
            awaitStatus(server, "scenario-001", "finished").path("summary").path("fixed").asInt())
        .isEqualTo(1);
    assertThat(created.get()).isEqualTo(2);
  }
}
