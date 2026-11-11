/**
 * Keeps #app the size of the *visual* viewport. iOS doesn't shrink the layout viewport when the
 * keyboard opens, so a bottom-anchored composer would end up behind it; the visual viewport is
 * the part actually on screen. Also undoes the page scroll iOS adds to "reveal" a focused input,
 * since our box already follows the keyboard.
 */
export function trackVisualViewport(): void {
  const vv = window.visualViewport
  if (!vv) return
  const root = document.documentElement
  const apply = () => {
    root.style.setProperty('--vv-height', `${vv.height}px`)
    root.style.setProperty('--vv-top', `${vv.offsetTop}px`)
  }
  vv.addEventListener('resize', apply)
  vv.addEventListener('scroll', apply)
  apply()
}
