package se.kth.sonar.decision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.MeshRegion;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Builds the questions the search asks, with the facts the decision model needs to answer them.
 *
 * <p>Sections come in a fixed order: the setting, the violated specifications, the verifier's
 * evidence, the relevant configuration excerpts, the program relations of the objects involved, and
 * what verification has already shown about the choices. Each choice is described by where it
 * edits, what it restores, and its exact edits when they are few. A question larger than the
 * payload limit is rebuilt with less detail, in steps, and reported as too large only when the
 * least detailed form still exceeds the limit.
 */
public final class DecisionProblemFactory {
  private static final int MAX_SPECIFICATIONS = 24;
  private static final int MAX_TRACES = 16;
  private static final int MAX_EXCERPT_LINES = 40;
  private static final int MAX_FEEDBACK = 12;
  private static final int EXACT_EDITS = 3;
  private static final int SHOWN_STATEMENTS = 4;
  private static final int LEAST_DETAIL = 2;

  private final int payloadLimit;

  /** Creates a factory for questions of at most {@code payloadLimit} characters. */
  public DecisionProblemFactory(int payloadLimit) {
    if (payloadLimit < 1) {
      throw new IllegalArgumentException("payload limit must be at least 1: " + payloadLimit);
    }
    this.payloadLimit = payloadLimit;
  }

  /** Returns the largest question size the factory tries to stay within. */
  public int payloadLimit() {
    return payloadLimit;
  }

  /**
   * Returns the question of which region of {@code partition} holds the repair, given what {@code
   * feedback} has shown about candidates tried in the current state.
   */
  public DecisionProblem.RegionChoice regionChoice(
      RepairState state,
      SearchSpace space,
      Partition partition,
      List<TransitionLedger.Transition> feedback) {
    List<Region> regions = new ArrayList<>();
    for (Region.Id id : partition.regions()) {
      regions.add(space.region(id).orElseThrow());
    }
    Set<Region.Id> ids = new LinkedHashSet<>(partition.regions());
    List<TransitionLedger.Transition> relevant =
        feedback.stream().filter(transition -> ids.contains(transition.region())).toList();
    DecisionProblem.RegionChoice problem = null;
    for (int detail = 0; detail <= LEAST_DETAIL; detail++) {
      Describer describer = new Describer(state, detail);
      SequencedMap<String, String> options = new LinkedHashMap<>();
      Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
      for (Region region : regions) {
        options.put(region.id().value(), describer.region(region));
        objects.addAll(describer.objectsOf(region));
      }
      List<DecisionProblem.Context.Section> sections = new ArrayList<>();
      sections.add(
          new DecisionProblem.Context.Section(
              "Setting",
              describer.setting(
                  "Each option is a region: candidate edits to one coherent program object or "
                      + "relation. Decide which region holds an edit that fixes at "
                      + "least one of the violated specifications below.")));
      List<Specification> targets = new ArrayList<>();
      for (Region region : regions) {
        for (Specification target : region.targets()) {
          if (!targets.contains(target)) {
            targets.add(target);
          }
        }
      }
      sections.add(describer.specifications(targets));
      sections.add(describer.evidence(targets));
      describer.excerpts(objects).ifPresent(sections::add);
      describer.relations(objects).ifPresent(sections::add);
      describer.feedback(relevant).ifPresent(sections::add);
      problem =
          new DecisionProblem.RegionChoice(
              partition, regions, options, new DecisionProblem.Context(sections));
      if (problem.size() <= payloadLimit) {
        break;
      }
    }
    return problem;
  }

  /** Returns the question of how to order the candidates of {@code region}. */
  public DecisionProblem.HypothesisChoice hypothesisChoice(
      RepairState state, Region region, List<TransitionLedger.Transition> feedback) {
    List<Hypothesis> hypotheses =
        region.hypotheses() instanceof Region.Hypotheses.Admitted admitted
            ? admitted.list()
            : List.of();
    List<TransitionLedger.Transition> relevant =
        feedback.stream().filter(transition -> transition.region().equals(region.id())).toList();
    DecisionProblem.HypothesisChoice problem = null;
    for (int detail = 0; detail <= LEAST_DETAIL; detail++) {
      Describer describer = new Describer(state, detail);
      SequencedMap<String, String> options = new LinkedHashMap<>();
      for (Hypothesis hypothesis : hypotheses) {
        options.put(hypothesis.id().abbreviated(), describer.hypothesis(hypothesis));
      }
      Set<ProgramObject.Ref> objects = describer.objectsOf(region);
      List<DecisionProblem.Context.Section> sections = new ArrayList<>();
      sections.add(
          new DecisionProblem.Context.Section(
              "Setting",
              describer.setting(
                  "Each option is one candidate edit in the region "
                      + describer.region(region)
                      + ". Order the candidates by how likely each is to fix at least one of the "
                      + "violated specifications below without breaking others.")));
      sections.add(describer.specifications(region.targets()));
      sections.add(describer.evidence(region.targets()));
      describer.excerpts(objects).ifPresent(sections::add);
      describer.relations(objects).ifPresent(sections::add);
      describer.feedback(relevant).ifPresent(sections::add);
      problem =
          new DecisionProblem.HypothesisChoice(
              region, hypotheses, options, new DecisionProblem.Context(sections));
      if (problem.size() <= payloadLimit) {
        break;
      }
    }
    return problem;
  }

