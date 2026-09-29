import { createContext, useContext } from 'react'

/** Information about the server, loaded once: versions, locations, defaults and limits. */
export const InfoContext = createContext(null)

/** Returns the server information, or null while it loads. */
export function useInfo() {
  return useContext(InfoContext)
}
