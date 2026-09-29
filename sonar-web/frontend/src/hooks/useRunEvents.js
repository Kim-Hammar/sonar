import { useEffect, useReducer } from 'react'
import { api } from '../components/Common/api'
import { EVENT_PAGE } from '../components/Common/constants'
import { initialState, runReducer } from '../components/Runs/runReducer'

const defaultSource = (url) => new EventSource(url)
const schedule = (callback) =>
  typeof requestAnimationFrame === 'function'
    ? requestAnimationFrame(callback)
    : setTimeout(callback, 16)
const unschedule = (handle) =>
  typeof cancelAnimationFrame === 'function' ? cancelAnimationFrame(handle) : clearTimeout(handle)

/**
 * Follows the events of a run: loads the history in pages, then streams new events with
 * Server-Sent Events, batching them per animation frame, until the run ends.
 */
export default function useRunEvents(
  runId,
  { openSource = defaultSource, fetchPage = api.events } = {}
) {
  const [state, dispatch] = useReducer(runReducer, initialState)

  useEffect(() => {
    let active = true
    let source = null
    let buffer = []
    let frame = null

    const flush = () => {
      frame = null
      if (buffer.length > 0) {
        dispatch({ type: 'events', events: buffer })
        buffer = []
      }
    }

    const follow = (after) => {
      source = openSource(api.streamUrl(runId, after))
      source.onopen = () => dispatch({ type: 'connection', status: 'live' })
      source.onerror = () => {
        if (source.readyState === 2) {
          flush()
          dispatch({ type: 'ended' })
        } else {
          dispatch({ type: 'connection', status: 'reconnecting' })
        }
      }
      source.addEventListener('events', (message) => {
        buffer = buffer.concat(JSON.parse(message.data))
        if (frame === null) {
          frame = schedule(flush)
        }
      })
      source.addEventListener('end', () => {
        source.close()
        flush()
        dispatch({ type: 'ended' })
      })
    }

    const load = async () => {
      dispatch({ type: 'reset' })
      let after = 0
      for (;;) {
        const page = await fetchPage(runId, after, EVENT_PAGE)
        if (!active) {
          return
        }
        if (page.events.length > 0) {
          dispatch({ type: 'events', events: page.events })
          after = page.last
        }
        if (page.events.length < EVENT_PAGE) {
          if (page.sealed && after >= page.size) {
            dispatch({ type: 'ended' })
          } else {
            follow(after)
          }
          return
        }
      }
    }

    load().catch((error) => {
      if (active) {
        dispatch({ type: 'error', error: error.message })
      }
    })

    return () => {
      active = false
      if (source) {
        source.close()
      }
      if (frame !== null) {
        unschedule(frame)
      }
    }
  }, [runId, openSource, fetchPage])

  return state
}
