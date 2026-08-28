/**
 * useHoverIntent - opens a panel on hover only when the pointer looks deliberate.
 *
 * A bare `pointerenter` is wrong for the rail: it spans the whole left edge, so
 * every crossing would open and close it. Entry needs one of three signals:
 * the pointer is moving slowly, it stops inside, or the panel is already open.
 * Speed is an exponential moving average so one jittery event cannot flip it.
 *
 * Staying open is decided by a band larger than the element's own box. Sides
 * listed in `dockedEdges` extend to the window edge, since a panel inset from an
 * edge is routinely overshot; other sides get a small pad. Leaving the band or
 * the window closes after a delay, which also covers clipping a corner or
 * crossing a gap between children.
 */
import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

interface Options {
  /** The element to watch. Must be mounted before intent can be measured. */
  elementRef: Ref<HTMLElement | null>
  /** Grace period before closing, so a clipped corner does not shut the panel. */
  closeDelay?: number
  /** px/ms at or below which movement counts as deliberate. */
  slowSpeed?: number
  /** How long the pointer must be motionless inside before that counts as intent. */
  restDelay?: number
  /**
   * Sides the element is docked against. The band extends to the window edge on
   * each of these, so overshooting into the gutter between the element and the
   * edge never reads as leaving.
   */
  dockedEdges?: Array<'left' | 'right' | 'top' | 'bottom'>
  /** Pad in px applied to the sides that are not docked. */
  edgePad?: number
}

/** EWMA time constant in ms: responsiveness against noise. */
const TAU = 20

export function useHoverIntent({
  elementRef,
  closeDelay = 180,
  slowSpeed = 0.2,
  restDelay = 60,
  dockedEdges = [],
  edgePad = 14,
}: Options) {
  const open = ref(false)

  /** Smoothed pointer speed, px/ms. */
  let speed = 0
  let lastX = 0
  let lastY = 0
  let lastTime = 0
  let hasSample = false

  /** Cached so a pointermove does not force layout on every event. */
  let rect: DOMRect | null = null

  let closeTimer: number | undefined
  let restTimer: number | undefined
  let observer: ResizeObserver | null = null

  function clearTimers() {
    window.clearTimeout(closeTimer)
    window.clearTimeout(restTimer)
    closeTimer = undefined
    restTimer = undefined
  }

  function openNow() {
    clearTimers()
    open.value = true
  }

  function closeNow() {
    clearTimers()
    open.value = false
  }

  function scheduleClose() {
    if (!open.value || closeTimer !== undefined) return
    closeTimer = window.setTimeout(() => {
      closeTimer = undefined
      open.value = false
    }, closeDelay)
  }

  function currentRect(): DOMRect | null {
    if (!rect) rect = elementRef.value?.getBoundingClientRect() ?? null
    return rect
  }

  /**
   * The element's box grown into the region the pointer could plausibly be aiming
   * at: out to the window edge on every docked side, and by `edgePad` elsewhere.
   */
  function withinBand(x: number, y: number): boolean {
    const box = currentRect()
    if (!box) return false

    const left = dockedEdges.includes('left') ? 0 : box.left - edgePad
    const right = dockedEdges.includes('right') ? window.innerWidth : box.right + edgePad
    const top = dockedEdges.includes('top') ? 0 : box.top - edgePad
    const bottom = dockedEdges.includes('bottom') ? window.innerHeight : box.bottom + edgePad

    return x >= left && x <= right && y >= top && y <= bottom
  }

  function onPointerMove(event: PointerEvent) {
    // Touch and pen have no hover state to speak of; on those the rail is a
    // permanently expanded bar, so intent tracking would only fight it.
    if (event.pointerType !== 'mouse') return

    const now = event.timeStamp
    if (hasSample) {
      const dt = Math.max(now - lastTime, 1)
      const instant = Math.hypot(event.clientX - lastX, event.clientY - lastY) / dt
      // Exponential moving average, framerate-independent via the dt weighting.
      speed += (1 - Math.exp(-dt / TAU)) * (instant - speed)
    }
    lastX = event.clientX
    lastY = event.clientY
    lastTime = now
    hasSample = true

    if (!withinBand(event.clientX, event.clientY)) {
      window.clearTimeout(restTimer)
      restTimer = undefined
      scheduleClose()
      return
    }

    window.clearTimeout(closeTimer)
    closeTimer = undefined

    if (open.value || speed <= slowSpeed) {
      openNow()
      return
    }

    // Fast and still moving: hold, but arm the dead-stop fallback. If the pointer
    // halts here, pointermove goes silent and this timer is the only thing left
    // that can open the panel.
    if (restTimer === undefined) {
      restTimer = window.setTimeout(() => {
        restTimer = undefined
        if (withinBand(lastX, lastY)) openNow()
      }, restDelay)
    }
  }

  /**
   * The pointer left the window entirely, so no further move events are coming.
   * Closing on a delay rather than at once: overshooting off the top of the window
   * and coming straight back is still an attempt to reach the panel.
   */
  function onPointerLeaveDocument() {
    scheduleClose()
  }

  function invalidateRect() {
    rect = null
  }

  onMounted(() => {
    document.addEventListener('pointermove', onPointerMove, { passive: true })
    document.addEventListener('pointerleave', onPointerLeaveDocument)
    window.addEventListener('scroll', invalidateRect, { passive: true, capture: true })
    window.addEventListener('resize', invalidateRect)

    // The rail's own width animates, so its box changes without any of the
    // events above firing.
    if (typeof ResizeObserver !== 'undefined' && elementRef.value) {
      observer = new ResizeObserver(invalidateRect)
      observer.observe(elementRef.value)
    }
  })

  onBeforeUnmount(() => {
    clearTimers()
    observer?.disconnect()
    observer = null
    document.removeEventListener('pointermove', onPointerMove)
    document.removeEventListener('pointerleave', onPointerLeaveDocument)
    window.removeEventListener('scroll', invalidateRect, true)
    window.removeEventListener('resize', invalidateRect)
  })

  return { open, openNow, closeNow, scheduleClose }
}
