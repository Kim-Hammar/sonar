package se.kth.sonar.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

class SearchRoutesTest {
  private static final String BASE = "api/scenarios/scenario-001/search";

  @TempDir Path dataset;
  @TempDir Path output;
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<WebServer> servers = new ArrayList<>();
  private final AtomicInteger created = new AtomicInteger();

  /** The scripted data plane, except that the first {@code failures} inspectors fail. */
  private final class FailingFirst implements DataPlaneInspector {
    private final boolean fails;

    FailingFirst(int failures) {
      this.fails = created.getAndIncrement() < failures;
    }

    @Override
    public Report inspect(Snapshot snapshot, SpecificationSet specifications) {
      if (fails) {
        throw new IllegalStateException("Batfish exploded");
      }
      return new ScriptedDataPlane().inspect(snapshot, specifications);
    }

    @Override
    public void close() {}
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
            config, (run, cancellation, sink) -> List.of(), () -> new FailingFirst(failures));
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

  private static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private JsonNode awaitSearch(WebServer server, String status) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      JsonNode search = json(get(server, BASE + "?percent=10")).path("search");
      if (search.path("status").asText().equals(status)) {
        return search;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the search space never became " + status);
  }

  private JsonNode awaitExpansion(WebServer server, String id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      JsonNode region =
          json(get(server, BASE + "/region?percent=10&id=" + encoded(id))).path("region");
      String status = region.path("expansion").path("status").asText();
      if (!status.equals("queued") && !status.equals("running")) {
        return region;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the expansion of " + id + " never settled");
  }

  @Test
  void aSearchSpaceCanBeBuiltBrowsedAndExpanded() throws Exception {
    WebServer server = start(0);
    assertThat(get(server, BASE + "?percent=10").statusCode()).isEqualTo(404);
    assertThat(get(server, BASE + "/regions?percent=10").statusCode()).isEqualTo(404);
    HttpResponse<String> started = post(server, BASE, "{\"percent\":10}");
    assertThat(started.statusCode()).isEqualTo(202);
    assertThat(json(started).path("search").path("status").asText())
        .isIn("queued", "verifying", "building", "finished");
    JsonNode search = awaitSearch(server, "finished");
    assertThat(search.path("factors"))
        .extracting(JsonNode::asText)
        .containsExactly(
            "spectrum",
            "relation",
            "agreement-outlier",
            "first-match-dominance",
            "counterexample",
            "policy-cut",
            "path-differential",
            "frontier",
            "drop");
    JsonNode summary = search.path("summary");
    assertThat(summary.path("routers").asInt()).isEqualTo(3);
    assertThat(summary.path("specifications").asInt()).isEqualTo(8);
    assertThat(summary.path("unresolved").asInt()).isEqualTo(4);
    assertThat(summary.path("regions").asInt()).isGreaterThan(3);
    assertThat(summary.path("partitions").asInt()).isPositive();
    assertThat(summary.path("bound").asLong()).isPositive();
    assertThat(summary.path("expanded").asInt()).isZero();
    assertThat(search.path("elapsedMillis").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(post(server, BASE, "{\"percent\":10}").statusCode()).isEqualTo(200);

    JsonNode regions = json(get(server, BASE + "/regions?percent=10"));
    int total = regions.path("total").asInt();
    assertThat(total).isEqualTo(summary.path("regions").asInt());
    assertThat(regions.path("matched").asInt()).isEqualTo(total);
    assertThat(regions.path("regions")).hasSize(total);
    double previous = Double.MAX_VALUE;
    double mass = 0;
    for (JsonNode row : regions.path("regions")) {
      double prior = row.path("prior").asDouble();
      assertThat(prior).isLessThanOrEqualTo(previous);
      previous = prior;
      mass += prior;
      assertThat(row.path("kind").asText()).isIn("site", "relation", "incidence", "transfer");
      assertThat(row.path("expansion").asText()).isEqualTo("deferred");
      assertThat(row.path("factors").fieldNames())
          .toIterable()
          .containsExactly(
              "spectrum",
              "relation",
              "agreement-outlier",
              "first-match-dominance",
              "counterexample",
              "policy-cut",
              "path-differential",
              "frontier",
              "drop");
      assertThat(row.path("partition").asText()).isNotEmpty();
    }
    assertThat(mass).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(regions.path("routers")).extracting(JsonNode::asText).contains("r1");
    assertThat(regions.path("partitions").size()).isEqualTo(summary.path("partitions").asInt());

    JsonNode sites = json(get(server, BASE + "/regions?percent=10&kind=site"));
    assertThat(sites.path("regions")).isNotEmpty();
    assertThat(sites.path("regions"))
        .extracting(row -> row.path("kind").asText())
        .containsOnly("site");
    JsonNode r1 = json(get(server, BASE + "/regions?percent=10&router=r1"));
    assertThat(r1.path("regions"))
        .allSatisfy(
            row -> assertThat(row.path("routers")).extracting(JsonNode::asText).contains("r1"));
    JsonNode page = json(get(server, BASE + "/regions?percent=10&offset=1&limit=2"));
    assertThat(page.path("regions")).hasSize(Math.min(2, total - 1));
    assertThat(page.path("regions").get(0).path("id").asText())
        .isEqualTo(regions.path("regions").get(1).path("id").asText());
    String siteId = sites.path("regions").get(0).path("id").asText();
    JsonNode named = json(get(server, BASE + "/regions?percent=10&query=" + encoded(siteId)));
    assertThat(named.path("regions")).extracting(row -> row.path("id").asText()).contains(siteId);
    assertThat(get(server, BASE + "/regions?percent=10&kind=galaxy").statusCode()).isEqualTo(400);
    assertThat(get(server, BASE + "/regions?percent=10&limit=abc").statusCode()).isEqualTo(400);

    JsonNode detail =
        json(get(server, BASE + "/region?percent=10&id=" + encoded(siteId))).path("region");
    assertThat(detail.path("kind").asText()).isEqualTo("site");
    assertThat(detail.path("site").path("ref").asText()).isNotEmpty();
    assertThat(detail.path("site").path("statements").get(0).path("lines")).isNotEmpty();
    assertThat(detail.path("specifications")).isNotEmpty();
    assertThat(detail.path("expansion").path("status").asText()).isEqualTo("deferred");
    assertThat(detail.has("candidates")).isFalse();
    assertThat(get(server, BASE + "/region?percent=10").statusCode()).isEqualTo(400);
    assertThat(get(server, BASE + "/region?percent=10&id=site:nothing:r9").statusCode())
        .isEqualTo(404);

    HttpResponse<String> expanding =
        post(server, BASE + "/expand", "{\"percent\":10,\"region\":\"" + siteId + "\"}");
    assertThat(expanding.statusCode()).isEqualTo(202);
    assertThat(json(expanding).path("region").path("expansion").path("status").asText())
        .isIn("queued", "running", "admitted", "dropped");
    JsonNode expanded = awaitExpansion(server, siteId);
    String status = expanded.path("expansion").path("status").asText();
    assertThat(status).isIn("admitted", "dropped");
    if (status.equals("admitted")) {
      assertThat(expanded.path("candidates")).isNotEmpty();
      JsonNode candidate = expanded.path("candidates").get(0);
      assertThat(candidate.path("abbreviated").asText()).isNotEmpty();
      assertThat(candidate.path("support").asText()).isIn("evidenced", "observed", "invented");
      assertThat(candidate.path("edits")).isNotEmpty();
      for (JsonNode edit : candidate.path("edits")) {
        if (Set.of("delete", "replace").contains(edit.path("op").asText())) {
          assertThat(edit.path("removed")).isNotEmpty();
        }
      }
      assertThat(
              post(server, BASE + "/expand", "{\"percent\":10,\"region\":\"" + siteId + "\"}")
                  .statusCode())
          .isEqualTo(200);
    } else {
      assertThat(expanded.path("candidates").asInt()).isZero();
    }
    JsonNode after = json(get(server, BASE + "?percent=10")).path("search").path("summary");
    assertThat(after.path("expanded").asInt() + after.path("dropped").asInt()).isEqualTo(1);
    assertThat(
            post(server, BASE + "/expand", "{\"percent\":10,\"region\":\"site:nothing:r9\"}")
                .statusCode())
        .isEqualTo(404);
    assertThat(post(server, BASE + "/expand", "{\"percent\":10}").statusCode()).isEqualTo(400);
    assertThat(post(server, BASE, "{\"percent\":50}").statusCode()).isEqualTo(404);
    assertThat(post(server, "api/scenarios/scenario-009/search", "{}").statusCode()).isEqualTo(404);
  }

  @Test
  void aFailedVerificationFailsTheSearchSpaceUntilItIsRequestedAgain() throws Exception {
    WebServer server = start(1);
    assertThat(post(server, BASE, "{}").statusCode()).isEqualTo(202);
    JsonNode failed = awaitSearch(server, "failed");
    assertThat(failed.path("error").asText())
        .isEqualTo("the verification failed: Batfish exploded");
    assertThat(failed.has("summary")).isFalse();
    assertThat(get(server, BASE + "/regions?percent=10").statusCode()).isEqualTo(409);
    assertThat(
            post(server, BASE + "/expand", "{\"percent\":10,\"region\":\"site:x:y\"}").statusCode())
        .isEqualTo(409);
    assertThat(post(server, BASE, "{}").statusCode()).isEqualTo(202);
    assertThat(awaitSearch(server, "finished").path("summary").path("regions").asInt())
        .isPositive();
  }
}
