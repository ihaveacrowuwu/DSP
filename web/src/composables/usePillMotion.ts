/**
 * usePillMotion - slides a "jelly pill" behind the active item in a tab or nav
 * list, and optionally clips a highlight to it.
 *
 * The pill is one element behind the items, not a background on the active item.
 * It measures the active item, moves over it, and stretches on launch / squashes
 * on arrival. Deformation scales with distance travelled and is damped by the
 * pill's own size.
 *
 * Movement animates `top`/`left`, not `transform`, because the squash keyframes
 * own `transform`.
 *
 * Constraints, all load-bearing:
 * - The spotlight clip uses the pill's painted rect (getBoundingClientRect), not
 *   its layout rect, so it stays welded to the pill during the squash. Ancestor
 *   transforms are divided out; the pill's own transforms are kept.
 * - The jelly class is applied imperatively (remove, flush, add) because
 *   re-applying the same class does not replay a CSS animation, and nothing then
 *   fires `animationend`. Do not also bind this class in the template.
 * - Position updates are never gated on the animation; only the deformation
 *   variables are held steady mid-keyframe. A safety timer clears the moving flag
 *   if an animation event is lost.
 * - Nothing may move or resize an item under the pill. The consumer's CSS must
 *   freeze hover on the active item and while the pill travels; see AppSidebar.
 */
import { onBeforeUnmount, onMounted, ref, watch, type CSSProperties, type Ref } from 'vue'

export type PillOrientation = 'vertical' | 'horizontal'

interface Options {
  /** Ref holding the id of the active item; changing it moves the pill. */
  activeId: Ref<string | null>
  /** Container the pill is positioned inside. Must be `position: relative`. */
  containerRef: Ref<HTMLElement | null>
  orientation?: PillOrientation
  /** Items are found by this attribute, whose value is compared to activeId. */
  attribute?: string
  /** The pill element itself. Required for the spotlight clip. */
  pillRef?: Ref<HTMLElement | null>
  /** Overlay clipped to the pill. Required for the spotlight clip. */
  spotlightRef?: Ref<HTMLElement | null>
  /** The pill's corner radius in px, so the clip can round to match it. */
  radius?: number
}

/** Reference pill sizes at which deformation is exactly as authored. */
const REFERENCE = { vertical: 38, horizontal: 46 }

/**
 * How long to keep re-clipping after something moves the pill. Covers the travel
 * transition plus the jelly keyframe with room to spare; it is a bounded window
 * rather than an open loop because the pill only ever moves as the result of a
 * commit, never under the pointer.
 */
const SPOTLIGHT_WINDOW_MS = 700

/** Fully hidden. Used before the first measurement and when there is no pill. */
const CLIP_HIDDEN = 'inset(0 0 100% 0)'

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/** Offset of `el` inside `container`, walking the offsetParent chain. */
function offsetWithin(el: HTMLElement, container: HTMLElement, axis: 'offsetTop' | 'offsetLeft') {
  let total = 0
  let node: HTMLElement | null = el
  while (node && node !== container) {
    total += node[axis]
    node = node.offsetParent as HTMLElement | null
  }
  return total
}

