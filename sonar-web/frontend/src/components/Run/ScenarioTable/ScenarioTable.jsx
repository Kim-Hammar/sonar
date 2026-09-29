import DataTable from '../../Common/DataTable/DataTable'
import { formatCompact, formatNumber, STOP_LABELS } from '../../Common/format'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'

/** One row per scenario of a run with its state and what it consumed; a row opens its details. */
export default function ScenarioTable({ run, state, onOpen }) {
  const rows = run.scenarios.map(
    (name) => state.scenarios[name] || { name, status: 'pending', usage: {}, adopted: 0, tried: 0 }
  )
  const columns = [
    { id: 'name', header: 'Scenario', cell: (row) => <span className="mono">{row.name}</span> },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => (
        <StatusBadge
          status={row.status}
          label={row.stop && row.status === 'stopped' ? STOP_LABELS[row.stop] : undefined}
        />
      )
    },
    {
      id: 'remaining',
      header: 'Remaining',
      align: 'right',
      cell: (row) =>
        row.remaining === null || row.remaining === undefined
          ? '–'
          : `${formatNumber(row.remaining)} of ${formatNumber(row.initial)}`
    },
    { id: 'adopted', header: 'Adopted', align: 'right', cell: (row) => formatNumber(row.adopted) },
    { id: 'tried', header: 'Candidates', align: 'right', cell: (row) => formatNumber(row.tried) },
    {
      id: 'verifications',
      header: 'Verifications',
      align: 'right',
      cell: (row) => formatNumber(row.usage.verifications ?? 0)
    },
    {
      id: 'decisions',
      header: 'Decisions',
      align: 'right',
      cell: (row) => formatNumber(row.usage.decisions ?? 0)
    },
    {
      id: 'tokens',
      header: 'Tokens',
      align: 'right',
      cell: (row) => formatCompact((row.usage.inputTokens ?? 0) + (row.usage.outputTokens ?? 0))
    },
    {
      id: 'reason',
      header: 'Note',
      cell: (row) => <span className="muted">{row.reason || ''}</span>
    }
  ]
  return (
    <DataTable
      columns={columns}
      rows={rows}
      rowKey={(row) => row.name}
      onRowClick={(row) => onOpen(row.name)}
    />
  )
}
