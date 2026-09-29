import { useMemo, useState } from 'react'
import DataTable from '../../Common/DataTable/DataTable'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import './SpecificationTable.css'

const PAGE = 300

function stateOf(row, statuses) {
  if (statuses) {
    return statuses.get(row.key) || 'holding'
  }
  return row.initial === 'broken' ? 'violated' : row.initial
}

const FILTERS_WITH_STATE = [
  ['all', 'All'],
  ['unresolved', 'Unresolved'],
  ['fixed', 'Fixed'],
  ['side-effect', 'Side effects'],
  ['holding', 'Holding']
]

const FILTERS_WITHOUT_STATE = [
  ['all', 'All'],
  ['violated', 'Violated'],
  ['intact', 'Intact']
]

/**
 * Specifications with filters: by state when a run has verified them, otherwise by their status
 * in the faulty network.
 */
export default function SpecificationTable({ rows, statuses = null }) {
  const [filter, setFilter] = useState('all')
  const [query, setQuery] = useState('')
  const [limit, setLimit] = useState(PAGE)
  const filters = statuses ? FILTERS_WITH_STATE : FILTERS_WITHOUT_STATE
  const counts = useMemo(() => {
    const result = { all: rows.length }
    for (const row of rows) {
      const state = stateOf(row, statuses)
      result[state] = (result[state] || 0) + 1
    }
    return result
  }, [rows, statuses])
  const shown = useMemo(
    () =>
      rows.filter(
        (row) =>
          (filter === 'all' || stateOf(row, statuses) === filter) &&
          (query === '' || row.key.includes(query.trim()))
      ),
    [rows, statuses, filter, query]
  )
  const hints = HINTS.specifications
  const columns = [
    { id: 'type', header: 'Type', hint: hints.type, cell: (row) => row.type.replace('_', ' ') },
    {
      id: 'source',
      header: 'Source',
      hint: hints.source,
      cell: (row) => <span className="mono">{row.source}</span>
    },
    {
      id: 'destination',
      header: 'Destination',
      hint: hints.destination,
      cell: (row) => <span className="mono">{row.destination}</span>
    },
    {
      id: 'extra',
      header: 'Waypoint or routes',
      hint: hints.extra,
      cell: (row) => (
        <span className="mono">{row.waypoint || (row.routes ? `${row.routes} routes` : '')}</span>
      )
    },
    {
      id: 'label',
      header: 'Label',
      hint: hints.label,
      cell: (row) => <span className="muted">{row.status}</span>
    },
    {
      id: 'state',
      header: 'State',
      hint: statuses ? hints.runState : hints.initialState,
      cell: (row) => <StatusBadge status={stateOf(row, statuses)} />
    }
  ]
  return (
    <div className="SpecificationTable">
      <div className="SpecificationTable-bar">
        <div className="SpecificationTable-filters" role="group" aria-label="Filter specifications">
          {filters.map(([id, label]) => (
            <button
              key={id}
              type="button"
              className={
                filter === id ? 'SpecificationTable-filter active' : 'SpecificationTable-filter'
              }
              onClick={() => {
                setFilter(id)
                setLimit(PAGE)
              }}
            >
              {label} <span className="muted">{counts[id] || 0}</span>
            </button>
          ))}
        </div>
        <input
          className="input"
          placeholder="Search"
          aria-label="Search specifications"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>
      <DataTable
        columns={columns}
        rows={shown.slice(0, limit)}
        rowKey={(row) => row.key}
        empty="No specification matches"
      />
      {shown.length > limit && (
        <button type="button" className="button quiet" onClick={() => setLimit(limit + PAGE)}>
          Show more of {shown.length}
        </button>
      )}
    </div>
  )
}
