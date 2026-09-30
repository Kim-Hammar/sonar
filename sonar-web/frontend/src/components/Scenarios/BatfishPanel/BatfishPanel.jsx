import { useEffect, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import DataTable from '../../Common/DataTable/DataTable'
import FieldLabel from '../../Common/FieldLabel/FieldLabel'
import FlowTrace from '../../Common/FlowTrace/FlowTrace'
import { formatDuration, formatNumber } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import Tabs from '../../Common/Tabs/Tabs'
import Tiles from '../../Common/Tiles/Tiles'
import SpecificationTable from '../SpecificationTable/SpecificationTable'
import './BatfishPanel.css'

const POLL = 1000
const PAGE = 200
const hints = HINTS.batfish

function active(verification) {
  return (
    Boolean(verification) && (verification.status === 'queued' || verification.status === 'running')
  )
}

function Names({ names }) {
  if (names.length === 0) {
    return <span className="muted">none</span>
  }
  return <span className="mono BatfishPanel-names">{names.join(', ')}</span>
}

function Summary({ verification }) {
  const summary = verification.summary
  const tiles = [
    { label: 'Routers', hint: hints.routers, value: formatNumber(summary.routers) },
    {
      label: 'Forwarding rows',
      hint: hints.forwardingRows,
      value: formatNumber(summary.forwardingRows)
    },
    {
      label: 'Violated',
      hint: hints.violated,
      value: formatNumber(summary.unresolved),
      detail: `of ${formatNumber(summary.specifications)} specifications`
    },
    { label: 'Holding', hint: hints.holding, value: formatNumber(summary.holding) },
    {
      label: 'Label mismatches',
      hint: hints.mismatches,
      value: formatNumber(summary.fixed + summary.sideEffects),
      detail: `${formatNumber(summary.fixed)} fixed, ${formatNumber(summary.sideEffects)} side effects`
    },
    {
      label: 'Batfish time',
      hint: hints.elapsed,
      value: formatDuration(verification.elapsedMillis)
    }
  ]
  return (
    <div className="BatfishPanel-summary">
      <Tiles tiles={tiles} />
    </div>
  )
}

function TraceView({ scenario, percent, specification, client }) {
  const [trace, setTrace] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    client.verifiedTraces(scenario, percent, specification).then(
      (body) => !cancelled && setTrace(body),
      (failure) => !cancelled && setError(failure.message)
    )
    return () => {
      cancelled = true
    }
  }, [scenario, percent, specification, client])

  return (
    <div className="BatfishPanel-trace card">
      <div className="BatfishPanel-traceHeader">
        <Hint {...hints.traces}>Forwarding traces</Hint>
        <span className="mono">{specification}</span>
        {trace && <StatusBadge status={trace.state} />}
      </div>
      <Alert>{error}</Alert>
      {trace ? (
        <>
          <FlowTrace flows={trace.flows} />
          <div className="BatfishPanel-routers">
            <Hint {...hints.routeHolders}>BGP route holders</Hint>
            <Names names={trace.routeHolders} />
          </div>
          <div className="BatfishPanel-routers">
            <Hint {...hints.forwarders}>Forwarding routers</Hint>
            <Names names={trace.forwarders} />
          </div>
        </>
      ) : (
        !error && <div className="muted">Loading…</div>
      )}
    </div>
  )
}

/** The traces of one specification; a new specification gets a fresh view. */
function TracePanel({ scenario, percent, specification, client }) {
  return (
    <TraceView
      key={specification}
      scenario={scenario}
      percent={percent}
      specification={specification}
      client={client}
    />
  )
}

function SpecificationsView({ scenario, percent, client }) {
  const [rows, setRows] = useState(null)
  const [statuses, setStatuses] = useState(null)
  const [selected, setSelected] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    client.verifiedSpecifications(scenario, percent).then(
      (body) => {
        if (!cancelled) {
          setRows(body.specifications)
          setStatuses(new Map(body.specifications.map((row) => [row.key, row.state])))
        }
      },
      (failure) => !cancelled && setError(failure.message)
    )
    return () => {
      cancelled = true
    }
  }, [scenario, percent, client])

  return (
    <>
      <Alert>{error}</Alert>
      {rows ? (
        <SpecificationTable
          rows={rows}
          statuses={statuses}
          onSelect={setSelected}
          selected={selected}
        />
      ) : (
        !error && <div className="muted">Loading…</div>
      )}
      {selected && (
        <TracePanel
          scenario={scenario}
          percent={percent}
          specification={selected}
          client={client}
        />
      )}
    </>
  )
}

