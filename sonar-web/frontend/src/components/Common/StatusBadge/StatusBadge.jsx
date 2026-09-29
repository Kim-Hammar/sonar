import './StatusBadge.css'

const TONES = {
  queued: 'neutral',
  pending: 'neutral',
  running: 'live',
  finished: 'ok',
  solved: 'ok',
  answered: 'ok',
  improvement: 'ok',
  stopped: 'warn',
  mixed: 'warn',
  cancelled: 'warn',
  interrupted: 'warn',
  'no-effect': 'neutral',
  failed: 'bad',
  regression: 'bad',
  unresolved: 'bad',
  fixed: 'ok',
  'side-effect': 'warn',
  demo: 'info'
}

/** A small coloured label for a status; colour is used only where it carries meaning. */
export default function StatusBadge({ status, label }) {
  return (
    <span className={`StatusBadge ${TONES[status] || 'neutral'}`}>
      {TONES[status] === 'live' && <span className="StatusBadge-pulse" />}
      {label || status.replace('-', ' ')}
    </span>
  )
}
