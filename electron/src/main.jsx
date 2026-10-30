import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { FluentProvider, webDarkTheme, webLightTheme } from '@fluentui/react-components'
import './index.css'
import App from './App.jsx'
import { useSettingsStore } from './store/settingsStore.jsx'
import { api, hasBridge } from './lib/ipc.jsx'

/**
 * FluentProvider must wrap everything: it injects the CSS custom properties
 * every `tokens.*` reference resolves against. Same Fluent v9 web themes as
 * WorkTrack; dark unless the user has switched it off in Settings.
 */
const initialDark = !hasBridge || api.initialTheme !== 'light'

function Root() {
  const loaded = useSettingsStore((s) => Boolean(s.settings))
  const dark = useSettingsStore((s) => (s.settings ? s.settings.darkMode !== false : initialDark))
  // Keep the window background (visible while resizing) in step with the page.
  useEffect(() => {
    if (hasBridge && loaded) api.set_theme(dark).catch(() => {})
  }, [dark, loaded])
  return (
    <FluentProvider theme={dark ? webDarkTheme : webLightTheme} style={{ height: '100%' }}>
      <App />
    </FluentProvider>
  )
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Root />
  </StrictMode>
)
