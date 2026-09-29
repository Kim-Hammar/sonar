import { useCallback, useState } from 'react'

/** Measures an element's width and height and follows later changes. */
export default function useElementSize() {
  const [size, setSize] = useState({ width: 0, height: 0 })
  const ref = useCallback((element) => {
    if (!element) {
      return
    }
    const measure = () => setSize({ width: element.clientWidth, height: element.clientHeight })
    measure()
    if (typeof ResizeObserver === 'function') {
      const observer = new ResizeObserver(measure)
      observer.observe(element)
      return () => observer.disconnect()
    }
  }, [])
  return [ref, size]
}
