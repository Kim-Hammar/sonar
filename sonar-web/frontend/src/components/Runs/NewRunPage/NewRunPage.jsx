import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import { SPECIFICATION_PERCENTS } from '../../Common/constants'
import Disclosure from '../../Common/Disclosure/Disclosure'
import FieldLabel from '../../Common/FieldLabel/FieldLabel'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import { useInfo } from '../../Common/info'
import PageHeader from '../../Layout/PageHeader/PageHeader'
import ScenarioPicker from '../ScenarioPicker/ScenarioPicker'
import './NewRunPage.css'

const PARALLELISM = [
  ['verificationWorkers', 'Verification workers'],
  ['decisionConcurrency', 'Decision requests in flight'],
  ['generationParallelism', 'Generation threads'],
  ['scenarioParallelism', 'Scenarios at a time'],
  ['prefetchDecisions', 'Decisions prefetched']
]

const BUDGETS = [
  ['verifications', 'Verifications'],
  ['decisions', 'Decisions'],
  ['input_tokens', 'Input tokens']
]

/** Builds the request body of a new run from the form values. */
export function runRequest(form) {
  const limits = {}
  for (const [key] of BUDGETS) {
    if (form.budgets[key] !== '') {
      limits[key] = Number(form.budgets[key])
    }
  }
  return {
    name: form.name.trim(),
    scenarios: [...form.scenarios].sort(),
    settings: {
      specificationPercent: Number(form.percent),
      ...Object.fromEntries(PARALLELISM.map(([key]) => [key, Number(form.parallelism[key])])),
      limits,
      timeLimitSeconds: form.minutes === '' ? null : Math.round(Number(form.minutes) * 60),
      seed: Number(form.seed)
    }
  }
}

/** Returns what is wrong with the form, or null. */
export function validate(form, limits = {}) {
  if (form.scenarios.size === 0) {
    return 'Choose at least one scenario.'
  }
  for (const [key, label] of PARALLELISM) {
    const value = Number(form.parallelism[key])
    const minimum = key === 'prefetchDecisions' ? 0 : 1
    if (!Number.isInteger(value) || value < minimum || (limits[key] && value > limits[key])) {
      return `${label} must be a whole number from ${minimum}${limits[key] ? ` to ${limits[key]}` : ''}.`
    }
  }
  for (const [key, label] of BUDGETS) {
    const value = form.budgets[key]
    if (value !== '' && (!Number.isInteger(Number(value)) || Number(value) < 0)) {
      return `${label} must be a whole number, or empty for no limit.`
    }
  }
  if (form.minutes !== '' && !(Number(form.minutes) > 0)) {
    return 'The time limit must be positive, or empty for no limit.'
  }
  return null
}

function initialForm(defaults) {
  return {
    name: '',
    scenarios: new Set(),
    percent: defaults.specificationPercent,
    budgets: { verifications: '', decisions: '', input_tokens: '' },
    minutes: '',
    seed: defaults.seed,
    parallelism: Object.fromEntries(PARALLELISM.map(([key]) => [key, defaults[key]]))
  }
}

