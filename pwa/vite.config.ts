import preact from '@preact/preset-vite'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import pkg from './package.json' with { type: 'json' }

// Ask git first: Workers Builds clones the repo, and the env var it was read from showed the
// branch name ("main") on the live build instead of a sha. Env vars only for builds without git.
function commit(): string {
  try { return execSync('git rev-parse --short HEAD').toString().trim() } catch { /* no git */ }
  return (process.env.WORKERS_CI_COMMIT_SHA ?? process.env.CF_PAGES_COMMIT_SHA ?? 'dev').slice(0, 7)
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(`${pkg.version} · ${commit()}`),
  },
  plugins: [
    preact(),
    VitePWA({
      // A new deploy is fetched in the background and runs on the next app start —
      // nobody has to reinstall or clear anything.
      registerType: 'autoUpdate',
      includeAssets: ['favicon.png', 'apple-touch-icon.png'],
      manifest: {
        name: 'Naice Notes',
        short_name: 'Naice Notes',
        description: 'Shared notes and lists',
        lang: 'en',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#f0806a',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
    }),
  ],
})
