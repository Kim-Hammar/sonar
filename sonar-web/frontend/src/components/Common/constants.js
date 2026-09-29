/** How often the runs list refreshes, in milliseconds. */
export const RUNS_REFRESH = 5000

/** How often an unfinished run's status refreshes, in milliseconds. */
export const RUN_REFRESH = 3000

/** How many event summaries one history page holds. */
export const EVENT_PAGE = 1000

/** The specification percentages the benchmark provides. */
export const SPECIFICATION_PERCENTS = [2, 5, 10, 20, 50, 75, 100]

/** Run statuses that no longer change. */
export const FINAL_STATUSES = new Set(['finished', 'failed', 'cancelled', 'interrupted'])
