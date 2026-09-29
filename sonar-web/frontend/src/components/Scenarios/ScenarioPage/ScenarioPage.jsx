import { useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import FieldLabel from '../../Common/FieldLabel/FieldLabel'
import { HINTS } from '../../Common/hints'
import Tabs from '../../Common/Tabs/Tabs'
import PageHeader from '../../Layout/PageHeader/PageHeader'
import ConfigViewer from '../ConfigViewer/ConfigViewer'
import SpecificationTable from '../SpecificationTable/SpecificationTable'
import TopologyGraph from '../TopologyGraph/TopologyGraph'
import './ScenarioPage.css'

/** The contents of one scenario's page, created afresh for each scenario. */
function ScenarioView({ name }) {
  const [params, setParams] = useSearchParams()
  const [summary, setSummary] = useState(null)
  const [topology, setTopology] = useState(null)
  const [specifications, setSpecifications] = useState({ percent: null, rows: null })
  const [chosenPercent, setChosenPercent] = useState(null)
  const [router, setRouter] = useState(null)
  const [error, setError] = useState(null)
  const tab = params.get('tab') || 'topology'
  const available = summary ? summary.specificationPercents : []
  const percent = chosenPercent ?? (available.includes(10) ? 10 : available[0])

  useEffect(() => {
    api.scenario(name).then(setSummary, (failure) => setError(failure.message))
    api.topology(name).then(setTopology, (failure) => setError(failure.message))
  }, [name])

  useEffect(() => {
    if (tab === 'specifications' && percent) {
      api.specifications(name, percent).then(
        (body) => setSpecifications({ percent, rows: body.specifications }),
        (failure) => setError(failure.message)
      )
    }
  }, [tab, name, percent])

  const rows = specifications.percent === percent ? specifications.rows : null

  return (
    <div className="page ScenarioPage">
      <PageHeader
        eyebrow="Scenario"
        title={name}
        meta={
          summary && (
            <span>
              {summary.routers} routers, {summary.links} links
            </span>
          )
        }
      />
      <Alert>{error}</Alert>
      <Tabs
        tabs={[
          { id: 'topology', label: 'Topology' },
          { id: 'specifications', label: 'Specifications' },
          { id: 'configurations', label: 'Configurations' }
        ]}
        active={tab}
        onChange={(id) => setParams({ tab: id })}
      />
      {tab === 'topology' && topology && (
        <>
          <TopologyGraph
            routers={topology.routers}
            links={topology.links}
            selected={router}
            onSelect={setRouter}
          />
          {router && (
            <p className="muted ScenarioPage-hint">
              Selected <span className="mono">{router}</span>.
              <button
                type="button"
                className="button quiet"
                onClick={() => setParams({ tab: 'configurations' })}
              >
                Show its configuration
              </button>
            </p>
          )}
        </>
      )}
      {tab === 'specifications' && summary && (
        <>
          <div className="ScenarioPage-percent">
            <FieldLabel htmlFor="scenario-percent" hint={HINTS.scenarios.specificationFile}>
              Specification file
            </FieldLabel>
            <select
              id="scenario-percent"
              className="input"
              value={percent || ''}
              onChange={(event) => setChosenPercent(Number(event.target.value))}
            >
              {available.map((value) => (
                <option key={value} value={value}>
                  {value}%
                </option>
              ))}
            </select>
          </div>
          {rows ? <SpecificationTable rows={rows} /> : <div className="muted">Loading…</div>}
        </>
      )}
      {tab === 'configurations' && topology && (
        <ConfigViewer
          scenario={name}
          routers={topology.routers.map((entry) => entry.name)}
          initial={router}
        />
      )}
    </div>
  )
}

/** One scenario of the dataset: its topology, specifications and configurations. */
export default function ScenarioPage() {
  const { name } = useParams()
  return <ScenarioView key={name} name={name} />
}
