import { useCallback, useEffect, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import DataTable from '../../Common/DataTable/DataTable'
import FieldLabel from '../../Common/FieldLabel/FieldLabel'
import { formatDuration, formatNumber } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import Tiles from '../../Common/Tiles/Tiles'
import { factorHint, formatPercent } from './factors'
import RegionDrawer from './RegionDrawer'
import SearchExplanation from './SearchExplanation'
import './SearchPanel.css'

const POLL = 1000
const PAGE = 100
const ACTIVE = new Set(['queued', 'verifying', 'building'])
const hints = HINTS.search

function Summary({ search }) {
  const summary = search.summary
  const tiles = [
    {
      label: 'Regions',
      hint: hints.regions,
      value: formatNumber(summary.regions),
      detail: `${formatNumber(summary.partitions)} partitions`
    },
    {
      label: 'Candidates',
      hint: hints.candidates,
      value: `≤ ${formatNumber(summary.bound)}`,
      detail: `${formatNumber(summary.candidates)} generated in ${formatNumber(summary.expanded)} regions`
    },
    {
      label: 'Violated specifications',
      hint: hints.violated,
      value: formatNumber(summary.unresolved),
      detail: `of ${formatNumber(summary.specifications)}`
    },
    { label: 'Build time', hint: hints.elapsed, value: formatDuration(search.elapsedMillis) }
  ]
  return (
    <div className="SearchPanel-summary">
      <Tiles tiles={tiles} />
    </div>
  )
}

function routers(names) {
  return names.length > 2
    ? `${names.slice(0, 2).join(', ')} +${names.length - 2}`
    : names.join(', ')
}

function candidates(row) {
  if (row.expansion === 'deferred') {
    return `≤ ${formatNumber(row.bound)}`
  }
  if (row.expansion === 'admitted') {
    return formatNumber(row.candidates)
  }
  return <StatusBadge status={row.expansion} />
}

function columns(factors) {
  return [
    {
      id: 'id',
      header: 'Region',
      hint: hints.region,
      cell: (row) => <span className="mono SearchPanel-region">{row.id}</span>
    },
    {
      id: 'kind',
      header: 'Kind',
      hint: hints.kind,
      cell: (row) => <StatusBadge status={row.kind} />
    },
    {
      id: 'routers',
      header: 'Routers',
      hint: hints.routers,
      cell: (row) => <span className="mono SearchPanel-routers">{routers(row.routers)}</span>
    },
    {
      id: 'bound',
      header: 'Candidates',
      hint: hints.bound,
      align: 'right',
      cell: candidates
    },
    {
      id: 'prior',
      header: 'Prior',
      hint: hints.prior,
      align: 'right',
      cell: (row) => formatPercent(row.prior)
    },
    ...factors.map((name) => ({
      id: name,
      header: name,
      hint: factorHint(name),
      align: 'right',
      cell: (row) => row.factors[name].toFixed(2)
    }))
  ]
}

function RegionTable({ scenario, percent, factors, version, selected, onSelect, client }) {
  const [filter, setFilter] = useState({ kind: '', router: '', partition: '', query: '' })
  const [page, setPage] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    client.searchRegions(scenario, percent, { ...filter, offset: 0, limit: PAGE }).then(
      (body) => !cancelled && setPage(body),
      (failure) => !cancelled && setError(failure.message)
    )
    return () => {
      cancelled = true
    }
  }, [scenario, percent, client, filter, version])

  const more = () =>
    client
      .searchRegions(scenario, percent, { ...filter, offset: page.regions.length, limit: PAGE })
      .then(
        (body) => setPage({ ...body, regions: [...page.regions, ...body.regions] }),
        (failure) => setError(failure.message)
      )
  const change = (field) => (event) => setFilter({ ...filter, [field]: event.target.value })

  return (
    <>
      <Alert>{error}</Alert>
      {page && (
        <>
          <div className="SearchPanel-filters">
            <FieldLabel htmlFor="search-kind" hint={hints.kind}>
              Kind
            </FieldLabel>
            <select
              id="search-kind"
              className="input"
              value={filter.kind}
              onChange={change('kind')}
            >
              <option value="">All kinds</option>
              {page.kinds.map((kind) => (
                <option key={kind} value={kind}>
                  {kind}
                </option>
              ))}
            </select>
            <FieldLabel htmlFor="search-router" hint={hints.routers}>
              Router
            </FieldLabel>
            <select
              id="search-router"
              className="input"
              value={filter.router}
              onChange={change('router')}
            >
              <option value="">All routers</option>
              {page.routers.map((router) => (
                <option key={router} value={router}>
                  {router}
                </option>
              ))}
            </select>
            <FieldLabel htmlFor="search-partition" hint={hints.partitions}>
              Partition
            </FieldLabel>
            <select
              id="search-partition"
              className="input"
              value={filter.partition}
              onChange={change('partition')}
            >
              <option value="">All partitions</option>
              {page.partitions.map((partition) => (
                <option key={partition.id} value={partition.id}>
                  {partition.id} ({partition.regions}, {formatPercent(partition.mass)})
                </option>
              ))}
            </select>
            <input
              className="input"
              placeholder="Region contains"
              aria-label="Region contains"
              value={filter.query}
              onChange={change('query')}
            />
            <span className="muted SearchPanel-count">
              {formatNumber(page.matched)} of {formatNumber(page.total)} regions
            </span>
          </div>
          <DataTable
            columns={columns(factors)}
            rows={page.regions}
            rowKey={(row) => row.id}
            onRowClick={(row) => onSelect(row.id)}
            selected={selected}
            empty="No region matches"
          />
          {page.regions.length < page.matched && (
            <button type="button" className="button quiet" onClick={more}>
              Show more of {formatNumber(page.matched)}
            </button>
          )}
        </>
      )}
    </>
  )
}