  /** Renders the facts of one state at one level of detail: 0 is full, higher is less. */
  private static final class Describer {
    private final RepairState state;
    private final int detail;
    private final ProgramModel program;
    private final Map<RouterId, Map<Integer, Statement>> statementsByLine = new HashMap<>();

    Describer(RepairState state, int detail) {
      this.state = state;
      this.detail = detail;
      this.program = state.program();
    }

    String setting(String task) {
      Verdict verdict = state.verification().verdict();
      return "A network of "
          + state.snapshot().routers().size()
          + " Cisco IOS routers fails "
          + verdict.unresolved().size()
          + " of "
          + state.problem().specifications().size()
          + " forwarding specifications after a configuration fault"
          + (verdict.fixed().isEmpty() ? "" : "; " + verdict.fixed().size() + " were fixed already")
          + ". "
          + task;
    }

    DecisionProblem.Context.Section specifications(List<Specification> targets) {
      Map<String, Integer> byType = new TreeMap<>();
      targets.forEach(target -> byType.merge(target.predicate().type(), 1, Integer::sum));
      StringBuilder body = new StringBuilder();
      body.append(targets.size()).append(" violated: ");
      byType.forEach((type, count) -> body.append(count).append(' ').append(type).append(", "));
      body.setLength(body.length() - 2);
      body.append('\n');
      int shown = detail == 0 ? MAX_SPECIFICATIONS : MAX_SPECIFICATIONS / 2;
      for (Specification target : targets.subList(0, Math.min(shown, targets.size()))) {
        body.append(predicate(target.predicate())).append('\n');
      }
      if (targets.size() > shown) {
        body.append("and ").append(targets.size() - shown).append(" more\n");
      }
      return new DecisionProblem.Context.Section(
          "Violated specifications", body.toString().strip());
    }

    DecisionProblem.Context.Section evidence(List<Specification> targets) {
      DataPlaneEvidence evidence = state.verification().evidence();
      StringBuilder body = new StringBuilder();
      Set<String> seen = new LinkedHashSet<>();
      Set<Prefix> destinations = new LinkedHashSet<>();
      int shown = detail == 0 ? MAX_TRACES : MAX_TRACES / 2;
      for (Specification target : targets) {
        destinations.add(target.predicate().destination());
        for (DataPlaneEvidence.Trace trace :
            evidence.traces().getOrDefault(target.predicate(), List.of())) {
          String line = trace(trace) + " for " + target.predicate().destination();
          if (seen.size() < shown && seen.add(line)) {
            body.append(line).append('\n');
          }
        }
      }
      int described = 0;
      RouterEvidence routerEvidence = RouterEvidence.of(state);
      Set<List<Object>> describedBoundaries = new LinkedHashSet<>();
      for (Prefix destination : destinations) {
        if (described++ == shown) {
          body.append("and ").append(destinations.size() - shown).append(" more destinations\n");
          break;
        }
        Set<RouterId> forwarders = evidence.forwarders().getOrDefault(destination, Set.of());
        Set<RouterId> holders = evidence.routeHolders().getOrDefault(destination, Set.of());
        body.append(destination)
            .append(": ")
            .append(forwarders.size())
            .append(" routers forward to it, ")
            .append(holders.size())
            .append(" hold a BGP route");
        if (!forwarders.isEmpty() && forwarders.size() <= 12) {
          body.append("; forwarders ").append(routerNames(forwarders));
        }
        if (!holders.isEmpty() && holders.size() <= 12) {
          body.append("; BGP route holders ").append(routerNames(holders));
        }
        body.append('\n');
        for (Specification target : targets) {
          if (!target.predicate().destination().equals(destination)
              || !RouterEvidence.noRouteAtSource(evidence, target.predicate())
              || !describedBoundaries.add(
                  List.of(target.predicate().source(), target.predicate().destination()))) {
            continue;
          }
          Set<RouterEvidence.Link> frontier =
              routerEvidence.routeFrontier(target.predicate().source(), destination);
          if (!frontier.isEmpty()) {
            body.append("route-propagation boundary from ")
                .append(target.predicate().source().name())
                .append(": ")
                .append(frontier)
                .append('\n');
          }
        }
      }
      return new DecisionProblem.Context.Section("Verifier evidence", body.toString().strip());
    }

