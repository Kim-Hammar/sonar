import { Plus } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import { RUNS_REFRESH } from '../../Common/constants'
import DataTable from '../../Common/DataTable/DataTable'
import Empty from '../../Common/Empty/Empty'
import { formatDateTime, formatDuration } from '../../Common/format'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import PageHeader from '../../Layout/PageHeader/PageHeader'

function duration(run, now) {
  if (!run.started) {
    return '–'
  }
  return formatDuration((run.finished || now) - run.started)
}

/** Every run, newest first, refreshed every few seconds. */
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
      cell: (run) => <span className="RunsPage-name">{run.name}</span>
    },
    {
      id: 'status',
      header: 'Status',
      cell: (run) => (
        <span className="RunsPage-status">
          <StatusBadge status={run.status} />
          {run.demo && <StatusBadge status="demo" label="Demo" />}
        </span>
      )
    },
    { id: 'scenarios', header: 'Scenarios', align: 'right', cell: (run) => run.scenarios.length },
    { id: 'created', header: 'Created', cell: (run) => formatDateTime(run.created) },
    { id: 'duration', header: 'Duration', align: 'right', cell: (run) => duration(run, now) }
  ]

  return (
    <div className="page">
      <PageHeader
        title="Runs"
        actions={
          <Link to="/runs/new" className="button primary">
            <Plus size={16} />
            New run
          </Link>
        }
      />
      <Alert>{error}</Alert>
      {runs && runs.length === 0 && (
        <Empty
          title="No runs yet"
          action={
            <Link to="/runs/new" className="button primary">
              Start a run
            </Link>
          }
        >
          A run repairs one or more scenarios and shows its progress here as it happens.
        </Empty>
      )}
      {runs && runs.length > 0 && (
        <DataTable
          columns={columns}
          rows={runs}
          rowKey={(run) => run.id}
          onRowClick={(run) => navigate(`/runs/${run.id}`)}
        />
      )}
    </div>
  )
}