/** The form itself, created once the server's defaults are known. */
function RunForm({ info, scenarios, onError }) {
  const navigate = useNavigate()
  const [form, setForm] = useState(() => initialForm(info.defaults))
  const [submitting, setSubmitting] = useState(false)
  const update = (changes) => setForm({ ...form, ...changes })

  const submit = async (event) => {
    event.preventDefault()
    const problem = validate(form, info.limits)
    if (problem) {
      onError(problem)
      return
    }
    setSubmitting(true)
    onError(null)
    try {
      const body = await api.createRun(runRequest(form))
      navigate(`/runs/${body.run.id}`)
    } catch (failure) {
      onError(failure.message)
      setSubmitting(false)
    }
  }

  return (
    <form className="NewRunPage-form card" onSubmit={submit} noValidate>
      <div className="field">
        <FieldLabel htmlFor="run-name" hint={HINTS.settings.name}>
          Name
        </FieldLabel>
        <input
          id="run-name"
          className="input"
          placeholder="Optional"
          value={form.name}
          onChange={(event) => update({ name: event.target.value })}
        />
      </div>
      <div className="field">
        <FieldLabel hint={HINTS.settings.scenarios}>Scenarios</FieldLabel>
        <ScenarioPicker
          scenarios={scenarios}
          selected={form.scenarios}
          onChange={(selected) => update({ scenarios: selected })}
        />
      </div>
      <div className="NewRunPage-row">
        <div className="field">
          <FieldLabel htmlFor="run-percent" hint={HINTS.settings.specifications}>
            Specifications
          </FieldLabel>
          <select
            id="run-percent"
            className="input"
            value={form.percent}
            onChange={(event) => update({ percent: event.target.value })}
          >
            {SPECIFICATION_PERCENTS.map((percent) => (
              <option key={percent} value={percent}>
                {percent}%
              </option>
            ))}
          </select>
        </div>
        {BUDGETS.map(([key, label]) => (
          <div className="field" key={key}>
            <FieldLabel htmlFor={`budget-${key}`} hint={HINTS.settings[key]}>
              {`${label} per scenario`}
            </FieldLabel>
            <input
              id={`budget-${key}`}
              className="input"
              inputMode="numeric"
              placeholder="No limit"
              value={form.budgets[key]}
              onChange={(event) =>
                update({ budgets: { ...form.budgets, [key]: event.target.value } })
              }
            />
          </div>
        ))}
        <div className="field">
          <FieldLabel htmlFor="budget-minutes" hint={HINTS.settings.minutes}>
            Minutes per scenario
          </FieldLabel>
          <input
            id="budget-minutes"
            className="input"
            inputMode="decimal"
            placeholder="No limit"
            value={form.minutes}
            onChange={(event) => update({ minutes: event.target.value })}
          />
        </div>
      </div>
      <Disclosure title="Advanced" meta="Parallelism, seed, decision model">
        <div className="NewRunPage-row">
          {PARALLELISM.map(([key, label]) => (
            <div className="field" key={key}>
              <FieldLabel htmlFor={`parallelism-${key}`} hint={HINTS.settings[key]}>
                {label}
              </FieldLabel>
              <input
                id={`parallelism-${key}`}
                className="input"
                inputMode="numeric"
                value={form.parallelism[key]}
                onChange={(event) =>
                  update({ parallelism: { ...form.parallelism, [key]: event.target.value } })
                }
              />
            </div>
          ))}
          <div className="field">
            <FieldLabel htmlFor="run-seed" hint={HINTS.settings.seed}>
              Seed
            </FieldLabel>
            <input
              id="run-seed"
              className="input"
              inputMode="numeric"
              value={form.seed}
              onChange={(event) => update({ seed: event.target.value })}
            />
          </div>
        </div>
        <p className="NewRunPage-model muted">
          <Hint {...HINTS.settings.decisionModel}>Decision model</Hint>{' '}
          <span className="mono">{info.model}</span> at{' '}
          <span className="mono">{info.endpoint}</span>, set when the server starts.
        </p>
      </Disclosure>
      <div className="NewRunPage-actions">
        <button type="submit" className="button primary" disabled={submitting}>
          {submitting ? 'Starting…' : 'Start run'}
        </button>
        <button type="button" className="button quiet" onClick={() => navigate('/runs')}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/** The page that configures and starts a run; rarely needed settings stay folded away. */
export default function NewRunPage() {
  const info = useInfo()
  const [scenarios, setScenarios] = useState([])
  const [error, setError] = useState(null)

  useEffect(() => {
    api.scenarios().then(
      (body) => setScenarios(body.scenarios),
      (failure) => setError(failure.message)
    )
  }, [])

  const ready = info && info.defaults
  return (
    <div className="page NewRunPage">
      <PageHeader
        title="New run"
        meta={ready && info.demo ? 'Demo mode: runs use the scripted repairer.' : null}
      />
      <Alert>{error}</Alert>
      {ready && <RunForm info={info} scenarios={scenarios} onError={setError} />}
    </div>
  )
}
