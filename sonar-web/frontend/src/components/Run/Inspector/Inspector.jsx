import { useEffect, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import CodeBlock, { prettyJson } from '../../Common/CodeBlock/CodeBlock'
import DataTable from '../../Common/DataTable/DataTable'
import DiffView from '../../Common/DiffView/DiffView'
import Disclosure from '../../Common/Disclosure/Disclosure'
import FlowTrace from '../../Common/FlowTrace/FlowTrace'
import { describeEvent, formatCompact, formatNumber, STOP_LABELS } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import './Inspector.css'

const hints = HINTS.inspector

function Heading({ hint, children }) {
  return (
    <h3 className="Inspector-heading">
      <Hint {...hint}>{children}</Hint>
    </h3>
  )
}

function TracesSection({ evidence }) {
  return (
    <Disclosure
      title="Forwarding traces"
      hint={hints.traces}
      meta={formatNumber(evidence.traces.length)}
    >
      <Traces evidence={evidence} />
    </Disclosure>
  )
}

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
          <FlowTrace flows={trace.flows} />
        </div>
      ))}
    </div>
  )
}

function Verdict({ verdict }) {
  return (
    <>
      <Disclosure
        title="Unresolved"
        hint={hints.unresolved}
        meta={formatNumber(verdict.unresolved.length)}
      >
        <KeyList keys={verdict.unresolved} />
      </Disclosure>
      <Disclosure title="Fixed" hint={hints.fixed} meta={formatNumber(verdict.fixed.length)}>
        <KeyList keys={verdict.fixed} />
      </Disclosure>
      <Disclosure
        title="Side effects"
        hint={hints.sideEffects}
        meta={formatNumber(verdict.sideEffects.length)}
      >
        <KeyList keys={verdict.sideEffects} />
      </Disclosure>
    </>
  )
}

function Decision({ detail }) {
  const answer = detail.answer
  return (
    <>
      <Heading hint={hints.answer}>Answer</Heading>
      {answer && answer.regions && (
        <DataTable
          rows={[...answer.regions, { id: 'none of these', probability: answer.none }]}
          rowKey={(row) => row.id}
          columns={[
            {
              id: 'id',
              header: 'Region',
              hint: hints.region,
              cell: (row) => <span className="mono">{row.id}</span>
            },
            {
              id: 'p',
              header: 'Probability',
              hint: hints.probability,
              align: 'right',
              cell: (row) => row.probability.toFixed(3)
            }
          ]}
        />
      )}
      {answer && answer.order && (
        <DataTable
          rows={answer.order}
          rowKey={(row) => row.id}
          columns={[
            {
              id: 'id',
              header: 'Candidate',
              hint: hints.candidate,
              cell: (row) => <span className="mono">{row.id.slice(0, 12)}</span>
            },
            {
              id: 'p',
              header: 'Probability',
              hint: hints.rankProbability,
              align: 'right',
              cell: (row) => row.probability.toFixed(3)
            }
          ]}
        />
      )}
      {!answer && <Alert tone="warn">{detail.reason}</Alert>}
      <Heading hint={hints.options}>Options</Heading>
      <Disclosure title="Options offered" meta={`${detail.options.length} options`}>
        <DataTable
          rows={detail.options}
          rowKey={(row) => row.id}
          columns={[
            {
              id: 'id',
              header: 'Option',
              cell: (row) => <span className="mono">{row.id}</span>
            },
            { id: 'description', header: 'Description', cell: (row) => row.description }
          ]}
        />
      </Disclosure>
      <Heading hint={hints.context}>Context</Heading>
      {detail.context.map((section) => (
        <Disclosure
          key={section.title}
          title={section.title}
          meta={`${formatNumber(section.body.length)} characters`}
        >
          <CodeBlock text={section.body} />
        </Disclosure>
      ))}
      <Heading hint={hints.exchanges}>Exchanges with the model</Heading>
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
        <Hint label="Effect" {...hints.effect} />
        <span className="muted">
          in <span className="mono">{detail.region}</span>, {detail.hypothesis.support} values
        </span>
        <Hint label="Values" {...hints.support} />
      </div>
      <Heading hint={hints.changed}>Specifications that changed</Heading>
      <DataTable
        rows={detail.changes}
        rowKey={(row) => row.key}
        empty="No specification changed"
        columns={[
          {
            id: 'key',
            header: 'Specification',
            hint: hints.specification,
            cell: (row) => <span className="mono">{row.key}</span>
          },
          {
            id: 'before',
            header: 'Before',
            hint: hints.before,
            cell: (row) => <StatusBadge status={row.before} />
          },
          {
            id: 'after',
            header: 'After',
            hint: hints.after,
            cell: (row) => <StatusBadge status={row.after} />
          }
        ]}
      />
      <Heading hint={hints.edits}>Edits</Heading>
      <DiffView edits={detail.hypothesis.edits} />
      <Heading hint={hints.evidence}>Evidence</Heading>
      <TracesSection evidence={detail.evidence} />
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
          <Heading hint={hints.edits}>Edits</Heading>
          <DiffView edits={detail.hypotheses.flatMap((hypothesis) => hypothesis.edits)} />
          <Heading hint={hints.verdict}>Verdict after adoption</Heading>
          <Verdict verdict={detail.verdict} />
          <TracesSection evidence={detail.evidence} />
        </>
      )
    case 'baseline':
      return (
        <>
          <Verdict verdict={detail.verdict} />
          <TracesSection evidence={detail.evidence} />
        </>
      )
    case 'space-built':
      return (
        <Disclosure title="Regions" hint={hints.regions} meta={formatNumber(detail.regions.length)}>
          <DataTable
            rows={detail.regions}
            rowKey={(row) => row.id}
            columns={[
              {
                id: 'id',
                header: 'Region',
                hint: hints.regionId,
                cell: (row) => <span className="mono">{row.id}</span>
              },
              { id: 'kind', header: 'Kind', hint: hints.kind, cell: (row) => row.kind },
              {
                id: 'routers',
                header: 'Routers',
                hint: hints.routers,
                cell: (row) => row.routers.join(', ')
              },
              {
                id: 'bound',
                header: 'Candidates',
                hint: hints.bound,
                align: 'right',
                cell: (row) => formatNumber(row.bound)
              },
              {
                id: 'prior',
                header: 'Prior',
                hint: HINTS.search.prior,
                align: 'right',
                cell: (row) => (row.prior === undefined ? '–' : `${(row.prior * 100).toFixed(1)}%`)
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
            <Hint label="Stop reason" {...hints.stop} />
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
