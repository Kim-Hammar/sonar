const whole = new Intl.NumberFormat('en-US')
const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 })

/** Formats a count with thousands separators. */
export function formatNumber(value) {
  return value === null || value === undefined ? '–' : whole.format(value)
}

/** Formats a large count compactly, such as 12.3K. */
export function formatCompact(value) {
  return value === null || value === undefined ? '–' : compact.format(value)
}

/** Formats a duration given in milliseconds, such as 3 min 05 s. */
export function formatDuration(milliseconds) {
  if (milliseconds === null || milliseconds === undefined || milliseconds < 0) {
    return '–'
  }
  const seconds = Math.floor(milliseconds / 1000)
  if (seconds < 60) {
    return milliseconds < 10000 ? `${(milliseconds / 1000).toFixed(1)} s` : `${seconds} s`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes} min ${String(seconds % 60).padStart(2, '0')} s`
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`
}

/** Formats an epoch time in milliseconds as a local time of day. */
export function formatTime(epoch) {
  return epoch ? new Date(epoch).toLocaleTimeString('en-GB') : '–'
}

/** Formats an epoch time in milliseconds as a short local date and time. */
export function formatDateTime(epoch) {
  if (!epoch) {
    return '–'
  }
  const date = new Date(epoch)
  return `${date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}`
}

/** Human names of event types. */
export const EVENT_LABELS = {
  loaded: 'Scenario loaded',
  baseline: 'Baseline verified',
  'space-built': 'Search space built',
  decided: 'Decision',
  tried: 'Candidate verified',
  adopted: 'Repair adopted',
  finished: 'Finished',
  failed: 'Failed'
}

/** Human names of the reasons a scenario stopped. */
export const STOP_LABELS = {
  solved: 'Solved',
  'budget-exhausted': 'Budget exhausted',
  'time-expired': 'Time limit reached',
  cancelled: 'Cancelled',
  'search-exhausted': 'Search exhausted',
  abandoned: 'Abandoned',
  failed: 'Failed'
}

const plural = (count, word) => `${formatNumber(count)} ${word}${count === 1 ? '' : 's'}`

/** Returns a one-line description of an event summary. */
export function describeEvent(event) {
  switch (event.type) {
    case 'loaded':
      return `${plural(event.routers, 'router')}, ${plural(event.links, 'link')}, ${formatNumber(event.remaining)} of ${formatNumber(event.specifications)} specifications violated`
    case 'baseline':
      return `${formatNumber(event.remaining)} violated, ${plural(event.sideEffects, 'side effect')}`
    case 'space-built':
      return `${plural(event.regions, 'region')} in ${plural(event.partitions, 'partition')}`
    case 'decided':
      return `${event.question} choice, ${event.outcome}, ${formatNumber(event.inputTokens)} input and ${formatNumber(event.outputTokens)} output tokens${event.cached ? ', cached' : ''}`
    case 'tried':
      return `${event.change.replace('-', ' ')} in ${event.region}, ${formatNumber(event.remaining)} would remain`
    case 'adopted':
      return `${plural(event.adopted, 'repair')} adopted, ${formatNumber(event.remaining)} remaining`
    case 'finished':
      return `${STOP_LABELS[event.stop] || event.stop}, ${formatNumber(event.remaining)} remaining`
    case 'failed':
      return event.reason
    default:
      return event.type
  }
}
