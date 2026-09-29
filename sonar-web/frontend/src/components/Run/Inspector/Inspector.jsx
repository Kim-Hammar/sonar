import { ChevronRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import CodeBlock, { prettyJson } from '../../Common/CodeBlock/CodeBlock'
import DataTable from '../../Common/DataTable/DataTable'
import DiffView from '../../Common/DiffView/DiffView'
import Disclosure from '../../Common/Disclosure/Disclosure'
import { describeEvent, formatCompact, formatNumber, STOP_LABELS } from '../../Common/format'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import './Inspector.css'

function KeyList({ keys }) {
  if (!keys || keys.length === 0) {
    return <div className="muted">None</div>
  }
  return <CodeBlock text={keys.join('\n')} maxHeight={260} />
}

function Traces({ evidence }) {
  if (!evidence || evidence.traces.length === 0) {
    return <div className="muted">No traces</div>
  }
  return (
    <div className="Inspector-traces">
      {evidence.traces.map((trace) => (
        <div key={trace.predicate} className="Inspector-trace">
          <div className="mono">{trace.predicate}</div>
          {trace.flows.map((flow, index) => (
            <div key={index} className="Inspector-flow">
              {flow.hops.map((hop, hopIndex) => (
                <span key={hopIndex} className="Inspector-hop">
                  {hopIndex > 0 && <ChevronRight size={12} className="muted" />}
                  <span className="mono">{hop}</span>
                </span>
              ))}
              <StatusBadge
                status={flow.disposition === 'accepted' ? 'fixed' : 'unresolved'}
                label={flow.disposition}
              />
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

function Verdict({ verdict }) {
  return (
    <>
      <Disclosure title="Unresolved" meta={formatNumber(verdict.unresolved.length)}>
        <KeyList keys={verdict.unresolved} />
      </Disclosure>
      <Disclosure title="Fixed" meta={formatNumber(verdict.fixed.length)}>
        <KeyList keys={verdict.fixed} />
      </Disclosure>
      <Disclosure title="Side effects" meta={formatNumber(verdict.sideEffects.length)}>
        <KeyList keys={verdict.sideEffects} />
      </Disclosure>
    </>
  )
}

function Decision({ detail }) {
  const answer = detail.answer
  return (
    <>
      <h3 className="Inspector-heading">Answer</h3>
      {answer && answer.regions && (
        <DataTable
          rows={[...answer.regions, { id: 'none of these', probability: answer.none }]}
          rowKey={(row) => row.id}
          columns={[
            { id: 'id', header: 'Region', cell: (row) => <span className="mono">{row.id}</span> },
            {
              id: 'p',
              header: 'Probability',
              align: 'right',
              cell: (row) => row.probability.toFixed(3)
            }
          ]}
        />
      )}
      {answer && answer.order && <KeyList keys={answer.order} />}
      {!answer && <Alert tone="warn">{detail.reason}</Alert>}
      <h3 className="Inspector-heading">Context</h3>
      {detail.context.map((section) => (
        <Disclosure
          key={section.title}
          title={section.title}
          meta={`${formatNumber(section.body.length)} characters`}
        >
          <CodeBlock text={section.body} />
        </Disclosure>
      ))}
      <h3 className="Inspector-heading">Exchanges with the model</h3>
      {detail.exchanges.length === 0 && <div className="muted">Nothing was sent</div>}
      {detail.exchanges.map((exchange, index) => (
        <Disclosure
          key={index}
          title={`Request ${index + 1}`}
          meta={`HTTP ${exchange.status}, ${formatNumber(exchange.latencyMillis)} ms${exchange.cached ? ', cached' : ''}`}
        >
          <h4 className="Inspector-subheading">Request</h4>
          <CodeBlock text={prettyJson(exchange.request)} />
          <h4 className="Inspector-subheading">Reply</h4>
          <CodeBlock text={prettyJson(exchange.reply)} />
        </Disclosure>
      ))}
    </>
  )
}

function Candidate({ detail }) {
  return (
    <>
      <div className="Inspector-line">
        <StatusBadge status={detail.change} />
        <span className="muted">
          in <span className="mono">{detail.region}</span>, {detail.hypothesis.support} values
        </span>
      </div>
      <h3 className="Inspector-heading">Specifications that changed</h3>
      <DataTable
        rows={detail.changes}
        rowKey={(row) => row.key}
        empty="No specification changed"
        columns={[
          {
            id: 'key',
            header: 'Specification',
            cell: (row) => <span className="mono">{row.key}</span>
          },
          { id: 'before', header: 'Before', cell: (row) => <StatusBadge status={row.before} /> },
          { id: 'after', header: 'After', cell: (row) => <StatusBadge status={row.after} /> }
        ]}
      />
      <h3 className="Inspector-heading">Edits</h3>
      <DiffView edits={detail.hypothesis.edits} />
      <h3 className="Inspector-heading">Evidence</h3>
      <Disclosure title="Forwarding traces" meta={formatNumber(detail.evidence.traces.length)}>
        <Traces evidence={detail.evidence} />
      </Disclosure>
    </>
  )
}

function Body({ detail }) {
  switch (detail.type) {
    case 'decided':
      return <Decision detail={detail} />
    case 'tried':
      return <Candidate detail={detail} />
    case 'adopted':
      return (
        <>
          <h3 className="Inspector-heading">Edits</h3>
          <DiffView edits={detail.hypotheses.flatMap((hypothesis) => hypothesis.edits)} />
          <h3 className="Inspector-heading">Verdict after adoption</h3>
          <Verdict verdict={detail.verdict} />
          <Disclosure title="Forwarding traces" meta={formatNumber(detail.evidence.traces.length)}>
            <Traces evidence={detail.evidence} />
          </Disclosure>
        </>
      )
    case 'baseline':
      return (
        <>
          <Verdict verdict={detail.verdict} />
          <Disclosure title="Forwarding traces" meta={formatNumber(detail.evidence.traces.length)}>
            <Traces evidence={detail.evidence} />
          </Disclosure>
        </>
      )
    case 'space-built':
      return (
        <Disclosure title="Regions" meta={formatNumber(detail.regions.length)}>
          <DataTable
            rows={detail.regions}
            rowKey={(row) => row.id}
            columns={[
              { id: 'id', header: 'Region', cell: (row) => <span className="mono">{row.id}</span> },
              { id: 'kind', header: 'Kind', cell: (row) => row.kind },
              { id: 'routers', header: 'Routers', cell: (row) => row.routers.join(', ') },
              {
                id: 'bound',
                header: 'Candidates',
                align: 'right',
                cell: (row) => formatNumber(row.bound)
              }
            ]}
          />
        </Disclosure>
      )
    case 'loaded':
      return (
        <p className="muted">
          The Scenarios tab shows this scenario&apos;s specifications, topology and configurations.
        </p>
      )
    case 'finished':
      return (
        <>
          <div className="Inspector-line">
            <StatusBadge
              status={detail.stop === 'solved' ? 'solved' : 'stopped'}
              label={STOP_LABELS[detail.stop]}
            />
            {detail.resource && <span className="muted">{detail.resource}</span>}
          </div>
          <Verdict verdict={detail.verdict} />
        </>
      )
    case 'failed':
      return <Alert tone="warn">{detail.reason}</Alert>
    default:
      return null
  }
}

/** The details of one event, loaded only when the inspector opens; key it by event to reload. */
export default function Inspector({ runId, event, load = api.event }) {
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let active = true
    load(runId, event.seq).then(
      (body) => active && setDetail(body),
      (failure) => active && setError(failure.message)
    )
    return () => {
      active = false
    }
  }, [runId, event.seq, load])

  const usage = event.usage || {}
  return (
    <div className="Inspector">
      <p className="Inspector-summary">{describeEvent(event)}</p>
      <div className="Inspector-usage muted">
        Scenario totals at this point: {formatNumber(usage.verifications)} verifications,{' '}
        {formatNumber(usage.decisions)} decisions, {formatCompact(usage.inputTokens)} input and{' '}
        {formatCompact(usage.outputTokens)} output tokens
      </div>
      <Alert>{error}</Alert>
      {!detail && !error && <div className="muted">Loading…</div>}
      {detail && <Body detail={detail} />}
    </div>
  )
}
