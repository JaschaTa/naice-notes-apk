import { render } from 'preact'
import './index.css'
import { App } from './app.tsx'
import { trackVisualViewport } from './ui/viewport.ts'

// The app moved from its workers.dev address to naice-notes.com. An install from the old address
// starts from its own service-worker cache and never asks the server, so only the app itself can
// send it on. (Its IndexedDB stays behind: each origin has its own; the server has the notes.)
if (location.hostname.endsWith('.workers.dev')) {
  location.replace(`https://naice-notes.com${location.pathname}${location.search}${location.hash}`)
} else {
  trackVisualViewport()
  render(<App />, document.getElementById('app')!)
}
