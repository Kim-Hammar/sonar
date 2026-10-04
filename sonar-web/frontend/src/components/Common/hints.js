import { EVENT_LABELS, STOP_LABELS } from './format'

const DURATION_ONLY = "Only the execution's duration depends on it, unless a time limit is set."

const EVENT_MEANINGS = {
  loaded: "The scenario's configurations, topology and specifications were read.",
  baseline: 'The faulty network was verified, which shows the specifications it violates.',
  'space-built':
    'Candidate repairs were grouped into regions, one for each place in the network they change.',
  decided:
    'The decision model was asked which region holds the repair, or in which order to try the candidates of a region.',
  tried: 'A candidate repair was applied and the changed network verified.',
  adopted:
    'Candidates that fixed specifications and broke none were kept; the search continues from the changed network.',
  finished: 'The scenario stopped, solved or not.',
  failed: 'An error ended the scenario.'
}

const STOP_MEANINGS = {
  solved: 'Every specification holds as required.',
  'budget-exhausted': 'One of the per-scenario limits was reached.',
  'time-expired': 'The per-scenario time limit passed.',
  cancelled: 'The agent execution was cancelled.',
  'search-exhausted': 'No candidate repair was left to try.',
  failed: 'An error ended the scenario.'
}

const STOPS = Object.entries(STOP_LABELS).map(([stop, label]) => [label, STOP_MEANINGS[stop]])

/**
 * What each field of the interface means, shown by the info icon beside it. Entries hold a short
 * `text`, a list of `items` that pair a term with its meaning, or both.
 */
