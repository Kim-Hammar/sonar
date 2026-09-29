import { Square } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import useRunEvents from '../../../hooks/useRunEvents'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import { FINAL_STATUSES, RUN_REFRESH } from '../../Common/constants'
import Drawer from '../../Common/Drawer/Drawer'
import { EVENT_LABELS, formatDateTime, formatDuration } from '../../Common/format'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import Tabs from '../../Common/Tabs/Tabs'
import PageHeader from '../../Layout/PageHeader/PageHeader'
import ActivityList from '../../Run/ActivityList/ActivityList'
import Inspector from '../../Run/Inspector/Inspector'
import KpiStrip from '../../Run/KpiStrip/KpiStrip'
import RunCharts from '../../Run/RunCharts/RunCharts'
import ScenarioInspector from '../../Run/ScenarioInspector/ScenarioInspector'
import ScenarioTable from '../../Run/ScenarioTable/ScenarioTable'
import './RunPage.css'

/**
 * One run: live headline numbers and charts by default; the per-scenario table, the event list
 * and every inspector only when asked for.
 */
export default function RunPage() {
  const { runId } = useParams()
  const [params, setParams] = useSearchParams()
  const [run, setRun] = useState(null)
  const [error, setError] = useState(null)
  const [now, setNow] = useState(() => Date.now())
  const [chartScenario, setChartScenario] = useState('all')
  const state = useRunEvents(runId)
  const tab = params.get('tab') || 'overview'
  const openEvent = params.get('event')
  const openScenario = params.get('scenario')

  const refresh = useCallback(
    () =>
      api.run(runId).then(
        (body) => {
          setRun(body.run)
          setNow(Date.now())
        },
        (failure) => setError(failure.message)
      ),
    [runId]
  )

  useEffect(() => {
    refresh()
  }, [refresh, state.ended])

  useEffect(() => {
    if (!run || FINAL_STATUSES.has(run.status)) {
      return undefined
    }
    const timer = setInterval(refresh, RUN_REFRESH)
    return () => clearInterval(timer)
  }, [run, refresh])

  const show = (changes) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) {
        next.delete(key)
      } else {
        next.set(key, value)
      }
    }
    setParams(next)
  }

  const cancel = async () => {
    try {
      const body = await api.cancelRun(runId)
      setRun(body.run)
    } catch (failure) {
      setError(failure.message)
    }
  }

  if (!run) {
    return (
      <div className="page">
        <Alert>{error}</Alert>
        {!error && <div className="muted">Loading…</div>}
      </div>
    )
  }

  const selectedEvent = openEvent && state.events.find((event) => String(event.seq) === openEvent)
  const active = !FINAL_STATUSES.has(run.status)
  const elapsed = run.started ? (run.finished || now) - run.started : null

  return (
    <div className="page RunPage">
      <PageHeader
        eyebrow="Run"
        title={run.name}
        meta={
          <>
            <StatusBadge status={run.status} />
            {run.demo && <StatusBadge status="demo" label="Demo" />}
            <span>created {formatDateTime(run.created)}</span>
            {elapsed !== null && <span>{formatDuration(elapsed)}</span>}
            <span>{run.settings.specificationPercent}% of specifications</span>
            {active && state.connection === 'reconnecting' && <span>reconnecting…</span>}
          </>
        }
        actions={
          active && (
            <button type="button" className="button" onClick={cancel}>
              <Square size={14} />
              Cancel run
            </button>
          )
        }
      />
      <Alert>{error || state.error}</Alert>
      {run.error && <Alert tone="warn">{run.error}</Alert>}
      <Tabs
        tabs={[
          { id: 'overview', label: 'Overview' },
          { id: 'scenarios', label: 'Scenarios', count: run.scenarios.length },
          { id: 'activity', label: 'Activity', count: state.events.length }
        ]}
        active={tab}
        onChange={(id) => show({ tab: id, event: null, scenario: null })}
      />
      {tab === 'overview' && (
        <>
          <KpiStrip run={run} state={state} now={now} />
          <RunCharts state={state} scenario={chartScenario} onScenario={setChartScenario} />
        </>
      )}
      {tab === 'scenarios' && (
        <ScenarioTable run={run} state={state} onOpen={(name) => show({ scenario: name })} />
      )}
      {tab === 'activity' && (
        <ActivityList
          events={state.events}
          scenarios={run.scenarios}
          onOpen={(seq) => show({ event: String(seq) })}
        />
      )}
      {selectedEvent && (
        <Drawer
          title={EVENT_LABELS[selectedEvent.type] || selectedEvent.type}
          subtitle={`${selectedEvent.scenario}, step ${selectedEvent.step}`}
          onClose={() => show({ event: null })}
        >
          <Inspector key={selectedEvent.seq} runId={runId} event={selectedEvent} />
        </Drawer>
      )}
      {openScenario && (
        <Drawer
          title={openScenario}
          subtitle="Scenario of this run"
          onClose={() => show({ scenario: null })}
        >
          <ScenarioInspector
            key={openScenario}
            runId={runId}
            scenario={openScenario}
            state={state}
          />
        </Drawer>
      )}
    </div>
  )
}
