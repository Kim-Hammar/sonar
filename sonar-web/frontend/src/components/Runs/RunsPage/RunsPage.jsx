import { Plus } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import { RUNS_REFRESH } from '../../Common/constants'
import DataTable from '../../Common/DataTable/DataTable'
import Empty from '../../Common/Empty/Empty'
import { formatDateTime, formatDuration } from '../../Common/format'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import PageHeader from '../../Layout/PageHeader/PageHeader'

function duration(run, now) {
  if (!run.started) {
    return '–'
  }
  return formatDuration((run.finished || now) - run.started)
}

/** Every agent execution, newest first, refreshed every few seconds. */
export default function RunsPage() {
  const [runs, setRuns] = useState(null)
  const [error, setError] = useState(null)
  const [now, setNow] = useState(() => Date.now())
  const navigate = useNavigate()

  useEffect(() => {
    let active = true
    const load = () =>
      api.runs().then(
        (body) => {
          if (active) {
            setRuns(body.runs)
            setNow(Date.now())
            setError(null)
          }
        },
        (failure) => active && setError(failure.message)
      )
    load()
    const timer = setInterval(load, RUNS_REFRESH)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [])

  const columns = [
    {
      id: 'name',
      header: 'Name',
      hint: HINTS.executions.name,
      cell: (run) => <span className="RunsPage-name">{run.name}</span>
    },
    {
      id: 'status',
      header: 'Status',
      hint: HINTS.executions.status,
      cell: (run) => (
        <span className="RunsPage-status">
          <StatusBadge status={run.status} />
          {run.demo && <StatusBadge status="demo" label="Demo" />}
        </span>
      )
    },
    {
      id: 'scenarios',
      header: 'Scenarios',
      hint: HINTS.executions.scenarios,
      align: 'right',
      cell: (run) => run.scenarios.length
    },
    {
      id: 'created',
      header: 'Created',
      hint: HINTS.executions.created,
      cell: (run) => formatDateTime(run.created)
    },
    {
      id: 'duration',
      header: 'Duration',
      hint: HINTS.executions.duration,
      align: 'right',
      cell: (run) => duration(run, now)
    }
  ]

  return (
    <div className="page">
      <PageHeader
        title="Agent executions"
        actions={
          <Link to="/executions/new" className="button primary">
            <Plus size={16} />
            New agent execution
          </Link>
        }
      />
      <Alert>{error}</Alert>
      {runs && runs.length === 0 && (
        <Empty
          title="No agent executions yet"
          action={
            <Link to="/executions/new" className="button primary">
              Start an agent execution
            </Link>
          }
        >
          An agent execution repairs one or more scenarios and shows its progress here as it
          happens.
        </Empty>
      )}
      {runs && runs.length > 0 && (
        <DataTable
          columns={columns}
          rows={runs}
          rowKey={(run) => run.id}
          onRowClick={(run) => navigate(`/executions/${run.id}`)}
        />
      )}
    </div>
  )
}
