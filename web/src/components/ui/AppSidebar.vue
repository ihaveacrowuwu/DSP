<script setup lang="ts">
/**
 * The floating navigation rail, the one piece of persistent chrome.
 *
 * It floats over the content rather than taking a layout column, so the map below
 * runs full-bleed; content is inset by padding instead (see `.page`). Collapsed it
 * is a strip of icons, and hover or focus widens it to reveal labels.
 *
 * Opening is gated on hover intent (useHoverIntent), not pointerenter, because the
 * rail spans the whole left edge.
 *
 * The active marker is a sliding pill (usePillMotion) plus a copy of the nav drawn
 * in the active colours and clipped to the pill's painted rect.
 *
 * That imposes one rule below: nothing may move or resize a nav item. The two
 * copies are stacked and the overlay takes no pointer events, so any hover effect
 * would shift only the base copy and make the highlight shimmer. Hover grow is
 * therefore frozen on the active item, and on all items while the pill travels.
 */
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import Icon from '@/components/ui/Icon.vue'
import { useHoverIntent } from '@/composables/useHoverIntent'
import { usePillMotion } from '@/composables/usePillMotion'
import { useTheme } from '@/composables/useTheme'
import {
  iconDay,
  iconMap,
  iconNight,
  iconOperations,
  iconQueue,
  iconRecords,
  iconSignOut,
} from '@/lib/icons'
import { useAuthStore } from '@/stores/auth'

const emit = defineEmits<{ signOut: [] }>()

const auth = useAuthStore()
const route = useRoute()
const theme = useTheme()

const railRef = ref<HTMLElement | null>(null)
const navRef = ref<HTMLElement | null>(null)
const pillRef = ref<HTMLElement | null>(null)
const spotlightRef = ref<HTMLElement | null>(null)

/**
 * Hover opens the rail only on a deliberate approach, and keeps it open across
 * the whole left strip of the window. The rail floats 10px off the left edge, so
 * without `dockedEdges` an overshoot into that gutter - which is exactly what
 * happens when you reach for the rail and miss - would read as leaving it.
 */
const hover = useHoverIntent({ elementRef: railRef, dockedEdges: ['left'] })
const open = hover.open

interface NavItem {
  key: string
  label: string
  icon: string
  routeName: string
  /** Route names that should also light this item, e.g. a record's detail page. */
  also?: string[]
  visible: () => boolean
}

const ITEMS: NavItem[] = [
  {
    key: 'reefs',
    label: 'Reef map',
    icon: iconMap,
    routeName: 'reefs',
    visible: () => auth.canVerify,
  },
  {
    key: 'queue',
    label: 'Review queue',
    icon: iconQueue,
    routeName: 'queue',
    visible: () => auth.canVerify,
  },
  {
    key: 'sightings',
    label: 'Sightings',
    icon: iconRecords,
    routeName: 'sightings',
    also: ['sighting'],
    visible: () => true,
  },
  {
    key: 'operations',
    label: 'Operations',
    icon: iconOperations,
    routeName: 'operations',
    visible: () => auth.isAdmin,
  },
]

const items = computed(() => ITEMS.filter((item) => item.visible()))

const activeKey = computed(() => {
  const name = String(route.name ?? '')
  const match = items.value.find(
    (item) => item.routeName === name || item.also?.includes(name),
  )
  return match?.key ?? null
})

const pill = usePillMotion({
  activeId: activeKey,
  containerRef: navRef,
  pillRef,
  spotlightRef,
  // Matches --r-md on .pill, so the clip rounds off exactly where the pill does.
  radius: 14,
})

// The visible item list depends on the account's role, which resolves after the
// first paint on a cold load. Re-measure when it changes or the pill keeps the
// offsets of a list it is no longer describing.
watch(items, () => pill.remeasure())
// Expanding widens every row and grows the rail's own hover scale; neither moves
// the pill vertically, but both change its painted rect, so the clip is resynced
// for the length of the width transition.
watch(open, () => pill.runSpotlight(400))

const initials = computed(() => {
  const name = auth.user?.displayName?.trim()
  if (!name) return '··'
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
})

const isLight = computed(() => theme.resolved() === 'light')
</script>

