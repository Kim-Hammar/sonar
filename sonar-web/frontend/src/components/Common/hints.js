import { EVENT_LABELS, STOP_LABELS } from './format'

const DURATION_ONLY = "Only the run's duration depends on it, unless a time limit is set."

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
  cancelled: 'The run was cancelled.',
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
      text: 'A label for the run in the runs list. Without one, the run is listed under its identifier, which records when it was created.'
    },
    scenarios: {
      text: 'The benchmark scenarios to repair. Each is repaired on its own, with its own budget.'
    },
    specifications: {
      text: "The share of each scenario's specifications that the run checks. The benchmark provides samples of 2 to 100 per cent; a larger sample describes the intended forwarding more completely."
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
    verificationWorkers: {
      text: `How many Batfish engines verify candidates at the same time. Each engine needs its own memory. ${DURATION_ONLY}`
    },
    decisionConcurrency: {
      text: `How many requests to the decision model may be in flight at the same time. ${DURATION_ONLY}`
    },
    generationParallelism: {
      text: `How many threads generate candidate repairs and discard those that cannot be repairs, such as edits that do not parse, before anything is verified. ${DURATION_ONLY}`
    },
    scenarioParallelism: {
      text: `How many scenarios are repaired at the same time, each with its own budget. ${DURATION_ONLY}`
    },
    prefetchDecisions: {
      text: 'How many questions may be sent to the decision model before they are needed, while candidates are verified, to hide its response time; 0 turns this off. Answers the search never uses still cost provider tokens but do not count against the budget.'
    },
    seed: {
      text: 'The seed of every random choice the search makes. Runs with the same seed, scenarios and settings make the same random choices.'
    },
    decisionModel: {
      text: "The language model that answers the search's questions, and the address it is reached at. The server's --model and --endpoint options set both, and the API key comes from the server's environment."
    }
  },
  runs: {
    name: { text: "The name given when the run was started, or the run's identifier." },
    status: {
      items: [
        ['Queued', 'Waiting for the runs before it; one run executes at a time.'],
        ['Running', 'Repairing its scenarios now.'],
        ['Finished', 'Every scenario ended; open the run to see which were solved.'],
        ['Failed', 'An error outside any single scenario stopped the run.'],
        ['Cancelled', 'The run was cancelled.'],
        ['Interrupted', 'The server stopped while the run was queued or running.']
      ]
    },
    scenarios: { text: 'How many scenarios the run repairs.' },
    created: { text: 'When the run was submitted.' },
    duration: {
      text: "Time from the run's start to its end, or until now while it runs. Time spent waiting in the queue is not counted."
    }
  },
  kpi: {
    scenarios: {
      text: 'Scenarios that have ended, of all the scenarios in the run, and how many of them were solved. A scenario is solved when every specification holds as required.'
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
      text: 'Time since the run started, or how long it took once it ended. Time spent waiting in the queue is not counted.'
    }
  },
  charts: {
    progress: {
      text: "Running totals over the time since the run started. With all scenarios selected, each point adds up every scenario's latest values; choose one scenario to see only its own."
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
      text: "The scenarios in the server's dataset. Each is a network whose configurations contain faults, with its topology and specifications."
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
      text: 'Each request sent to the decision model for this question, with its reply, retries included. A cached reply was read from the decision cache in the output directory, without a provider call.'
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
        ]
      ]
    },
    routers: { text: "The routers the region's candidates may change." },
    bound: {
      text: 'How many candidate repairs the region holds, or at most holds when they have not been generated yet.'
    },
    stop: { items: STOPS }
  }
}
