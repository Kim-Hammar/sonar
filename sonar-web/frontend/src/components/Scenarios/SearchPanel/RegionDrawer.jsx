import { useEffect, useRef, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import CodeBlock from '../../Common/CodeBlock/CodeBlock'
import DataTable from '../../Common/DataTable/DataTable'
import DiffView from '../../Common/DiffView/DiffView'
import Disclosure from '../../Common/Disclosure/Disclosure'
import Drawer from '../../Common/Drawer/Drawer'
import { formatDuration, formatNumber } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import StatusBadge from '../../Common/StatusBadge/StatusBadge'
import { factorHint, formatPercent } from './factors'
import './RegionDrawer.css'

const hints = HINTS.search

function active(expansion) {
  return expansion.status === 'queued' || expansion.status === 'running'
}

function Strength({ strength }) {
  return (
    <Hint label={`${strength} rule`} {...hints.strength}>
      <StatusBadge status={strength} />
    </Hint>
  )
}

function Section({ hint, title, children }) {
  return (
    <section className="RegionDrawer-section">
      <h3 className="RegionDrawer-heading">
        <Hint {...hint}>{title}</Hint>
      </h3>
      {children}
    </section>
  )
}

function Factors({ region, factors }) {
  const rows = factors.map((name) => ({ name, weight: region.factors[name] }))
  return (
    <DataTable
      columns={[
        {
          id: 'name',
          header: 'Factor',
          hint: hints.factors,
          cell: (row) => <Hint {...factorHint(row.name)}>{row.name}</Hint>
        },
        {
          id: 'weight',
          header: 'Weight',
          hint: hints.weight,
          align: 'right',
          cell: (row) => row.weight.toFixed(3)
        }
      ]}
      rows={rows}
      rowKey={(row) => row.name}
    />
  )
}

function Specifications({ rows }) {
  return (
    <DataTable
      columns={[
        { id: 'type', header: 'Type', cell: (row) => row.type.replace('_', ' ') },
        {
          id: 'source',
          header: 'Source',
          cell: (row) => <span className="mono">{row.source}</span>
        },
        {
          id: 'destination',
          header: 'Destination',
          cell: (row) => (
            <span className="mono">
              {row.destination}
              {row.waypoint ? ` via ${row.waypoint}` : ''}
            </span>
          )
        }
      ]}
      rows={rows}
      rowKey={(row) => row.key}
      empty="No specification"
    />
  )
}

function Site({ site }) {
  const text = site.statements
    .flatMap((statement) =>
      statement.lines.map(
        (line, index) => `${String(statement.first + index).padStart(5)}  ${line}`
      )
    )
    .join('\n')
  return (
    <>
      <div className="RegionDrawer-facts">
        <span className="mono">{site.ref}</span>
        {Object.entries(site.attributes).map(([name, values]) => (
          <span key={name} className="RegionDrawer-attribute">
            <span className="muted">{name}</span> <span className="mono">{values.join(' ')}</span>
          </span>
        ))}
      </div>
      {text ? <CodeBlock text={text} maxHeight={260} /> : <div className="muted">No lines</div>}
    </>
  )
}

function Relation({ relation }) {
  return (
    <>
      <div className="RegionDrawer-facts">
        <span className="mono">{relation.name}</span>
        {Object.entries(relation.roles).map(([role, ref]) => (
          <span key={role} className="RegionDrawer-attribute">
            <span className="muted">{role}</span> <span className="mono">{ref}</span>
          </span>
        ))}
      </div>
      {relation.violated.length === 0 ? (
        <div className="muted">No violated requirement</div>
      ) : (
        <ul className="RegionDrawer-list">
          {relation.violated.map((requirement, index) => (
            <li key={index}>
              <Strength strength={requirement.strength} />{' '}
              <span className="mono">{requirement.text}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function Incidence({ incidence }) {
  return (
    <>
      <div className="RegionDrawer-facts">
        <span className="RegionDrawer-attribute">
          <span className="muted">reflector</span>{' '}
          <span className="mono">{incidence.reflector}</span>
        </span>
        <span className="RegionDrawer-attribute">
          <span className="muted">clients</span>{' '}
          <span className="mono">{incidence.clients.join(', ')}</span>
        </span>
      </div>
      <DataTable
        columns={[
          {
            id: 'source',
            header: 'From',
            cell: (row) => <span className="mono">{row.source}</span>
          },
          { id: 'target', header: 'To', cell: (row) => <span className="mono">{row.target}</span> },
          { id: 'peer', header: 'Peer', cell: (row) => <span className="mono">{row.peer}</span> },
          {
            id: 'groups',
            header: 'Peer groups',
            cell: (row) => <span className="mono">{row.groups.map((g) => g.name).join(', ')}</span>
          }
        ]}
        rows={incidence.directions}
        rowKey={(row) => `${row.source}>${row.target}`}
        empty="No session to complete"
      />
    </>
  )
}

function Transfer({ transfer }) {
  return (
    <div className="RegionDrawer-facts">
      <span className="RegionDrawer-attribute">
        <span className="muted">policy</span> <span className="mono">{transfer.policy}</span>
      </span>
      <span className="RegionDrawer-attribute">
        <span className="muted">blockers</span>{' '}
        <span className="mono">{transfer.blockers.join(', ') || 'none'}</span>
      </span>
    </div>
  )
}

function Facts({ region }) {
  if (region.site) {
    return (
      <Section hint={hints.site} title="Configuration object">
        <Site site={region.site} />
      </Section>
    )
  }
  if (region.relation) {
    return (
      <Section hint={hints.relation} title="Relation">
        <Relation relation={region.relation} />
      </Section>
    )
  }
  if (region.incidence) {
    return (
      <Section hint={hints.incidence} title="Route reflection">
        <Incidence incidence={region.incidence} />
      </Section>
    )
  }
  if (region.transfer) {
    return (
      <Section hint={hints.transfer} title="Protocol transfer">
        <Transfer transfer={region.transfer} />
      </Section>
    )
  }
  return null
}

function Candidate({ candidate, index }) {
  return (
    <Disclosure
      title={`${index + 1}. ${candidate.abbreviated}`}
      meta={`${formatNumber(candidate.cost)} lines, ${candidate.support}, ${candidate.routers.join(', ')}`}
    >
      <div className="RegionDrawer-candidate">
        <StatusBadge status={candidate.support} />
        {candidate.restores.length > 0 && (
          <ul className="RegionDrawer-list">
            {candidate.restores.map((restored, restoredIndex) => (
              <li key={restoredIndex}>
                <span className="muted">{restored.relation}</span>{' '}
                <Strength strength={restored.strength} />{' '}
                <span className="mono">{restored.text}</span>
              </li>
            ))}
          </ul>
        )}
        <DiffView edits={candidate.edits} />
      </div>
    </Disclosure>
  )
}

function Candidates({ region, generating, onGenerate }) {
  const expansion = region.expansion
  const status = expansion.status
  const canGenerate = status === 'deferred' || status === 'failed'
  return (
    <Section hint={hints.generate} title="Candidate repairs">
      <div className="RegionDrawer-generate">
        <Hint label="Generation" {...hints.expansion}>
          <StatusBadge status={status} />
        </Hint>
        {expansion.elapsedMillis !== undefined && (
          <span className="muted">{formatDuration(expansion.elapsedMillis)}</span>
        )}
        {canGenerate && (
          <button
            type="button"
            className="button primary"
            onClick={onGenerate}
            disabled={generating}
          >
            {status === 'failed' ? 'Generate again' : 'Generate candidates'}
          </button>
        )}
      </div>
      {expansion.error && <Alert>{expansion.error}</Alert>}
      {status === 'dropped' && (
        <p className="muted">
          No candidate passed the checks, so the agent drops this region from the search space.
        </p>
      )}
      {status === 'admitted' && (
        <div className="RegionDrawer-candidates">
          {(region.candidates || []).map((candidate, index) => (
            <Candidate key={candidate.id} candidate={candidate} index={index} />
          ))}
        </div>
      )}
    </Section>
  )
}

/**
 * The drawer of one region: its prior with each factor's weight, the specifications it aims at,
 * the facts of its kind, and its candidate repairs, generated on request. `onSettled` is called
 * when a generation ends, so the table behind the drawer can refresh.
 */
export default function RegionDrawer({
  scenario,
  percent,
  id,
  factors,
  onClose,
  onSettled,
  client,
  pollMillis
}) {
  const [region, setRegion] = useState(null)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [generating, setGenerating] = useState(false)
  const pending = useRef(false)

  useEffect(() => {
    let cancelled = false
    let timer = null
    const poll = () =>
      client.searchRegion(scenario, percent, id).then(
        (body) => {
          if (cancelled) {
            return
          }
          setRegion(body.region)
          if (active(body.region.expansion)) {
            pending.current = true
            timer = setTimeout(poll, pollMillis)
          } else if (pending.current) {
            pending.current = false
            onSettled()
          }
        },
        (failure) => !cancelled && setError(failure.message)
      )
    poll()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [scenario, percent, id, client, attempt, pollMillis, onSettled])

  const generate = () => {
    setGenerating(true)
    setError(null)
    client
      .expandRegion(scenario, percent, id)
      .then(
        (body) => {
          pending.current = true
          setRegion(body.region)
          setAttempt((count) => count + 1)
        },
        (failure) => setError(failure.message)
      )
      .finally(() => setGenerating(false))
  }

  return (
    <Drawer title={id} subtitle="Region of the search space" onClose={onClose}>
      <Alert>{error}</Alert>
      {region ? (
        <>
          <div className="RegionDrawer-summary">
            <StatusBadge status={region.kind} />
            <span className="mono">{region.routers.join(', ')}</span>
            {region.prior !== undefined && (
              <span>
                <Hint {...hints.prior}>Prior</Hint> {formatPercent(region.prior)}
              </span>
            )}
            {region.partition && (
              <span>
                <Hint {...hints.partition}>Partition</Hint>{' '}
                <span className="mono">{region.partition}</span>
              </span>
            )}
          </div>
          {region.factors && (
            <Section hint={hints.factors} title="Prior factors">
              <Factors region={region} factors={factors} />
            </Section>
          )}
          <Facts region={region} />
          <Candidates region={region} generating={generating} onGenerate={generate} />
          {region.specifications && (
            <div className="RegionDrawer-section">
              <Disclosure
                title="Target specifications"
                hint={hints.specifications}
                meta={formatNumber(region.specifications.length)}
              >
                <Specifications rows={region.specifications} />
              </Disclosure>
            </div>
          )}
        </>
      ) : (
        !error && <div className="muted">Loading…</div>
      )}
    </Drawer>
  )
}