export function usePillMotion({
  activeId,
  containerRef,
  orientation = 'vertical',
  attribute = 'data-pill-id',
  pillRef,
  spotlightRef,
  radius = 14,
}: Options) {
  const vertical = orientation === 'vertical'

  /** Inline style for the pill element: placement plus deformation variables. */
  const style = ref<CSSProperties>({})
  /** False until the first successful measurement, so the pill never flashes at 0. */
  const placed = ref(false)
  /** True while a jelly keyframe owns the pill, for the consumer's hover freeze. */
  const moving = ref(false)
  /** Raw measurement, for consumers that need the pill's layout box. */
  const rect = ref({ offset: 0, size: 0 })

  let previousOffset: number | null = null
  let previousId: string | null = null
  let observer: ResizeObserver | null = null
  let safetyTimer: number | undefined

  /**
   * The deformation variables from the last actual move. Kept so a re-measure that
   * is not a move - a container resize, the rail expanding - can rewrite placement
   * without disturbing magnitudes a running keyframe is reading.
   */
  let deform: Record<string, string> = {
    '--pill-travel-dur': '0.2s',
    '--pill-anim-dur': '0.32s',
    '--pill-sy': '1',
    '--pill-sx': '1',
    '--pill-sy2': '1',
    '--pill-sx2': '1',
    '--pill-qy': '1',
    '--pill-qx': '1',
  }

  /** rAF handle for the clip window, and the timestamp it should stop at. */
  let spotlightFrame = 0
  let spotlightUntil = 0
  /** Last clip written, so identical writes do not re-dirty style every frame. */
  let lastClip = { el: null as HTMLElement | null, value: '' }

  function writeClip(value: string) {
    const el = spotlightRef?.value
    if (!el) return
    // Keyed by element as well as value: the overlay is conditionally rendered, so
    // a remount produces a fresh unclipped node that must be written even when the
    // computed string is unchanged.
    if (el === lastClip.el && value === lastClip.value) return
    lastClip = { el, value }
    el.style.clipPath = value
  }

  /** Clip the overlay to the pill's painted rect, in the overlay's own space. */
  function syncSpotlight() {
    const overlay = spotlightRef?.value
    if (!overlay) return

    const pill = pillRef?.value
    if (!pill || !placed.value) {
      writeClip(CLIP_HIDDEN)
      return
    }

    const oRect = overlay.getBoundingClientRect()
    const pRect = pill.getBoundingClientRect()
    if (oRect.width <= 0 || oRect.height <= 0 || pRect.width <= 0 || pRect.height <= 0) {
      writeClip(CLIP_HIDDEN)
      return
    }

    // Ancestor scale, recovered by comparing painted size to layout size. Divided
    // out below so an ancestor's transform does not offset the clip.
    const ax = oRect.width / (overlay.offsetWidth || oRect.width)
    const ay = oRect.height / (overlay.offsetHeight || oRect.height)

    const top = Math.max(0, (pRect.top - oRect.top) / ay)
    const left = Math.max(0, (pRect.left - oRect.left) / ax)
    const right = Math.max(0, (oRect.right - pRect.right) / ax)
    const bottom = Math.max(0, (oRect.bottom - pRect.bottom) / ay)

    // The pill's corners are painted through whatever transform it carries, so a
    // fixed radius drifts off them exactly while it is deforming. Scale the radius
    // per axis by the pill's own scale, and the clip stays elliptical in step with
    // the squash instead of snapping round.
    const psx = pRect.width / ax / (pill.offsetWidth || pRect.width / ax)
    const psy = pRect.height / ay / (pill.offsetHeight || pRect.height / ay)

    writeClip(
      `inset(${top.toFixed(2)}px ${right.toFixed(2)}px ${bottom.toFixed(2)}px ${left.toFixed(2)}px` +
        ` round ${(radius * psx).toFixed(2)}px / ${(radius * psy).toFixed(2)}px)`,
    )
  }

  /**
   * Re-clip every frame for `ms`. rAF runs after transitions have advanced and
   * before paint, so the clip is never a frame behind the pill.
   */
  function runSpotlight(ms = SPOTLIGHT_WINDOW_MS) {
    if (!spotlightRef?.value) return
    if (prefersReducedMotion()) {
      // Nothing animates, so the pill is already at its final rect and one sync
      // per commit is exact. A rAF window here would be pure waste.
      syncSpotlight()
      return
    }

    const until = performance.now() + ms
    if (until > spotlightUntil) spotlightUntil = until
    if (spotlightFrame) return

    const tick = () => {
      syncSpotlight()
      if (performance.now() < spotlightUntil) {
        spotlightFrame = requestAnimationFrame(tick)
      } else {
        spotlightFrame = 0
      }
    }
    spotlightFrame = requestAnimationFrame(tick)
  }

  /** Every keyframe class this composable can apply, for a clean slate. */
  const JELLY = ['pill-jelly-down', 'pill-jelly-up', 'pill-jelly-right', 'pill-jelly-left']

  function stopJelly() {
    window.clearTimeout(safetyTimer)
    safetyTimer = undefined
    pillRef?.value?.classList.remove(...JELLY)
    moving.value = false
  }

  /**
   * Restart the squash. Remove, flush, add: reading a layout property between the
   * two commits the removal, which is what lets the same animation replay. Setting
   * the class through a template binding cannot do this - an unchanged value is not
   * a change, so the animation silently never runs.
   */
  function playJelly(name: string) {
    const el = pillRef?.value
    if (!el) return

    el.classList.remove(...JELLY)
    void el.offsetWidth
    el.classList.add(name)
    moving.value = true

    // Belt and braces. animationend is the normal release, but a lost event - a
    // backgrounded tab, an interrupted animation - must not be able to leave the
    // flag set, because consumers freeze hover behaviour on it.
    window.clearTimeout(safetyTimer)
    safetyTimer = window.setTimeout(stopJelly, 900)
  }

  function measure() {
    const container = containerRef.value
    const id = activeId.value
    if (!container || !id) return

    const item = container.querySelector<HTMLElement>(`[${attribute}="${id}"]`)
    if (!item) return

    const offset = vertical
      ? offsetWithin(item, container, 'offsetTop')
      : offsetWithin(item, container, 'offsetLeft')
    const size = vertical ? item.offsetHeight : item.offsetWidth
    const containerSize = vertical ? container.offsetHeight : container.offsetWidth
    if (!size) return

    // How hard to deform: distance travelled, normalised against a comfortable
    // fraction of the container so the same gesture feels the same in a short
    // rail and a long one.
    const reach = Math.max(containerSize * 0.6, 80)
    const travelled = previousOffset === null ? 0 : Math.abs(offset - previousOffset)
    const changed = previousId !== null && previousId !== id && travelled > 2
    const intensity = changed ? Math.min(travelled / reach, 1) : 0

    const damp = Math.min(Math.max(Math.sqrt(REFERENCE[orientation] / size), 0.35), 1.1)
    // Floored: raw distance makes a one-item hop deform ~8%, which is invisible.
    // Every real move gets at least a quarter of the full wobble.
    const drive = intensity > 0 ? (0.25 + 0.75 * intensity) * damp : 0

    // Deformation is rewritten only when the pill actually moved. A resize or an
    // expand re-measures for placement, and rewriting these then would change the
    // magnitudes a running keyframe is reading mid-squash - a visible pop.
    if (changed) {
      deform = {
        '--pill-travel-dur': `${(0.2 + 0.16 * intensity).toFixed(2)}s`,
        '--pill-anim-dur': `${(0.32 + 0.1 * intensity).toFixed(2)}s`,
        '--pill-sy': String(1 + 0.34 * drive),
        '--pill-sx': String(1 - 0.07 * drive),
        '--pill-sy2': String(1 + 0.2 * drive),
        '--pill-sx2': String(1 - 0.05 * drive),
        '--pill-qy': String(1 - 0.19 * drive),
        '--pill-qx': String(1 + 0.05 * drive),
      }
    }

    // Placement is layout and is written unconditionally: whatever the animation is
    // doing, the pill belongs over the active item.
    style.value = {
      ...(vertical
        ? { top: `${offset}px`, height: `${size}px` }
        : { left: `${offset}px`, width: `${size}px` }),
      ...deform,
    } as CSSProperties

    const forward = offset > (previousOffset ?? 0)

    rect.value = { offset, size }
    previousOffset = offset
    previousId = id
    placed.value = true

    if (changed && !prefersReducedMotion()) {
      playJelly(
        vertical
          ? forward
            ? 'pill-jelly-down'
            : 'pill-jelly-up'
          : forward
            ? 'pill-jelly-right'
            : 'pill-jelly-left',
      )
    }

    runSpotlight()
  }

  /** Re-measure after the DOM has settled; used on mount and on data changes. */
  function remeasure() {
    requestAnimationFrame(() => {
      measure()
      runSpotlight()
    })
  }

  function onAnimationEnd() {
    stopJelly()
    // Resync now that the pill is its own size again, so the clip lands exactly on
    // the resting rect rather than on the last animated frame.
    runSpotlight(160)
  }

  watch(activeId, () => measure(), { flush: 'post' })

  // The preference can flip mid-animation, in which case the keyframe is
  // suppressed and its animationend never arrives - which would leave the
  // measurement gate closed forever.
  const motionQuery =
    typeof window === 'undefined' ? null : window.matchMedia('(prefers-reduced-motion: reduce)')
  function onMotionPreferenceChange() {
    if (!prefersReducedMotion()) return
    // The keyframe is suppressed from here on, so its animationend will never
    // arrive; release the flag by hand and settle the clip.
    stopJelly()
    syncSpotlight()
  }

  onMounted(() => {
    remeasure()
    // Fonts settling, the rail expanding, a filter changing the item count: all
    // resize the container without changing activeId, and without this the pill
    // keeps a stale offset and lands between two items.
    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      observer = new ResizeObserver(() => {
        measure()
        runSpotlight(220)
      })
      observer.observe(containerRef.value)
    }
    motionQuery?.addEventListener('change', onMotionPreferenceChange)
  })

  onBeforeUnmount(() => {
    window.clearTimeout(safetyTimer)
    safetyTimer = undefined
    observer?.disconnect()
    observer = null
    motionQuery?.removeEventListener('change', onMotionPreferenceChange)
    if (spotlightFrame) cancelAnimationFrame(spotlightFrame)
    spotlightFrame = 0
    spotlightUntil = 0
  })

  return {
    style,
    placed,
    moving,
    rect,
    remeasure,
    onAnimationEnd,
    /** Arm a clip window by hand, for movement the composable cannot observe. */
    runSpotlight,
  }
}