export const HINTS = {
  settings: {
    name: {
      text: 'A label for the agent execution in the list of executions. Without one, the execution is listed under its identifier, which records when it was created.'
    },
    scenarios: {
      text: 'The benchmark scenarios to repair. Each is repaired on its own, with its own budget.'
    },
    specifications: {
      text: "The share of each scenario's specifications that the agent checks. The benchmark provides samples of 2 to 100 per cent; a larger sample describes the intended forwarding more completely."
    },
    verifications: {
      text: 'The most verifications one scenario may use. A verification computes how a candidate network forwards traffic and checks every specification. Leave empty for no limit.'
    },
    decisions: {
      text: 'The most questions one scenario may ask the decision model, such as where in the network the fault most likely is. A question counts once, however often it is retried. Leave empty for no limit.'
    },
    input_tokens: {
      text: 'The most tokens one scenario may send to the decision model, over all its questions. Leave empty for no limit.'
    },
    minutes: {
      text: 'The most time one scenario may take. When it runs out, the scenario stops and keeps the repairs adopted so far. Leave empty for no limit.'
    },
    verificationBatch: {
      text: 'How many candidate repairs are verified at a time before the search decides what to adopt. A larger batch finds a repair in fewer steps but spends more verifications on candidates that turn out not to be needed. Unlike the other advanced settings, it changes what the search does and what it finds.'
    },
    verificationWorkers: {
      text: `How many candidate networks are verified at the same time; each needs memory of its own. ${DURATION_ONLY}`
    },
    generationParallelism: {
      text: `How many candidate repairs are generated at the same time, before any is verified. ${DURATION_ONLY}`
    },
    seed: {
      text: 'The seed of every random choice the search makes. Executions with the same seed, scenarios and settings make the same random choices.'
    },
    decisionModel: {
      text: "The language model that answers the search's questions, and the address it is reached at. Both are set when the server starts."
    }
  },
  executions: {
    name: {
      text: 'The name given when the agent execution was started, or its identifier.'
    },
    status: {
      items: [
        ['Queued', 'Waiting for the executions before it; one executes at a time.'],
        ['Running', 'Repairing its scenarios now.'],
        ['Finished', 'Every scenario ended; open the execution to see which were solved.'],
        ['Failed', 'An error outside any single scenario stopped the execution.'],
        ['Cancelled', 'The execution was cancelled.'],
        ['Interrupted', 'The server stopped while the execution was queued or running.']
      ]
    },
    scenarios: { text: 'How many scenarios the agent repairs in this execution.' },
    created: { text: 'When the execution was submitted.' },
    duration: {
      text: "Time from the execution's start to its end, or until now while it is running. Time spent waiting in the queue is not counted."
    }
  },
  kpi: {
    scenarios: {
      text: 'Scenarios that have ended, of all the scenarios in the execution, and how many of them were solved. A scenario is solved when every specification holds as required.'
    },
    remaining: {
      text: 'Specifications that were violated at the start and still are, summed over the scenarios. Below it is how many were violated at the start.'
    },
    verifications: {
      text: 'Verifications so far, summed over the scenarios. Each computes how a candidate network forwards traffic and checks every specification.'
    },
    decisions: {
      text: 'Questions asked of the decision model so far, summed over the scenarios. Provider calls count every request sent to the model, retries included.'
    },
    tokens: {
      text: 'Tokens exchanged with the decision model so far. Input tokens are sent with the questions and output tokens come back with the answers.'
    },
    elapsed: {
      text: 'Time since the execution started, or how long it took once it ended. Time spent waiting in the queue is not counted.'
    }
  },
  charts: {
    progress: {
      text: "Running totals over the time since the execution started. With all scenarios selected, each point adds up every scenario's latest values; choose one scenario to see only its own."
    },
    remaining: { text: 'Specifications violated at the start that are not repaired yet.' },
    tokens: { text: 'Tokens sent to the decision model and received from it.' },
    decisions: {
      text: 'Questions asked of the decision model and requests sent to it. Retries add requests but not questions.'
    },
    verifications: { text: 'Verifications of candidate networks against the specifications.' }
  },
  scenarioTable: {
    scenario: {
      text: 'The benchmark scenario. Select a row to see its specifications, topology and configurations.'
    },
    status: {
      items: [['Pending', 'Not started yet.'], ['Running', 'Being repaired now.'], ...STOPS]
    },
    remaining: {
      text: 'Specifications violated at the start that are not repaired yet, of all that were violated at the start.'
    },
    adopted: { text: 'Candidate repairs kept because they fixed specifications and broke none.' },
    candidates: { text: 'Candidate repairs verified so far.' },
    verifications: { text: 'Verifications this scenario has used.' },
    decisions: { text: 'Questions this scenario has asked the decision model.' },
    tokens: { text: 'Tokens this scenario has sent to the decision model and received from it.' },
    note: { text: 'Why the scenario failed, if it did.' }
  },
  activity: {
    types: {
      text: 'The kinds of events, in the order a scenario usually reports them.',
      items: Object.entries(EVENT_LABELS).map(([type, label]) => [label, EVENT_MEANINGS[type]])
    }
  },
  scenarios: {
    list: {
      text: 'The benchmark scenarios. Each is a network whose configurations contain faults, with its topology and specifications.'
    },
    specificationFile: {
      text: "Which of the scenario's specification files to show. The benchmark samples each scenario's specifications into files of 2 to 100 per cent."
    },
    topology: {
      colours:
        'Routers are coloured by BGP autonomous system (AS); hover over one to see its name.',
      changed: 'A ring marks a router changed by adopted repairs.',
      navigation: 'Scroll to zoom and drag to pan.',
      selection: 'Click a router to select it.'
    }
  },
  batfish: {
    run: {
      text: "Runs Batfish on the scenario's faulty configurations: it computes how every router forwards traffic and checks each specification of the chosen file against that."
    },
    status: {
      items: [
        ['Queued', 'Waiting for an earlier verification to finish.'],
        ['Running', 'Batfish is computing how the network forwards traffic.'],
        ['Finished', 'The results are shown below.'],
        ['Failed', 'Batfish could not analyse the configurations; the message says why.']
      ]
    },
    routers: { text: 'Routers whose configurations Batfish analysed.' },
    forwardingRows: {
      text: 'Rows of the forwarding table: one for each route of each router, with what happens to traffic sent along it.'
    },
    violated: {
      text: 'Specifications the faulty network violates, of all the specifications in the chosen file. These are what a repair must fix.'
    },
    holding: { text: 'Specifications the faulty network already meets.' },
    mismatches: {
      text: 'Specifications whose label disagrees with what Batfish found, which should be none: one labelled broken that already holds counts as fixed, and one labelled intact that is violated counts as a side effect.'
    },
    elapsed: { text: 'How long Batfish took, or has taken so far.' },
    views: {
      items: [
        [
          'Specifications',
          'The state of every specification; select one to see the paths its traffic takes.'
        ],
        [
          'Forwarding table',
          "Batfish's output: what each router does with traffic to each prefix."
        ],
        [
          'BGP routes',
          'For each specified destination, the routers that hold a BGP route to it and the routers that forward traffic to it.'
        ]
      ]
    },
    traces: {
      text: 'The paths the traffic of the selected specification takes through the network, router by router, and how each ends.'
    },
    routeHolders: {
      text: "Routers whose BGP table holds a route to the specification's destination."
    },
    forwarders: {
      text: "Routers that have a forwarding entry for the specification's destination."
    },
    forwarding: {
      router: { text: 'The router whose routing table holds the route.' },
      prefix: { text: 'The destination prefix of the route.' },
      nextHop: { text: 'The address the router sends the traffic to, if the route names one.' },
      interface: {
        text: 'The interface the traffic leaves through, after resolving the next hop through the routing table.'
      },
      action: {
        text: 'What happens to traffic sent along the route.',
        items: [
          ['forwarded', 'Sent on to the next hop.'],
          ['accepted', 'Delivered to the router itself, such as to a loopback address.'],
          ['delivered to subnet', 'Delivered to a host on a directly connected subnet.'],
          ['no route', 'No route covers the traffic.'],
          ['null routed', 'Dropped by a route to a null interface.'],
          ['neighbor unreachable', 'The next hop cannot be reached from the interface.'],
          ['insufficient info', 'Batfish cannot tell where the traffic ends up.'],
          ['forwarded blocked outgoing filter', "Dropped by the interface's outbound access list."],
          [
            'forwarded blocked incoming filter',
            "Dropped by the inbound access list of every neighbour's interface; the routers that block it are listed."
          ]
        ]
      },
      receivers: { text: 'The neighbours whose inbound access list drops the traffic.' }
    },
    routes: {
      prefix: { text: 'A destination prefix that some specification names.' },
      holders: { text: 'Routers whose BGP table holds a route to the prefix.' },
      forwarders: { text: 'Routers that have a forwarding entry for the prefix.' }
    }
  },
  search: {
    start: {
      text: 'Builds the search space the agent starts from for this scenario and specification file: the regions of candidate repairs and how likely each is to hold the repair, from the verification of the faulty network. Candidates are generated only for the regions you ask about.'
    },
    status: {
      items: [
        ['Queued', 'Waiting for an earlier search space to finish.'],
        ['Verifying', 'Batfish is verifying the faulty network first.'],
        ['Building', 'The regions and the prior are being computed.'],
        ['Finished', 'The search space is shown below.'],
        ['Failed', 'The search space could not be built; the message says why.']
      ]
    },
    regions: {
      text: 'Regions of the search space. A region holds the candidate repairs at one place in the network, and the search judges them together.'
    },
    partitions: {
      text: 'Groups of regions that aim at the same violated specifications; the agent compares the regions of a group when it asks the decision model.'
    },
    candidates: {
      text: 'The most candidate repairs the regions can hold in all, and how many have been generated so far.'
    },
    violated: { text: 'Specifications the faulty network violates, which the repairs must fix.' },
    elapsed: { text: 'How long building the search space took, after the verification.' },
    region: { text: 'The identifier the agent uses for the region.' },
    kind: {
      items: [
        [
          'site',
          'Candidates that edit one configuration object, such as one interface or one BGP neighbor.'
        ],
        [
          'relation',
          'Candidates that complete one broken relation, possibly on several routers, such as both ends of a BGP session.'
        ],
        [
          'incidence',
          'Candidates that complete the BGP sessions of a route reflector and its clients.'
        ],
        ['transfer', 'Candidates that change a policy that moves routes between routing protocols.']
      ]
    },
    routers: { text: "The routers the region's candidates may change." },
    targets: { text: 'The violated specifications the region aims at.' },
    bound: {
      text: 'How many candidate repairs the region holds once they are generated, or at most holds until then; while they are generated, or when none passed the checks, the state shows instead.'
    },
    partition: { text: 'The group of regions the region is compared within.' },
    prior: {
      text: 'The probability, before any candidate is verified, that the region holds the repair. It is the product of the factor weights, divided by the sum over all regions.'
    },
    weight: { text: 'The product of the factor weights of the region.' },
    factors: {
      text: 'Each factor of the prior multiplies the weight of a region.',
      items: [
        ['spectrum', "How often the region's routers lie on failing rather than passing flows."],
        [
          'relation',
          'How strongly the requirement the region restores holds elsewhere in the network.'
        ],
        [
          'counterexample',
          'Whether the region lies on a router pair named by an isolation, waypoint or no-route counterexample.'
        ],
        [
          'policy-cut',
          'Whether the region is an outbound policy that stops routes the verifier expected to pass.'
        ],
        ['frontier', 'Whether the region spans a link where routes stop propagating.'],
        ['drop', 'Whether a router of the region dropped violated traffic.'],
        [
          'experience',
          'What earlier candidates of the same kind did in this execution; neutral before any is verified.'
        ]
      ]
    },
    expansion: {
      text: 'Whether the candidate repairs of the region have been generated.',
      items: [
        ['Deferred', 'Not generated yet; only their number is bounded.'],
        ['Queued', 'Waiting to be generated.'],
        ['Running', 'Being generated now.'],
        ['Admitted', 'Generated and checked; the candidates are listed in the region.'],
        ['Dropped', 'No candidate passed the checks, so the agent drops the region.'],
        ['Failed', 'Generation failed; the message says why.']
      ]
    },
    specifications: { text: 'The violated specifications the region aims at.' },
    site: {
      text: 'The configuration object the candidates edit, with its attributes and the lines that define it.'
    },
    relation: {
      text: 'The broken relation the candidates complete: its roles and the requirements it violates.'
    },
    incidence: {
      text: 'The route reflector, its clients and the sessions between them that the candidates complete.'
    },
    transfer: {
      text: 'The policy that moves routes between protocols and the objects that block the routes.'
    },
    strength: {
      text: 'How strictly the rule binds.',
      items: [
        [
          'hard',
          'A rule every repaired configuration must satisfy. Candidates are extended until they break none, and a candidate that would leave one broken is discarded before verification.'
        ],
        [
          'soft',
          'A rule whose violation signals a fault: it gets a region and raises the prior, but a repair may leave it violated.'
        ],
        [
          'convention',
          'A rule kept when an edit would newly break it, with no fault signal and no region.'
        ]
      ]
    },
    generate: {
      text: 'Generates the candidate repairs of the region, exactly as the agent would when it expands it: seeds, their closure under the rules, and the checks that discard candidates that cannot be repairs.'
    },
    candidate: { text: 'One candidate repair, by the short form of its identifier.' },
    cost: { text: 'The number of configuration lines the candidate changes.' },
    support: {
      items: [
        [
          'evidenced',
          'Every value in the edits comes from the verifier’s evidence or a related object.'
        ],
        ['observed', 'Every value in the edits occurs in the configurations.'],
        ['invented', 'Some value, such as a new name, was made up to complete the edits.']
      ]
    },
    restores: {
      text: 'The requirements the candidate makes hold again, with their relation and how strictly each binds.'
    },
    edits: { text: 'The configuration lines removed and added, router by router.' }
  },
  specifications: {
    type: {
      items: [
        ['Reachability', 'Traffic from the source reaches the destination.'],
        ['Isolation', 'Traffic from the source does not reach the destination.'],
        ['Waypointing', 'Traffic from the source to the destination passes through the waypoint.'],
        [
          'Load balancing',
          'Traffic from the source to the destination is spread over the given number of equal-cost paths.'
        ]
      ]
    },
    source: { text: 'The router the traffic starts at.' },
    destination: { text: 'The prefix the traffic is addressed to.' },
    extra: {
      text: 'For waypointing, the router the traffic must pass through. For load balancing, the number of equal-cost paths it must be spread over.'
    },
    label: {
      text: "The benchmark's label for the specification.",
      items: [
        ['intact', 'The faulty network already behaves as the specification requires.'],
        ['broken_removed', 'The faults removed this behaviour; a repair must restore it.'],
        ['broken_added', 'The faults added this behaviour; a repair must remove it again.'],
        ['Combined labels', 'Such as broken_removed|intact, count as broken.']
      ]
    },
    initialState: {
      text: 'Whether the faulty network meets the specification.',
      items: [
        ['Violated', 'It does not behave as the specification requires.'],
        ['Intact', 'It behaves as required.'],
        ['Unknown', 'The label does not say.']
      ]
    },
    runState: {
      text: 'The state after the repairs adopted so far.',
      items: [
        ['Unresolved', 'Violated at the start and still violated.'],
        ['Fixed', 'Violated at the start and now holds as required.'],
        ['Side effect', 'Held at the start and is violated now.'],
        ['Holding', 'Held at the start and still holds.']
      ]
    }
  },
  inspector: {
    answer: {
      text: 'What the model answered. For a region question, how likely each region is to hold the repair; for an ordering question, the order in which to try the candidates.'
    },
    region: { text: 'A region the question offered the model.' },
    probability: {
      text: 'For a region, the probability that it holds the repair, given that one of the offered regions does. For none of these, the probability that none of them does.'
    },
    candidate: { text: 'A candidate edit the question offered the model, by its identifier.' },
    rankProbability: {
      text: 'The probability that the candidate is the repair, given that one of the offered candidates is.'
    },
    options: {
      text: 'The choices the question offered the model, each described as the model saw it.'
    },
    context: { text: 'The facts sent to the model with the question, one section each.' },
    exchanges: {
      text: 'Each request sent to the decision model for this question, with its reply, retries included. A cached reply repeats the answer to an identical earlier request, without a provider call.'
    },
    effect: {
      items: [
        ['Improvement', 'Fixed some specifications and broke none.'],
        ['Mixed', 'Fixed some specifications and broke others.'],
        ['Regression', 'Fixed none and broke some.'],
        ['No effect', "Changed no specification's state."]
      ]
    },
    support: {
      items: [
        [
          'Observed values',
          'Every value in the edits occurs in the configurations or in what the verifier observed.'
        ],
        ['Invented values', 'Some value, such as a new name, was made up to complete the edits.']
      ]
    },
    changed: {
      text: 'The specifications whose state this candidate changes, compared with the network before it.'
    },
    specification: {
      text: 'The specification, written as type|source|destination, followed by the waypoint or number of paths where it has one.'
    },
    before: {
      text: 'The state before this candidate: unresolved, fixed, side effect, or ok when it holds as required.'
    },
    after: { text: 'The state with this candidate applied.' },
    edits: { text: 'The configuration lines removed and added, router by router.' },
    evidence: {
      text: 'What the verifier observed about the specifications this candidate changes.'
    },
    traces: {
      text: 'For each specification, the paths its traffic took through the network and how each ended, such as accepted, denied or no route.'
    },
    verdict: {
      text: 'How the network measures up against the specifications once the adopted repairs are applied.'
    },
    unresolved: { text: 'Specifications violated at the start that are still violated.' },
    fixed: { text: 'Specifications violated at the start that now hold as required.' },
    sideEffects: { text: 'Specifications that held at the start but are violated now.' },
    regions: {
      text: 'The regions of the search space. A region holds the candidate repairs at one place in the network, and the search judges them together.'
    },
    regionId: { text: 'The identifier that decisions and events use for the region.' },
    kind: {
      items: [
        [
          'site',
          'Candidates that edit one configuration object, such as one interface or one BGP neighbor.'
        ],
        [
          'relation',
          'Candidates that complete one broken relation, possibly on several routers, such as both ends of a BGP session.'
        ],
        [
          'incidence',
          'Candidates that complete the BGP sessions of a route reflector and its clients.'
        ],
        ['transfer', 'Candidates that change a policy that moves routes between routing protocols.']
      ]
    },
    routers: { text: "The routers the region's candidates may change." },
    bound: {
      text: 'How many candidate repairs the region holds, or at most holds when they have not been generated yet.'
    },
    stop: { items: STOPS }
  }
}