    private static String routerNames(Set<RouterId> routers) {
      return routers.stream().sorted().map(RouterId::name).toList().toString();
    }

    Optional<DecisionProblem.Context.Section> excerpts(Set<ProgramObject.Ref> objects) {
      if (detail >= LEAST_DETAIL) {
        return Optional.empty();
      }
      int limit = detail == 0 ? MAX_EXCERPT_LINES : MAX_EXCERPT_LINES / 4;
      StringBuilder body = new StringBuilder();
      for (ProgramObject.Ref ref : objects) {
        Optional<ProgramObject> object = program.object(ref);
        if (object.isEmpty()) {
          continue;
        }
        body.append(object(ref)).append(":\n");
        int lines = 0;
        Set<Integer> printed = new LinkedHashSet<>();
        for (SourceSpan span : object.get().statements()) {
          Statement statement = statementAt(span.router(), span.first());
          if (statement != null && statement.opener() > 0 && printed.add(-statement.opener())) {
            body.append("  ")
                .append(statement.opener())
                .append(": ")
                .append(state.snapshot().text(span.router()).line(statement.opener()).strip())
                .append('\n');
          }
          for (int line = span.first(); line <= span.last() && lines < limit; line++) {
            if (printed.add(line)) {
              body.append("  ")
                  .append(line)
                  .append(": ")
                  .append(state.snapshot().text(span.router()).line(line).strip())
                  .append('\n');
              lines++;
            }
          }
          if (lines >= limit) {
            body.append("  ...\n");
            break;
          }
        }
      }
      return body.isEmpty()
          ? Optional.empty()
          : Optional.of(
              new DecisionProblem.Context.Section(
                  "Configuration excerpts", body.toString().strip()));
    }

    Optional<DecisionProblem.Context.Section> relations(Set<ProgramObject.Ref> objects) {
      if (detail >= LEAST_DETAIL) {
        return Optional.empty();
      }
      StringBuilder body = new StringBuilder();
      for (RelationInstance instance : program.instances()) {
        if (instance.roles().values().stream().noneMatch(objects::contains)) {
          continue;
        }
        body.append(instance.relation()).append(": ");
        instance
            .roles()
            .forEach(
                (role, ref) -> body.append(role).append(" = ").append(object(ref)).append(", "));
        body.setLength(body.length() - 2);
        if (instance.satisfied()) {
          body.append(" [holds]");
        } else {
          body.append(" [violates: ");
          for (Requirement requirement : instance.violated()) {
            body.append(requirement.text()).append("; ");
          }
          body.setLength(body.length() - 2);
          body.append(']');
        }
        body.append('\n');
      }
      return body.isEmpty()
          ? Optional.empty()
          : Optional.of(
              new DecisionProblem.Context.Section("Program relations", body.toString().strip()));
    }

    Optional<DecisionProblem.Context.Section> feedback(List<TransitionLedger.Transition> tried) {
      if (tried.isEmpty()) {
        return Optional.empty();
      }
      StringBuilder body = new StringBuilder();
      int before = state.verification().verdict().unresolved().size();
      List<TransitionLedger.Transition> shown =
          tried.subList(Math.max(0, tried.size() - MAX_FEEDBACK), tried.size());
      for (TransitionLedger.Transition transition : shown) {
        Verdict after = transition.verification().verdict();
        body.append(
                detail == 0
                    ? edits(transition.hypothesis())
                    : transition.hypothesis().id().abbreviated())
            .append(": ")
            .append(change(transition.change()))
            .append(" (violated ")
            .append(before)
            .append(" -> ")
            .append(after.unresolved().size())
            .append(", side effects ")
            .append(after.sideEffects().size())
            .append(")\n");
      }
      if (tried.size() > shown.size()) {
        body.append("and ")
            .append(tried.size() - shown.size())
            .append(" earlier attempts failed\n");
      }
      return Optional.of(
          new DecisionProblem.Context.Section(
              "Candidates already verified in this state", body.toString().strip()));
    }

