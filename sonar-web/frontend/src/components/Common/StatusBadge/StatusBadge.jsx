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
  demo: 'info',
  accepted: 'ok',
  forwarded: 'neutral',
  delivered_to_subnet: 'neutral',
  delegated_to_vrf: 'neutral',
  no_route: 'bad',
  null_routed: 'bad',
  neighbor_unreachable: 'bad',
  forwarded_blocked_outgoing_filter: 'bad',
  forwarded_blocked_incoming_filter: 'bad',
  insufficient_info: 'warn',
  unknown: 'warn',
  verifying: 'live',
  building: 'live',
  deferred: 'neutral',
  admitted: 'ok',
  dropped: 'warn',
  evidenced: 'ok',
  observed: 'neutral',
  invented: 'warn',
  site: 'neutral',
  relation: 'info',
  incidence: 'info',
  transfer: 'info',
  hard: 'bad',
  soft: 'warn',
  convention: 'neutral'
}

/** A small coloured label for a status; colour is used only where it carries meaning. */
export default function StatusBadge({ status, label }) {
  return (
    <span className={`StatusBadge ${TONES[status] || 'neutral'}`}>
      {TONES[status] === 'live' && <span className="StatusBadge-pulse" />}
      {label || status.replace(/[-_]/g, ' ')}
    </span>
  )
}