const FORWARDING_COLUMNS = [
  {
    id: 'node',
    header: 'Router',
    hint: hints.forwarding.router,
    cell: (row) => <span className="mono">{row.node}</span>
  },
  {
    id: 'prefix',
    header: 'Prefix',
    hint: hints.forwarding.prefix,
    cell: (row) => <span className="mono">{row.prefix}</span>
  },
  {
    id: 'nextHopIp',
    header: 'Next hop',
    hint: hints.forwarding.nextHop,
    cell: (row) => <span className="mono">{row.nextHopIp || '–'}</span>
  },
  {
    id: 'nextHopInterface',
    header: 'Interface',
    hint: hints.forwarding.interface,
    cell: (row) => <span className="mono">{row.nextHopInterface || '–'}</span>
  },
  {
    id: 'action',
    header: 'Action',
    hint: hints.forwarding.action,
    cell: (row) => <StatusBadge status={row.action} />
  },
  {
    id: 'receivers',
    header: 'Blocked by',
    hint: hints.forwarding.receivers,
    cell: (row) => <span className="mono">{row.receivers.join(', ')}</span>
  }
]

function ForwardingView({ scenario, percent, client }) {
  const [filter, setFilter] = useState({ node: '', action: '', prefix: '' })
  const [page, setPage] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    client.verifiedForwarding(scenario, percent, { ...filter, offset: 0, limit: PAGE }).then(
      (body) => !cancelled && setPage(body),
      (failure) => !cancelled && setError(failure.message)
    )
    return () => {
      cancelled = true
    }
  }, [scenario, percent, client, filter])

  const more = () =>
    client
      .verifiedForwarding(scenario, percent, { ...filter, offset: page.rows.length, limit: PAGE })
      .then(
        (body) => setPage({ ...body, rows: [...page.rows, ...body.rows] }),
        (failure) => setError(failure.message)
      )
  const change = (field) => (event) => setFilter({ ...filter, [field]: event.target.value })

  return (
    <>
      <Alert>{error}</Alert>
      {page && (
        <>
          <div className="BatfishPanel-filters">
            <FieldLabel htmlFor="forwarding-node" hint={hints.forwarding.router}>
              Router
            </FieldLabel>
            <select
              id="forwarding-node"
              className="input"
              value={filter.node}
              onChange={change('node')}
            >
              <option value="">All routers</option>
              {page.nodes.map((node) => (
                <option key={node} value={node}>
                  {node}
                </option>
              ))}
            </select>
            <FieldLabel htmlFor="forwarding-action" hint={hints.forwarding.action}>
              Action
            </FieldLabel>
            <select
              id="forwarding-action"
              className="input"
              value={filter.action}
              onChange={change('action')}
            >
              <option value="">All actions</option>
              {page.actions.map((action) => (
                <option key={action} value={action}>
                  {action.replaceAll('_', ' ')}
                </option>
              ))}
            </select>
            <FieldLabel htmlFor="forwarding-prefix" hint={hints.forwarding.prefix}>
              Prefix
            </FieldLabel>
            <input
              id="forwarding-prefix"
              className="input"
              placeholder="Contains"
              value={filter.prefix}
              onChange={change('prefix')}
            />
            <span className="muted BatfishPanel-count">
              {formatNumber(page.matched)} of {formatNumber(page.total)} rows
            </span>
          </div>
          <DataTable
            columns={FORWARDING_COLUMNS}
            rows={page.rows}
            rowKey={(row) =>
              `${row.node}|${row.prefix}|${row.nextHopIp || ''}|${row.nextHopInterface || ''}|${row.action}`
            }
            empty="No row matches"
          />
          {page.rows.length < page.matched && (
            <button type="button" className="button quiet" onClick={more}>
              Show more of {formatNumber(page.matched)}
            </button>
          )}
        </>
      )}
    </>
  )
}

const ROUTE_COLUMNS = [
  {
    id: 'prefix',
    header: 'Prefix',
    hint: hints.routes.prefix,
    cell: (row) => <span className="mono">{row.prefix}</span>
  },
  {
    id: 'holders',
    header: 'BGP route holders',
    hint: hints.routes.holders,
    cell: (row) => <Names names={row.holders} />
  },
  {
    id: 'forwarders',
    header: 'Forwarding routers',
    hint: hints.routes.forwarders,
    cell: (row) => <Names names={row.forwarders} />
  }
]