<template>
  <nav
    ref="railRef"
    class="rail"
    :class="{ 'is-open': open }"
    aria-label="Main"
    @focusin="hover.openNow()"
    @focusout="hover.scheduleClose()"
  >
    <!-- The brand mark is the patch lattice at logo size: a 3x3 grid with two
         cells bleached. The product's whole idea, drawn in nine squares. -->
    <div class="brand">
      <span class="mark" aria-hidden="true">
        <span
          v-for="cell in 9"
          :key="cell"
          class="mark-cell"
          :class="{ 'is-bleached': cell === 3 || cell === 5 || cell === 8 }"
        />
      </span>
      <span class="brand-text">
        <strong>Muraka</strong>
        <span class="brand-sub">Reef condition</span>
      </span>
    </div>

    <!-- data-pill-moving is what the hover freezes below hang off: an attribute
         rather than :has(), so the rule cannot be broken by scoped-style
         rewriting of a global keyframe class name. -->
    <div ref="navRef" class="nav-wrap" :data-pill-moving="pill.moving.value ? '' : undefined">
      <!-- No :class binding for the jelly keyframe. usePillMotion writes it on the
           element directly, and a binding here would patch it straight back off. -->
      <span
        v-show="pill.placed.value"
        ref="pillRef"
        class="pill"
        :style="pill.style.value"
        aria-hidden="true"
        @animationend="pill.onAnimationEnd()"
      />

      <ul class="nav">
        <li v-for="item in items" :key="item.key" :data-pill-id="item.key">
          <RouterLink
            class="nav-link"
            :to="{ name: item.routeName }"
            :data-tip="open ? undefined : item.label"
          >
            <Icon :path="item.icon" :size="1.2" />
            <span class="nav-label">{{ item.label }}</span>
          </RouterLink>
        </li>
      </ul>

      <!-- Identical markup, clipped to the pill's painted rect by usePillMotion,
           and inert. Anything interactive in here would be a second hit target
           sitting on top of the real one. -->
      <div ref="spotlightRef" class="spotlight" aria-hidden="true">
        <ul class="nav">
          <li v-for="item in items" :key="item.key">
            <span class="nav-link">
              <Icon :path="item.icon" :size="1.2" />
              <span class="nav-label">{{ item.label }}</span>
            </span>
          </li>
        </ul>
      </div>
    </div>

    <div class="account">
      <div class="who">
        <span class="badge readout">{{ initials }}</span>
        <span class="who-text">
          <span class="who-name">{{ auth.user?.displayName }}</span>
          <span class="who-role">{{ auth.user?.role }}</span>
        </span>
      </div>

      <!-- Contributors are measured by what has cleared review, so the rail says
           so rather than making them open a page to find out. -->
      <p v-if="auth.stats && !auth.canVerify" class="tally readout">
        {{ auth.stats.verified }} verified · {{ auth.stats.pending }} pending
      </p>

      <!-- Shaped like the nav links rather than as icon buttons, so the icon
           column stays continuous down the whole rail and the labels appear on
           expand in exactly the same way. -->
      <div class="account-actions">
        <button
          type="button"
          class="action"
          :data-tip="isLight ? 'Dark appearance' : 'Light appearance'"
          @click="theme.toggle()"
        >
          <Icon :path="isLight ? iconNight : iconDay" :size="1.2" />
          <span class="action-text">{{ isLight ? 'Dark appearance' : 'Light appearance' }}</span>
        </button>
        <button type="button" class="action" data-tip="Sign out" @click="emit('signOut')">
          <Icon :path="iconSignOut" :size="1.2" />
          <span class="action-text">Sign out</span>
        </button>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.rail {
  position: fixed;
  top: var(--rail-gap);
  left: var(--rail-gap);
  bottom: var(--rail-gap);
  z-index: var(--z-rail);
  width: var(--rail-w);
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0.5rem;
  background: var(--chrome);
  border: 1px solid var(--line);
  /* The most rounded surface in the app. At this radius the rail reads as an
     object resting on the page rather than a panel docked to its edge. */
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-float), var(--sheen);
  backdrop-filter: blur(var(--blur-lg));
  -webkit-backdrop-filter: blur(var(--blur-lg));
  overflow: hidden;
  transition: width var(--dur) var(--ease-spring), scale var(--dur) var(--ease-spring);
}

