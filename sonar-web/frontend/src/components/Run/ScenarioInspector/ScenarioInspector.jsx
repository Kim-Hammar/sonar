import { useEffect, useMemo, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import Tabs from '../../Common/Tabs/Tabs'
import ConfigViewer from '../../Scenarios/ConfigViewer/ConfigViewer'
import SpecificationTable from '../../Scenarios/SpecificationTable/SpecificationTable'
import TopologyGraph from '../../Scenarios/TopologyGraph/TopologyGraph'

const STATE_TYPES = new Set(['baseline', 'adopted', 'finished'])

function statuses(verdict) {
  const map = new Map()
  verdict.unresolved.forEach((key) => map.set(key, 'unresolved'))
  verdict.fixed.forEach((key) => map.set(key, 'fixed'))
  verdict.sideEffects.forEach((key) => map.set(key, 'side-effect'))
  return map
}

/** The specifications, topology and configurations of one scenario of a run, at its latest state. */
export default function ScenarioInspector({ runId, scenario, state }) {
  const [tab, setTab] = useState('specifications')
  const [loaded, setLoaded] = useState(null)
  const [verdict, setVerdict] = useState(null)
  const [topology, setTopology] = useState(null)
  const [error, setError] = useState(null)
  const loadedEvent = state.events.find(
    (event) => event.scenario === scenario && event.type === 'loaded'
  )
  const latest = useMemo(() => {
    for (let index = state.events.length - 1; index >= 0; index--) {
      const event = state.events[index]
      if (event.scenario === scenario && STATE_TYPES.has(event.type)) {
        return event
      }
    }
    return null
  }, [state.events, scenario])
  const changed = new Set((state.scenarios[scenario] && state.scenarios[scenario].changed) || [])

  useEffect(() => {
    if (loadedEvent) {
      api.event(runId, loadedEvent.seq).then(setLoaded, (failure) => setError(failure.message))
    }
  }, [runId, loadedEvent])

  useEffect(() => {
    if (latest) {
      api.event(runId, latest.seq).then(
        (detail) => setVerdict(statuses(detail.verdict)),
        (failure) => setError(failure.message)
      )
    }
  }, [runId, latest])

  useEffect(() => {
    if (tab === 'topology' && !topology) {
      api.topology(scenario).then(setTopology, (failure) => setError(failure.message))
    }
  }, [tab, topology, scenario])

  if (!loadedEvent) {
    return <p className="muted">The scenario has not started yet.</p>
  }
  return (
    <div>
      <Tabs
        tabs={[
          { id: 'specifications', label: 'Specifications' },
          { id: 'topology', label: 'Topology' },
          { id: 'configurations', label: 'Configurations' }
        ]}
        active={tab}
        onChange={setTab}
      />
      <Alert>{error}</Alert>
      {tab === 'specifications' && loaded && (
        <SpecificationTable rows={loaded.specifications} statuses={verdict} />
      )}
      {tab === 'topology' && topology && (
        <TopologyGraph
          routers={topology.routers}
          links={topology.links}
          highlighted={changed}
          height={440}
        />
      )}
      {tab === 'configurations' && loaded && (
        <ConfigViewer scenario={scenario} routers={loaded.topology.routers} highlighted={changed} />
      )}
      {changed.size > 0 && tab !== 'specifications' && (
        <p className="muted">
          Highlighted routers were changed by adopted repairs; configurations show the original
          text.
        </p>
      )}
    </div>
  )
}