function RoutesView({ scenario, percent, client }) {
  const [routes, setRoutes] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    client.verifiedRoutes(scenario, percent).then(
      (body) => !cancelled && setRoutes(body.routes),
      (failure) => !cancelled && setError(failure.message)
    )
    return () => {
      cancelled = true
    }
  }, [scenario, percent, client])

  return (
    <>
      <Alert>{error}</Alert>
      {routes ? (
        <DataTable
          columns={ROUTE_COLUMNS}
          rows={routes}
          rowKey={(row) => row.prefix}
          empty="No specified destination"
        />
      ) : (
        !error && <div className="muted">Loading…</div>
      )}
    </>
  )
}

const VIEWS = ['specifications', 'forwarding', 'routes']

/**
 * Runs Batfish on a scenario's faulty configurations against one specification file and shows
 * what it found: the state of every specification with its forwarding traces, the forwarding
 * table, and the routers holding BGP routes to the specified destinations. The page keeps the
 * chosen `view` and is told through `onView` when it changes. `client` is the server's API and
 * `pollMillis` the pause between status checks, both replaceable in tests.
 */
export default function BatfishPanel({
  scenario,
  percent,
  view,
  onView,
  client = api,
  pollMillis = POLL
}) {
  const [verification, setVerification] = useState(undefined)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [starting, setStarting] = useState(false)
  const shown = VIEWS.includes(view) ? view : VIEWS[0]

  useEffect(() => {
    let cancelled = false
    let timer = null
    const poll = () =>
      client.verification(scenario, percent).then(
        (body) => {
          if (cancelled) {
            return
          }
          setVerification(body.verification)
          if (active(body.verification)) {
            timer = setTimeout(poll, pollMillis)
          }
        },
        (failure) => {
          if (cancelled) {
            return
          }
          if (failure.status === 404) {
            setVerification(null)
          } else {
            setError(failure.message)
          }
        }
      )
    poll()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [scenario, percent, client, attempt, pollMillis])

  const start = () => {
    setStarting(true)
    setError(null)
    client
      .verify(scenario, percent)
      .then(
        (body) => {
          setVerification(body.verification)
          setAttempt((count) => count + 1)
        },
        (failure) => setError(failure.message)
      )
      .finally(() => setStarting(false))
  }

  if (verification === undefined && !error) {
    return <div className="muted">Loading…</div>
  }
  const summary = verification && verification.summary
  return (
    <div className="BatfishPanel">
      <Alert>{error}</Alert>
      {verification === null && (
        <div className="BatfishPanel-start card">
          <p>
            <Hint {...hints.run}>Batfish</Hint> computes how the faulty network forwards traffic and
            checks every specification of the chosen file against it.
          </p>
          <button type="button" className="button primary" onClick={start} disabled={starting}>
            Run Batfish
          </button>
        </div>
      )}
      {active(verification) && (
        <div className="BatfishPanel-status card">
          <Hint label="Status" {...hints.status}>
            <StatusBadge status={verification.status} />
          </Hint>
          <span>Batfish is computing how the faulty network forwards traffic.</span>
          <span className="muted">{formatDuration(verification.elapsedMillis)}</span>
        </div>
      )}
      {verification && verification.status === 'failed' && (
        <div className="BatfishPanel-start card">
          <Alert>Batfish failed: {verification.error}</Alert>
          <button type="button" className="button primary" onClick={start} disabled={starting}>
            Run again
          </button>
        </div>
      )}
      {summary && (
        <>
          <Summary verification={verification} />
          <div className="BatfishPanel-views">
            <Tabs
              tabs={[
                {
                  id: 'specifications',
                  label: 'Specifications',
                  count: formatNumber(summary.specifications)
                },
                {
                  id: 'forwarding',
                  label: 'Forwarding table',
                  count: formatNumber(summary.forwardingRows)
                },
                { id: 'routes', label: 'BGP routes' }
              ]}
              active={shown}
              onChange={onView}
            />
            <Hint label="Views" {...hints.views} />
          </div>
          {shown === 'specifications' && (
            <SpecificationsView scenario={scenario} percent={percent} client={client} />
          )}
          {shown === 'forwarding' && (
            <ForwardingView scenario={scenario} percent={percent} client={client} />
          )}
          {shown === 'routes' && (
            <RoutesView scenario={scenario} percent={percent} client={client} />
          )}
        </>
      )}
    </div>
  )
}
