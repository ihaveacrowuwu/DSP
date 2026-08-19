import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { initTheme } from './composables/useTheme'

// Load order is load-bearing: base sets structural tokens and the reset, theme
// fills in every colour those rules reference, components builds the shared
// primitives on top. Later files are allowed to override earlier ones.
import './assets/base.css'
import './assets/theme.css'
import './assets/components.css'

// Stamp the stored colour scheme before the first paint, or a pinned light theme
// flashes dark for a frame on every reload.
initTheme()

createApp(App).use(createPinia()).use(router).mount('#app')
