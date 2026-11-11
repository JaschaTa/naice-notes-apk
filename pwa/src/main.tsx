import { render } from 'preact'
import './index.css'
import { App } from './app.tsx'
import { trackVisualViewport } from './ui/viewport.ts'

trackVisualViewport()

render(<App />, document.getElementById('app')!)