    String region(Region region) {
      StringBuilder text = new StringBuilder();
      switch (region) {
        case SiteRegion site -> {
          text.append("edit ").append(object(site.site()));
          program
              .object(site.site())
              .ifPresent(
                  object -> {
                    List<String> lines = new ArrayList<>();
                    for (SourceSpan span : object.statements()) {
                      if (lines.size() == SHOWN_STATEMENTS) {
                        lines.add("...");
                        break;
                      }
                      lines.add(state.snapshot().text(span.router()).line(span.first()).strip());
                    }
                    if (!lines.isEmpty()) {
                      text.append(" (").append(String.join("; ", lines)).append(')');
                    }
                  });
        }
        case RelationRegion relation -> {
          text.append(
                  switch (relation.goal()) {
                    case RESTORE -> "complete ";
                    case ACTIVATE -> "activate ";
                    case REVISE -> "revise ";
                    case SUPPRESS -> "suppress ";
                  })
              .append(relation.instance().relation())
              .append(" (");
          relation
              .instance()
              .roles()
              .forEach(
                  (role, ref) -> text.append(role).append(" = ").append(object(ref)).append(", "));
          text.setLength(text.length() - 2);
          if (relation.goal() == RelationRegion.Goal.SUPPRESS) {
            text.append(") traversed as the first hop of a formal isolation counterexample");
          } else if (relation.goal() == RelationRegion.Goal.ACTIVATE) {
            text.append(
                ") whose physical endpoints both omit an adjacency for an IGP their routers "
                    + "already run");
          } else if (relation.goal() == RelationRegion.Goal.REVISE) {
            text.append(") whose jointly agreed value is unusual among peer relations");
          } else {
            text.append(") which violates: ");
            for (Requirement requirement : relation.instance().violated()) {
              if (requirement.signals()) {
                text.append(requirement.text()).append("; ");
              }
            }
            text.setLength(text.length() - 2);
          }
        }
        case IncidenceRegion incidence ->
            text.append("complete the repeated BGP session incidence set for explicit reflector ")
                .append(object(incidence.incidence().reflector()))
                .append(" across ")
                .append(incidence.incidence().clients().size())
                .append(" implicated same-AS clients; the set is inferred from cluster identity, ")
                .append("loopback ownership, and observed directional peer-group roles");
        case MeshRegion mesh ->
            text.append("complete the direct iBGP mesh incidence for processless router ")
                .append(object(mesh.mesh().member()))
                .append(" across ")
                .append(mesh.mesh().peers().size())
                .append(" implicated same-AS BGP speakers; the set is inferred from formal ")
                .append("no-route path evidence, AS membership, and loopback ownership");
        case TransferRegion transfer -> {
          text.append("repair the verifier-observed route transfer through ")
              .append(object(transfer.transfer().policy()));
          if (transfer.transfer().attachment().isPresent()) {
            var attachment = transfer.transfer().attachment().orElseThrow();
            text.append(" by attaching it ")
                .append(attachment.direction())
                .append(" on the exact forbidden-path edge ")
                .append(object(attachment.neighbor()));
          } else {
            text.append(" at a BGP/OSPF propagation boundary; ")
                .append(transfer.transfer().blockers().size())
                .append(" preceding deny clauses can intercept the affected routes");
          }
        }
      }
      if (region.hypotheses() instanceof Region.Hypotheses.Admitted admitted) {
        text.append("; ").append(admitted.bound()).append(" candidates");
        if (admitted.bound() <= EXACT_EDITS && detail == 0) {
          text.append(": ");
          for (Hypothesis hypothesis : admitted.list()) {
            text.append('[').append(edits(hypothesis)).append("] ");
          }
          text.setLength(text.length() - 1);
        }
      } else {
        text.append("; up to ").append(region.bound()).append(" candidates");
      }
      return text.toString();
    }

    String hypothesis(Hypothesis hypothesis) {
      StringBuilder text = new StringBuilder(edits(hypothesis));
      List<Provenance.Restored> restored = hypothesis.restores();
      if (!restored.isEmpty()) {
        text.append("; restores ");
        for (Provenance.Restored item : restored) {
          text.append(item.relation()).append(' ').append(item.requirement().text()).append(", ");
        }
        text.setLength(text.length() - 2);
      }
      if (hypothesis.support() == ValueSupport.INVENTED) {
        text.append("; uses a value not seen in the network");
      }
      return text.toString();
    }