.rail:hover {
  scale: 1.006;
}

.rail.is-open {
  width: var(--rail-w-open);
}

/* -- brand ---------------------------------------------------------------- */

.brand {
  display: flex;
  align-items: center;
  gap: 0.5625rem;
  padding: 0.375rem 0.4375rem 0.125rem;
  min-height: 2.25rem;
}

.mark {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5px;
  width: 1.375rem;
  aspect-ratio: 1;
  padding: 1.5px;
  border-radius: var(--r-xs);
  background: var(--surface--1);
  flex-shrink: 0;
}

.mark-cell {
  border-radius: 1px;
  background: var(--reef);
}

.mark-cell.is-bleached {
  background: var(--bone);
}

.brand-text {
  display: grid;
  line-height: 1.15;
  white-space: nowrap;
  opacity: 0;
  transition: opacity var(--dur-fast) linear;
}

.is-open .brand-text {
  opacity: 1;
  animation: slide-in var(--dur) var(--ease-spring) both;
}

.brand-text strong {
  font-size: var(--step-1);
  letter-spacing: -0.02em;
}

.brand-sub {
  font-family: var(--font-mono);
  font-size: var(--step--2);
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-4);
}

/* -- navigation ----------------------------------------------------------- */

.nav-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
}

.nav {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 0.6875rem;
  padding: 0.5rem 0.5625rem;
  border-radius: var(--r-md);
  color: var(--ink-3);
  text-decoration: none;
  white-space: nowrap;
  /* Above the pill, so the pill slides behind the icons rather than over them. */
  position: relative;
  z-index: 1;
  transition: scale var(--dur) var(--ease-spring), color var(--dur-fast) linear;
}

.nav-link:hover {
  color: var(--ink);
  scale: 1.05;
}

.nav-link:active {
  scale: 0.97;
}

/* -- the two hover freezes ---------------------------------------------------
   The active item sits under the pill, and the clipped copy on top of it does
   not receive hover. Growing the base copy alone would leave two glyph sets a
   fraction of a pixel apart, which reads as the highlight crawling. */
.nav-link.router-link-active:hover,
.nav-link.router-link-active:active {
  scale: 1;
  /* Instant, not eased. Clicking an item you are hovering makes it active, which
     switches it from 1.05 to 1 — and easing that over 240ms is 240ms of the base
     copy at a different size from the highlight copy stacked on it, which is
     exactly what reads as the label swelling as the pill lands. */
  transition-duration: 0s;
}

/* Same bug from the other side: while the pill travels it passes over items that
   are not active, and any of those growing under it desynchronises the copies
   for exactly as long as the animation runs. */
.nav-wrap[data-pill-moving] .nav-link:hover,
.nav-wrap[data-pill-moving] .nav-link:active {
  scale: 1;
  transition-duration: 0s;
}

/* An active link keeps its resting colour: the clipped copy is the sole active
   indicator, so the item a pill leaves returns to normal the moment it leaves
   rather than fading a beat later. */

.nav-label {
  font-size: var(--step--1);
  font-weight: 600;
  letter-spacing: 0.01em;
  opacity: 0;
  transition: opacity var(--dur-fast) linear;
}

.is-open .nav-label {
  opacity: 1;
  animation: slide-in var(--dur) var(--ease-spring) both;
}

.pill {
  position: absolute;
  left: 0.375rem;
  right: 0.375rem;
  z-index: 0;
  border-radius: var(--r-md);
  background: var(--reef-wash);
  border: 1px solid color-mix(in srgb, var(--reef) 34%, transparent);
  box-shadow: var(--sheen), 0 2px 10px color-mix(in srgb, var(--reef) 18%, transparent);
  pointer-events: none;
  will-change: transform;
  transition: top var(--pill-travel-dur, 0.24s) var(--ease-travel),
    height var(--dur) var(--ease-out);
}

.spotlight {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  /* Hidden until the first sync, so there is never an unclipped first frame.
     NO transition on clip-path: usePillMotion rewrites it every frame from the
     pill's painted rect, and a transition would make the clip chase a value the
     pill has already left — the highlight would lag its own pill. */
  clip-path: inset(0 0 100% 0);
}

