package se.kth.sonar.web.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.SearchJson;
import se.kth.sonar.app.SpecificationJson;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.BgpMesh;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.MeshRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.web.explore.SearchExplorer;

/** Writes the regions of an explored search space, with their prior, candidates and facts. */
final class RegionJson {
  private RegionJson() {}

  /** Returns the row of {@code region} in the regions table. */
  static ObjectNode row(Region region, SearchExplorer.Model model) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("id", region.id().value());
    node.put("kind", SearchJson.kind(region));
    ArrayNode routers = node.putArray("routers");
    region.routers().forEach(router -> routers.add(router.name()));
    node.put("targets", region.targets().size());
    node.put("bound", region.bound());
    node.put("partition", model.epoch().space().partitionOf(region.id()).id().value());
    node.put("prior", model.prior().probability(region.id()));
    SequencedMap<String, Double> breakdown = model.breakdown().get(region.id());
    double weight = 1;
    ObjectNode factors = node.putObject("factors");
    for (Map.Entry<String, Double> entry : breakdown.entrySet()) {
      factors.put(entry.getKey(), entry.getValue());
      weight *= entry.getValue();
    }
    node.put("weight", weight);
    node.put("expansion", expansionStatus(model.expansions().get(region.id())));
    if (region.hypotheses() instanceof Region.Hypotheses.Admitted admitted) {
      node.put("candidates", admitted.list().size());
    }
    return node;
  }

  /** Returns the detail of {@code region}: its row, targets, facts, expansion and candidates. */
  static ObjectNode detail(Region region, SearchExplorer.Model model, Clock clock) {
    ObjectNode node = row(region, model);
    ArrayNode targets = node.putArray("specifications");
    for (Specification target : region.targets()) {
      targets.add(SpecificationJson.row(target));
    }
    switch (region) {
      case SiteRegion site -> site(node.putObject("site"), site.site(), model);
      case RelationRegion relation -> relation(node.putObject("relation"), relation.instance());
      case IncidenceRegion incidence ->
          incidence(node.putObject("incidence"), incidence.incidence());
      case MeshRegion mesh -> mesh(node.putObject("mesh"), mesh.mesh());
      case TransferRegion transfer -> transfer(node.putObject("transfer"), transfer.transfer());
    }
    expansion(node.putObject("expansion"), model.expansions().get(region.id()), clock);
    if (region.hypotheses() instanceof Region.Hypotheses.Admitted admitted) {
      ArrayNode candidates = node.putArray("candidates");
      for (Hypothesis hypothesis : admitted.list()) {
        ObjectNode candidate = SearchJson.hypothesis(hypothesis, model.epoch().state().snapshot());
        candidate.put("abbreviated", hypothesis.id().abbreviated());
        candidates.add(candidate);
      }
    }
    return node;
  }

  /** Returns the detail of a region the engine dropped: its facts and why it went. */
  static ObjectNode dropped(Region region, SearchExplorer.Model model, Clock clock) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("id", region.id().value());
    node.put("kind", SearchJson.kind(region));
    ArrayNode routers = node.putArray("routers");
    region.routers().forEach(router -> routers.add(router.name()));
    node.put("targets", region.targets().size());
    node.put("bound", 0);
    node.put("candidates", 0);
    switch (region) {
      case SiteRegion site -> site(node.putObject("site"), site.site(), model);
      case RelationRegion relation -> relation(node.putObject("relation"), relation.instance());
      case IncidenceRegion incidence ->
          incidence(node.putObject("incidence"), incidence.incidence());
      case MeshRegion mesh -> mesh(node.putObject("mesh"), mesh.mesh());
      case TransferRegion transfer -> transfer(node.putObject("transfer"), transfer.transfer());
    }
    expansion(node.putObject("expansion"), model.expansions().get(region.id()), clock);
    return node;
  }

  private static void site(ObjectNode node, ProgramObject.Ref ref, SearchExplorer.Model model) {
    node.put("ref", ref.toString());
    node.put("type", ref.type());
    node.put("router", ref.router().name());
    ArrayNode key = node.putArray("key");
    ref.key().forEach(key::add);
    Optional<ProgramObject> object = model.epoch().state().program().object(ref);
    ObjectNode attributes = node.putObject("attributes");
    ArrayNode statements = node.putArray("statements");
    if (object.isEmpty()) {
      return;
    }
    object
        .get()
        .attributes()
        .forEach((name, values) -> values.forEach(attributes.putArray(name)::add));
    ConfigText text = model.epoch().state().snapshot().text(ref.router());
    for (SourceSpan span : object.get().statements()) {
      ObjectNode statement = statements.addObject();
      statement.put("first", span.first());
      statement.put("last", span.last());
      ArrayNode lines = statement.putArray("lines");
      for (int line = span.first(); line <= span.last(); line++) {
        lines.add(text.line(line));
      }
    }
  }

  private static void mesh(ObjectNode node, BgpMesh mesh) {
    node.put("member", mesh.member().toString());
    node.put("asn", mesh.asn());
    ArrayNode peers = node.putArray("peers");
    mesh.peers().forEach(peer -> peers.add(peer.toString()));
  }

  private static void relation(ObjectNode node, RelationInstance instance) {
    node.put("name", instance.relation());
    ObjectNode roles = node.putObject("roles");
    instance.roles().forEach((role, ref) -> roles.put(role, ref.toString()));
    ArrayNode violated = node.putArray("violated");
    for (Requirement requirement : instance.violated()) {
      ObjectNode entry = violated.addObject();
      entry.put("strength", requirement.strength().name().toLowerCase(Locale.ROOT));
      entry.put("text", requirement.text());
    }
    ArrayNode routers = node.putArray("routers");
    instance.dependencyRouters().stream().map(RouterId::name).sorted().forEach(routers::add);
  }

  private static void incidence(ObjectNode node, BgpIncidenceSet incidence) {
    node.put("reflector", incidence.reflector().toString());
    ArrayNode clients = node.putArray("clients");
    incidence.clients().forEach(client -> clients.add(client.toString()));
    ArrayNode directions = node.putArray("directions");
    for (BgpIncidenceSet.Direction direction : incidence.directions()) {
      ObjectNode entry = directions.addObject();
      entry.put("source", direction.source().toString());
      entry.put("target", direction.target().toString());
      entry.put("peer", direction.peer());
      ArrayNode groups = entry.putArray("groups");
      for (BgpIncidenceSet.Group group : direction.groups()) {
        ObjectNode member = groups.addObject();
        member.put("name", group.name());
        member.put("template", group.template().toString());
        member.put("nextHopSelf", group.nextHopSelf());
      }
    }
  }

  private static void transfer(ObjectNode node, ProtocolTransfer transfer) {
    node.put("policy", transfer.policy().toString());
    ArrayNode blockers = node.putArray("blockers");
    transfer.blockers().forEach(blocker -> blockers.add(blocker.toString()));
    transfer
        .attachment()
        .ifPresent(
            attachment -> {
              ObjectNode value = node.putObject("attachment");
              value.put("neighbor", attachment.neighbor().toString());
              value.put("direction", attachment.direction());
            });
  }

  private static String expansionStatus(SearchExplorer.Expansion expansion) {
    return expansion == null ? "deferred" : expansion.state().name().toLowerCase(Locale.ROOT);
  }

  private static void expansion(ObjectNode node, SearchExplorer.Expansion expansion, Clock clock) {
    node.put("status", expansionStatus(expansion));
    if (expansion == null) {
      return;
    }
    node.put("requested", expansion.requested().toEpochMilli());
    expansion.started().ifPresent(at -> node.put("started", at.toEpochMilli()));
    expansion.ended().ifPresent(at -> node.put("ended", at.toEpochMilli()));
    expansion
        .started()
        .ifPresent(
            started ->
                node.put(
                    "elapsedMillis",
                    expansion.ended().orElseGet(clock::instant).toEpochMilli()
                        - started.toEpochMilli()));
    expansion.error().ifPresent(error -> node.put("error", error));
  }
}
