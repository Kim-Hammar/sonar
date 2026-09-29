/** The cumulative counters every event summary carries. */
export const METRICS = [
  'verifications',
  'decisions',
  'providerCalls',
  'inputTokens',
  'outputTokens'
]

const STATE_TYPES = new Set(['loaded', 'baseline', 'adopted', 'finished'])

/** The state of a run page before any event arrives. */
export const initialState = {
  events: [],
  lastSeq: 0,
  ended: false,
  connection: 'loading',
  error: null,
  startedAt: null,
  order: [],
  scenarios: {},
  series: {},
  aggregate: [],
  totals: {
    remaining: 0,
    verifications: 0,
    decisions: 0,
    providerCalls: 0,
    inputTokens: 0,
    outputTokens: 0
  }
}

function emptyScenario(name) {
  return {
    name,
    status: 'pending',
    usage: { verifications: 0, decisions: 0, providerCalls: 0, inputTokens: 0, outputTokens: 0 },
    remaining: null,
    initial: null,
    adopted: 0,
    tried: 0,
    changed: []
  }
}

function status(event, previous) {
  switch (event.type) {
    case 'finished':
      if (event.stop === 'solved') {
        return 'solved'
      }
      return event.stop === 'cancelled' ? 'cancelled' : 'stopped'
    case 'failed':
      return 'failed'
    default:
      return previous && previous !== 'pending' ? previous : 'running'
  }
}

function usage(summary) {
  const values = {}
  for (const metric of METRICS) {
    values[metric] = (summary && summary[metric]) || 0
  }
  return values
}

function apply(state, events) {
  const fresh = events.filter((event) => event.seq > state.lastSeq).sort((a, b) => a.seq - b.seq)
  if (fresh.length === 0) {
    return state
  }
  const startedAt = state.startedAt ?? fresh[0].at
  const scenarios = { ...state.scenarios }
  const series = { ...state.series }
  const copied = new Set()
  const aggregate = state.aggregate.slice()
  const order = state.order.slice()
  const totals = { ...state.totals }
  for (const event of fresh) {
    const previous = scenarios[event.scenario]
    if (!previous) {
      order.push(event.scenario)
    }
    const current = { ...(previous ?? emptyScenario(event.scenario)) }
    current.status = status(event, previous && previous.status)
    current.usage = usage(event.usage)
    current.lastAt = event.at
    if (event.type === 'loaded') {
      current.routers = event.routers
      current.links = event.links
      current.specifications = event.specifications
      current.initial = event.remaining
    }
    if (STATE_TYPES.has(event.type) && event.remaining !== undefined) {
      current.remaining = event.remaining
    }
    if (event.type === 'tried') {
      current.tried += 1
    }
    if (event.type === 'adopted') {
      current.adopted += event.adopted
      current.changed = [...new Set([...current.changed, ...(event.routers || [])])]
    }
    if (event.type === 'finished') {
      current.stop = event.stop
    }
    if (event.type === 'failed') {
      current.reason = event.reason
    }
    for (const metric of METRICS) {
      totals[metric] += current.usage[metric] - (previous ? previous.usage[metric] : 0)
    }
    totals.remaining += (current.remaining ?? 0) - ((previous && previous.remaining) ?? 0)
    scenarios[event.scenario] = current
    const t = event.at - startedAt
    if (!copied.has(event.scenario)) {
      series[event.scenario] = (series[event.scenario] ?? []).slice()
      copied.add(event.scenario)
    }
    series[event.scenario].push({
      t,
      seq: event.seq,
      remaining: current.remaining,
      ...current.usage
    })
    aggregate.push({ t, seq: event.seq, ...totals })
  }
  return {
    ...state,
    events: state.events.concat(fresh),
    lastSeq: fresh[fresh.length - 1].seq,
    startedAt,
    scenarios,
    series,
    aggregate,
    totals,
    order
  }
}

/**
 * Folds event summaries into what the run page shows: per-scenario states, per-scenario series,
 * and the aggregate series that sums every scenario's latest values. Summaries at or below the
 * last sequence number are ignored, so history and stream may overlap.
 */
export function runReducer(state, action) {
  switch (action.type) {
    case 'reset':
      return initialState
    case 'events':
      return apply(state, action.events)
    case 'ended':
      return { ...state, ended: true, connection: 'ended' }
    case 'connection':
      return state.ended ? state : { ...state, connection: action.status }
    case 'error':
      return { ...state, error: action.error }
    default:
      return state
  }
}
