import { useEffect, useState } from 'preact/hooks'

/**
 * The clock the due/waiting split is judged against, re-read when the app comes back to the
 * foreground — the same rule as Android's rememberNow(). Hoist it once per screen: anything
 * reading Date.now() inline can disagree with it within a frame. No ticker on purpose: it would
 * reshuffle the list under a finger mid-drag.
 */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const onVisible = () => { if (document.visibilityState === 'visible') setNow(Date.now()) }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])
  return now
}
