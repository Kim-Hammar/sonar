package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.BgpMesh;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.text.LineEdit;

/** Materialises one verifier-required iBGP mesh closure through the typed IOS grammar. */
public final class BgpMeshGenerator {
  private final SeedGenerator seeds;

  /** Creates a generator from the shared typed seed generator. */
  public BgpMeshGenerator(SeedGenerator seeds) {
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
  }

  /** Returns the atomic direct-session closure, if every endpoint can be materialised. */
  public List<Hypothesis> generate(ProgramModel program, BgpMesh mesh) {
    ProgramObject member = program.object(mesh.member()).orElse(null);
    if (member == null) {
      return List.of();
    }
    String identity = member.value("loopback-address").orElse("");
    if (identity.isEmpty()) {
      return List.of();
    }
    Provenance provenance = new Provenance.Inferred("formal waypoint iBGP mesh incidence");
    List<EditOperator> operators = new ArrayList<>();
    List<EditOperator.RenderedStatement> process = new ArrayList<>();
    process.add(line(program, "router-bgp", "", Map.of("asn", List.of(mesh.asn()))));
    process.add(line(program, "bgp-router-id", " ", Map.of("id", List.of(identity))));
    process.add(line(program, "bgp-log-neighbor-changes", " ", Map.of()));
    process.add(line(program, "no-bgp-default-ipv4", " ", Map.of()));
    for (ProgramObject.Ref peerRef : mesh.peers()) {
      ProgramObject peer = program.object(peerRef).orElse(null);
      String peerAddress = peer == null ? "" : peer.value("loopback-address").orElse("");
      if (peerAddress.isEmpty()) {
        return List.of();
      }
      process.add(
          line(
              program,
              "neighbor-remote-as",
              " ",
              Map.of("peer", List.of(peerAddress), "asn", List.of(mesh.asn()))));
      process.add(
          line(
              program,
              "neighbor-update-source",
              " ",
              Map.of("peer", List.of(peerAddress), "interface", List.of("Loopback0"))));
      Optional<List<EditOperator>> reciprocal =
          existingPeer(program, peerRef, identity, mesh.asn(), provenance);
      if (reciprocal.isEmpty()) {
        return List.of();
      }
      operators.addAll(reciprocal.orElseThrow());
    }
    process.add(line(program, "address-family", " ", Map.of()));
    for (ProgramObject.Ref peerRef : mesh.peers()) {
      String address =
          program.object(peerRef).flatMap(peer -> peer.value("loopback-address")).orElseThrow();
      process.add(line(program, "neighbor-activate", "  ", Map.of("peer", List.of(address))));
    }
    process.add(line(program, "exit-address-family", " ", Map.of()));
    operators.addFirst(
        new EditOperator.InsertBlock(
            member.ref().router(),
            program.snapshot().text(member.ref().router()).lineCount(),
            process,
            provenance));
    List<LineEdit> edits = operators.stream().map(EditOperator::lower).toList();
    try {
      return List.of(new Hypothesis(Transaction.of(program.snapshot(), edits), operators));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return List.of();
    }
  }

  private Optional<List<EditOperator>> existingPeer(
      ProgramModel program,
      ProgramObject.Ref peer,
      String memberAddress,
      String asn,
      Provenance provenance) {
    List<EditOperator> anchor =
        seeds.insert(
            program,
            peer,
            "neighbor-remote-as",
            Map.of("peer", List.of(memberAddress), "asn", List.of(asn)),
            provenance);
    EditOperator.InsertStatement insertion =
        anchor.stream()
            .filter(EditOperator.InsertStatement.class::isInstance)
            .map(EditOperator.InsertStatement.class::cast)
            .findFirst()
            .orElse(null);
    if (insertion == null) {
      return Optional.empty();
    }
    List<EditOperator> operators = new ArrayList<>();
    operators.add(
        new EditOperator.InsertBlock(
            peer.router(),
            insertion.after(),
            List.of(
                line(
                    program,
                    "neighbor-remote-as",
                    insertion.indentation(),
                    Map.of("peer", List.of(memberAddress), "asn", List.of(asn))),
                line(
                    program,
                    "neighbor-update-source",
                    insertion.indentation(),
                    Map.of(
                        "peer", List.of(memberAddress),
                        "interface", List.of("Loopback0")))),
            provenance));
    List<EditOperator> activation =
        seeds.insert(
            program, peer, "neighbor-activate", Map.of("peer", List.of(memberAddress)), provenance);
    if (activation.isEmpty()) {
      return Optional.empty();
    }
    operators.addAll(activation);
    return Optional.of(List.copyOf(operators));
  }

  private static EditOperator.RenderedStatement line(
      ProgramModel program,
      String production,
      String indentation,
      Map<String, List<String>> values) {
    return new EditOperator.RenderedStatement(
        indentation, program.schema().grammar().production(production).orElseThrow(), values);
  }
}