/* Nothing inside may be a hit target. pointer-events:none on the root is not
   enough on its own: a descendant set back to auto punches through it. */
.spotlight * {
  pointer-events: none;
}

.spotlight .nav-link {
  color: var(--reef);
  scale: 1;
}

.spotlight .nav-label {
  color: var(--ink);
}

/* -- account -------------------------------------------------------------- */

.account {
  display: grid;
  gap: 0.5rem;
  padding-top: 0.625rem;
  border-top: 1px solid var(--line);
}

.who {
  display: flex;
  align-items: center;
  gap: 0.5625rem;
  padding-inline: 0.1875rem;
}

.badge {
  display: grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  flex-shrink: 0;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid var(--line);
  font-size: var(--step--2);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-2);
}

.who-text {
  display: grid;
  min-width: 0;
  line-height: 1.2;
  white-space: nowrap;
  opacity: 0;
  transition: opacity var(--dur-fast) linear;
}

.is-open .who-text {
  opacity: 1;
}

.who-name {
  font-size: var(--step--1);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
}

.who-role {
  font-family: var(--font-mono);
  font-size: var(--step--2);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-4);
}

.tally {
  padding-inline: 0.25rem;
  font-size: var(--step--2);
  color: var(--ink-4);
  white-space: nowrap;
  opacity: 0;
  transition: opacity var(--dur-fast) linear;
}

.is-open .tally {
  opacity: 1;
}

/* Never `flex-wrap` here. In a column-direction flex container a wrap pushes the
   overflow into a SECOND COLUMN, which put both buttons outside the rail's
   56px width where `overflow: hidden` clipped them away entirely. */
.account-actions {
  display: grid;
  gap: 2px;
}

.action {
  display: flex;
  align-items: center;
  gap: 0.6875rem;
  padding: 0.5rem 0.5625rem;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink-3);
  text-align: left;
  white-space: nowrap;
  transition: scale var(--dur) var(--ease-spring), color var(--dur-fast) linear,
    background-color var(--dur-fast) linear;
}

.action:hover {
  background: var(--surface-2);
  color: var(--ink);
  scale: 1.05;
}

.action:active {
  scale: 0.97;
}

.action-text {
  font-size: var(--step--1);
  font-weight: 600;
  opacity: 0;
  transition: opacity var(--dur-fast) linear;
}

.is-open .action-text {
  opacity: 1;
  animation: slide-in var(--dur) var(--ease-spring) both;
}

/* -- narrow screens ---------------------------------------------------------
   The rail lies down along the bottom edge and stays there: a rail that expands
   on hover is meaningless on a touch screen, so labels show permanently and the
   travelling pill is replaced by a plain active face. */
@media (max-width: 55rem) {
  .rail,
  .rail.is-open {
    top: auto;
    left: var(--rail-gap);
    right: var(--rail-gap);
    bottom: var(--rail-gap);
    width: auto;
    flex-direction: row;
    align-items: center;
    gap: 0.5rem;
    border-radius: var(--r-lg);
  }

  .rail:hover {
    scale: 1;
  }

  /* The avatar is decoration once the name beside it is gone, and on a phone the
     space it costs is the difference between the sign-out button being reachable
     and being scrolled off the end of the bar. */
  .brand,
  .tally,
  .who,
  .who-text,
  .action-text,
  .spotlight {
    display: none;
  }

  /* Destinations scroll if they have to; the account actions never do, so
     signing out is always one tap away. */
  .nav-wrap {
    flex: 1;
    min-width: 0;
    overflow-x: auto;
  }

  .account {
    flex-shrink: 0;
  }

  .nav {
    grid-auto-flow: column;
    justify-content: start;
    gap: 0.25rem;
  }

  .pill {
    display: none;
  }

  .nav-link.router-link-active {
    background: var(--reef-wash);
    color: var(--reef);
  }

  .nav-label {
    opacity: 1;
  }

  .account {
    padding-top: 0;
    border-top: none;
    border-left: 1px solid var(--line);
    padding-left: 0.5rem;
    grid-auto-flow: column;
    align-items: center;
  }

  .account-actions {
    grid-auto-flow: column;
  }
}
</style>
