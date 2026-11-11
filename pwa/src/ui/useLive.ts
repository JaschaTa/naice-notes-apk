import { useEffect, useState } from 'preact/hooks'
import type { Store } from '../data/store.ts'

/** Re-runs `query` whenever the store changes. Returns undefined until the first result. */
export function useLive<T>(store: Store, query: () => Promise<T>, deps: unknown[]): T | undefined {
  const [value, setValue] = useState<T>()
  useEffect(() => {
    let alive = true
    const run = () => { void query().then((v) => { if (alive) setValue(() => v) }) }
    run()
    const unsubscribe = store.subscribe(run)
    return () => { alive = false; unsubscribe() }
  }, [store, ...deps])
  return value
}
