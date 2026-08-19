/**
 * Colour scheme preference.
 *
 * Three states, not two: 'system' follows the OS (the default, and what most
 * people want), while 'light' and 'dark' pin the choice by stamping
 * `data-theme` on the root element, which theme.css reads. Pinning matters here
 * because the map is dark and a researcher working in daylight may want the rest
 * of the interface bright regardless of what their laptop reports.
 *
 * The preference is a device setting, so it lives in localStorage rather than on
 * the account: the same person on a boat and at a desk wants different answers.
 */
import { ref } from 'vue'

export type ThemeChoice = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'muraka.theme'

function read(): ThemeChoice {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' ? stored : 'system'
}

/** Module-level so every component observes the same value. */
const choice = ref<ThemeChoice>(read())

/**
 * The OS preference, mirrored into a ref. matchMedia on its own is not reactive,
 * so without this the appearance toggle in the rail would keep showing the wrong
 * icon after the OS flipped between light and dark under the 'system' setting.
 */
const systemQuery = window.matchMedia('(prefers-color-scheme: light)')
const systemLight = ref(systemQuery.matches)
systemQuery.addEventListener('change', (event) => {
  systemLight.value = event.matches
})

function apply(next: ThemeChoice) {
  if (next === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.setAttribute('data-theme', next)
}

/** Called once at startup, before the first paint, to avoid a scheme flash. */
export function initTheme() {
  apply(choice.value)
}

export function useTheme() {
  function set(next: ThemeChoice) {
    choice.value = next
    if (next === 'system') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, next)
    apply(next)
  }

  /** What is actually on screen right now, including under 'system'. */
  function resolved(): 'light' | 'dark' {
    if (choice.value !== 'system') return choice.value
    return systemLight.value ? 'light' : 'dark'
  }

  /** The switch in the rail flips to the opposite of what is currently shown. */
  function toggle() {
    set(resolved() === 'dark' ? 'light' : 'dark')
  }

  return { choice, set, toggle, resolved }
}