/**
 * Builds the search space the agent would start from for a scenario and specification file, and
 * shows its regions with their prior and each factor's weight; a region opens in a drawer, where
 * its candidate repairs can be generated. The page keeps the open `region` and is told through
 * `onRegion` when it changes. `client` is the server's API and `pollMillis` the pause between
 * status checks, both replaceable in tests.
 */
export default function SearchPanel({
  scenario,
  percent,
  region,
  onRegion,
  client = api,
  pollMillis = POLL
}) {
  const [search, setSearch] = useState(undefined)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [starting, setStarting] = useState(false)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    let timer = null
    const poll = () =>
      client.search(scenario, percent).then(
        (body) => {
          if (cancelled) {
            return
          }
          setSearch(body.search)
          if (ACTIVE.has(body.search.status)) {
            timer = setTimeout(poll, pollMillis)
          }
        },
        (failure) => {
          if (cancelled) {
            return
          }
          if (failure.status === 404) {
            setSearch(null)
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
      .explore(scenario, percent)
      .then(
        (body) => {
          setSearch(body.search)
          setAttempt((count) => count + 1)
        },
        (failure) => setError(failure.message)
      )
      .finally(() => setStarting(false))
  }
  const settled = useCallback(() => {
    setVersion((count) => count + 1)
    setAttempt((count) => count + 1)
  }, [])
  const close = useCallback(() => onRegion(null), [onRegion])

  if (search === undefined && !error) {
    return <div className="muted">Loading…</div>
  }
  const factors = search ? search.factors : []
  return (
    <div className="SearchPanel">
      <Alert>{error}</Alert>
      <div className="SearchPanel-explanation">
        <SearchExplanation />
      </div>
      {search === null && (
        <div className="SearchPanel-start card">
          <p>
            <Hint {...hints.start}>The search space</Hint> holds the regions of candidate repairs
            the agent starts from, with how likely each is to hold the repair.
          </p>
          <button type="button" className="button primary" onClick={start} disabled={starting}>
            Build search space
          </button>
        </div>
      )}
      {search && ACTIVE.has(search.status) && (
        <div className="SearchPanel-status card">
          <Hint label="Status" {...hints.status}>
            <StatusBadge status={search.status} />
          </Hint>
          <span>
            {search.status === 'verifying'
              ? 'Batfish is verifying the faulty network.'
              : 'The regions and their prior are being computed.'}
          </span>
          <span className="muted">{formatDuration(search.elapsedMillis)}</span>
        </div>
      )}
      {search && search.status === 'failed' && (
        <div className="SearchPanel-start card">
          <Alert>Building the search space failed: {search.error}</Alert>
          <button type="button" className="button primary" onClick={start} disabled={starting}>
            Run again
          </button>
        </div>
      )}
      {search && search.summary && (
        <>
          <Summary search={search} />
          <RegionTable
            scenario={scenario}
            percent={percent}
            factors={factors}
            version={version}
            selected={region}
            onSelect={onRegion}
            client={client}
          />
          {region && (
            <RegionDrawer
              key={region}
              scenario={scenario}
              percent={percent}
              id={region}
              factors={factors}
              onClose={close}
              onSettled={settled}
              client={client}
              pollMillis={pollMillis}
            />
          )}
        </>
      )}
    </div>
  )
}
