package se.kth.sonar.batfish;

import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;

/**
 * Derives program attributes from Batfish's vendor-independent model: the router that owns an
 * address, interface subnets, effective OSPF areas, link peers from the physical topology,
 * effective BGP remote AS numbers, and the prefixes of network statements.
 */
@Placeholder
public final class BatfishProgramFactory implements ProgramModel.Factory {
  private static final Set<String> FUNCTIONS =
      Set.of(
          "address-owner",
          "effective-remote-as",
          "link-peer",
          "network-prefix",
          "ospf-area",
          "subnet");

  @Override
  public Set<String> functions() {
    return FUNCTIONS;
  }

  @Override
  public ProgramModel.RouterFacts parse(RouterId router, ConfigText text) {
    throw new NotImplemented("BatfishProgramFactory.parse");
  }

  @Override
  public Map<ProgramObject.Ref, Map<String, List<String>>> derive(
      Topology topology,
      Map<RouterId, ProgramModel.RouterFacts> facts,
      List<ProgramObject> objects) {
    throw new NotImplemented("BatfishProgramFactory.derive");
  }
}
