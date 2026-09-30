import Disclosure from '../../Common/Disclosure/Disclosure'
import './SearchExplanation.css'

/**
 * A short account of how the search space is built from the configurations and the
 * verification, introducing every term the tab uses where it first matters.
 */
export default function SearchExplanation() {
  return (
    <Disclosure title="How the search space is built">
      <p className="SearchExplanation-lead">
        Sonar does not guess edits at random. It reads the configurations the way a compiler reads a
        program, checks them against rules of what a well-formed network looks like, and turns every
        broken rule and every suspect object into a place to search. The terms in bold are the ones
        this tab shows.
      </p>
      <ol className="SearchExplanation">
        <li>
          <strong>Verify.</strong> Batfish computes how the faulty network forwards traffic and
          which specifications it violates. For every violated flow, Sonar notes the routers on its
          path and the routers that own its destination: the fault lies among them. The violated
          specifications a place in the network can answer for are its{' '}
          <strong>target specifications</strong>.
        </li>
        <li>
          <strong>Read the configurations.</strong> A configuration file is plain text. Sonar comes
          with a description of every kind of statement a Cisco IOS configuration can contain, and
          uses it to turn each file into <strong>objects</strong> with named properties: an
          interface and its address, a BGP neighbour and its remote AS, an OSPF process and its
          networks, a route map and its entries. Some properties are worked out rather than read,
          such as which router an address belongs to. An object is written as its kind, its router
          and its name, for example <span className="mono">interface:r1:GigabitEthernet0/0</span>.
        </li>
        <li>
          <strong>Check that the objects fit together.</strong> Sonar also comes with a file of{' '}
          <strong>relations</strong>. A relation names the objects it connects, the{' '}
          <strong>roles</strong> they play, and the <strong>rules</strong> that must hold between
          them. The relation <span className="mono">bgp-session</span> connects a neighbour
          statement in the role <span className="mono">local</span> with the matching statement on
          the other router in the role <span className="mono">remote</span>, and its rule says that
          the AS each names must be the other router&apos;s AS. The relation{' '}
          <span className="mono">link</span> connects two interfaces,{' '}
          <span className="mono">a</span> and <span className="mono">b</span>, and its rule says
          their addresses share a subnet. Every relation is evaluated over every group of objects
          that can play its roles; where a rule fails, the relation is <strong>violated</strong>,
          and that is a structural fault. A rule&apos;s <strong>strength</strong> says how it binds:
          a <strong>hard</strong> rule must hold in the repaired network; a <strong>soft</strong>{' '}
          rule points at a fault when broken but may stay broken; a <strong>convention</strong> is
          only kept from breaking.
        </li>
        <li>
          <strong>Form regions.</strong> A <strong>region</strong> is one place where the repair may
          lie, and its <strong>kind</strong> says which. A <strong>relation</strong> region is one
          violated relation, and its repairs make the rule hold. A <strong>site</strong> region is
          one object on a router noted in step 1, and its repairs edit that object. An{' '}
          <strong>incidence</strong> region is the set of BGP sessions around a route reflector. A{' '}
          <strong>transfer</strong> region is a policy that carries routes from one protocol into
          another. A region is named after what it holds, such as{' '}
          <span className="mono">relation:bgp-session:f803457862be</span> or{' '}
          <span className="mono">site:interface:r1:Loopback0</span>, and lists the{' '}
          <strong>routers</strong> its repairs may change. Regions that aim at the same violated
          specifications form a <strong>partition</strong>; the agent compares the regions of a
          partition when it asks the decision model which one holds the fault.
        </li>
        <li>
          <strong>Weigh the regions.</strong> Each region gets a <strong>prior</strong>: the
          probability, before any repair is verified, that it holds the repair. Every{' '}
          <strong>factor</strong> in the table gives the region a <strong>weight</strong> read from
          the verification or the rules, such as how often its routers lie on failing rather than
          passing flows, whether the rule it would restore holds everywhere else in the network, and
          whether one of its routers dropped violated traffic. The product of the weights, scaled so
          that all regions add up to one, is the prior.
        </li>
        <li>
          <strong>Generate candidates.</strong> A <strong>candidate</strong> is one repair: a set of
          line edits on one or more routers. A region&apos;s candidates are generated only when you
          ask for them, or when the agent expands the region; until then the region is{' '}
          <strong>deferred</strong>, and the table shows only how many candidates it can hold at
          most. For a violated relation, Sonar computes the edits that make its rule hold, such as
          changing a remote AS to the right value. For an object, it tries deleting, changing and
          inserting statements of that object. Each edit is then extended until it breaks no hard
          rule, with values taken from the configurations, from what the verifier observed, or made
          up when nothing fits. Candidates that do not parse or that leave a hard rule broken are
          discarded; those that pass are <strong>admitted</strong>, and a region where none passes
          is <strong>dropped</strong>. A candidate shows how many lines it changes, its{' '}
          <strong>support</strong>, that is where its values come from (<strong>evidenced</strong>{' '}
          by what the verifier observed, <strong>observed</strong> in the configurations, or{' '}
          <strong>invented</strong>), and the rules it <strong>restores</strong>, that is makes hold
          again.
        </li>
      </ol>
      <p className="SearchExplanation-note">
        The agent then verifies candidates with Batfish, region by region in the order of the prior,
        and asks the decision model where the prior leaves it unsure.
      </p>
    </Disclosure>
  )
}