    /** Returns the objects a region edits, plus those they are related to. */
    Set<ProgramObject.Ref> objectsOf(Region region) {
      Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
      switch (region) {
        case SiteRegion site -> objects.add(site.site());
        case RelationRegion relation -> objects.addAll(relation.instance().roles().values());
        case IncidenceRegion incidence -> objects.addAll(incidence.incidence().objects());
        case MeshRegion mesh -> objects.addAll(mesh.mesh().objects());
        case TransferRegion transfer -> {
          objects.add(transfer.transfer().policy());
          objects.addAll(transfer.transfer().blockers());
          transfer.transfer().attachment().ifPresent(item -> objects.add(item.neighbor()));
        }
      }
      Set<ProgramObject.Ref> own = Set.copyOf(objects);
      for (RelationInstance instance : program.instances()) {
        if (instance.roles().values().stream().anyMatch(own::contains)) {
          objects.addAll(instance.roles().values());
        }
      }
      return objects;
    }

    private String edits(Hypothesis hypothesis) {
      StringBuilder text = new StringBuilder();
      for (LineEdit edit : hypothesis.transaction().edits()) {
        String router = edit.router().name();
        switch (edit) {
          case LineEdit.Delete delete ->
              text.append("on ").append(router).append(" delete ").append(lines(delete.span()));
          case LineEdit.Replace replace ->
              text.append("on ")
                  .append(router)
                  .append(" replace ")
                  .append(lines(replace.span()))
                  .append(" with ")
                  .append(quoted(replace.lines()));
          case LineEdit.InsertAfter insert ->
              text.append("on ")
                  .append(router)
                  .append(" insert ")
                  .append(quoted(insert.lines()))
                  .append(" after line ")
                  .append(insert.line())
                  .append(
                      insert.line() == 0
                          ? ""
                          : " "
                              + quoted(
                                  List.of(
                                      state.snapshot().text(edit.router()).line(insert.line()))));
          case LineEdit.Append append ->
              text.append("on ").append(router).append(" append ").append(quoted(append.lines()));
        }
        text.append("; ");
      }
      text.setLength(text.length() - 2);
      return text.toString();
    }

    private String lines(SourceSpan span) {
      List<String> lines = new ArrayList<>();
      for (int line = span.first(); line <= span.last(); line++) {
        lines.add(state.snapshot().text(span.router()).line(line));
      }
      return quoted(lines);
    }

    private static String quoted(List<String> lines) {
      StringBuilder text = new StringBuilder();
      for (String line : lines) {
        text.append('`').append(line.strip()).append("` ");
      }
      return text.toString().strip();
    }

    private Statement statementAt(RouterId router, int line) {
      return statementsByLine
          .computeIfAbsent(
              router,
              r -> {
                Map<Integer, Statement> byLine = new HashMap<>();
                program.statements(r).forEach(s -> byLine.put(s.span().first(), s));
                return byLine;
              })
          .get(line);
    }

    private static String object(ProgramObject.Ref ref) {
      return ref.key().isEmpty()
          ? "router " + ref.router().name()
          : ref.type() + " " + String.join(" ", ref.key()) + " on " + ref.router().name();
    }

    private static String predicate(ForwardingPredicate predicate) {
      return switch (predicate) {
        case ForwardingPredicate.Reachability r ->
            "reachability: " + r.source().name() + " reaches " + r.destination();
        case ForwardingPredicate.Isolation i ->
            "isolation: " + i.source().name() + " must not reach " + i.destination();
        case ForwardingPredicate.Waypointing w ->
            "waypointing: "
                + w.source().name()
                + " reaches "
                + w.destination()
                + " via "
                + w.waypoint().name();
        case ForwardingPredicate.LoadBalancing l ->
            "load balancing: "
                + l.source().name()
                + " reaches "
                + l.destination()
                + " over "
                + l.routes()
                + " paths";
      };
    }

    private static String trace(DataPlaneEvidence.Trace trace) {
      StringBuilder text = new StringBuilder();
      for (RouterId hop : trace.hops()) {
        text.append(hop.name()).append(" > ");
      }
      text.setLength(text.length() - 3);
      return text
          + " ("
          + trace.disposition().name().toLowerCase(Locale.ROOT).replace('_', ' ')
          + ")";
    }

    private static String change(Verdict.Change change) {
      return switch (change) {
        case IMPROVEMENT -> "improved";
        case MIXED -> "fixed some but broke others";
        case REGRESSION -> "broke specifications";
        case NO_EFFECT -> "no effect";
      };
    }
  }
}
