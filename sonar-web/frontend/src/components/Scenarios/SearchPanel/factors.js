import { HINTS } from '../../Common/hints'

const MEANINGS = new Map(HINTS.search.factors.items)

/** Returns the hint of the prior factor called `name`. */
export function factorHint(name) {
  return { text: MEANINGS.get(name) || `The weight of the ${name} factor.` }
}

/** Formats a probability as a percentage with one decimal. */
export function formatPercent(probability) {
  return `${(probability * 100).toFixed(1)}%`
}
