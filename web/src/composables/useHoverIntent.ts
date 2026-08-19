/**
 * useHoverIntent — opens a panel on hover only when the pointer means it.
 *
 * A bare `pointerenter` is the wrong trigger for anything that expands. The rail
 * sits along the whole left edge, so every trip from the left of the window to
 * anywhere else crosses it, and each of those trips would throw the rail open and
 * shut again. That reads as the interface twitching at things the user never
 * asked for.
 *
 * So entry is gated on three signals, any one of which counts as intent:
 *
 *   1. the pointer is moving slowly — a deliberate approach opens on the first
 *      event, so nothing feels laggy;
 *   2. it comes to a dead stop inside — a flick that ends on the rail still
 *      opens it, caught by a timer because a stopped pointer fires no events;
 *   3. it is already open — once open, staying inside keeps it open, with no
 *      further intent test to fail.
 *
 * A fast transit matches none of these: it stays fast the whole way across and
 * never stops. Speed is an exponential moving average rather than a raw delta, so
 * one jittery event cannot flip the decision either way.
 *
 * ─── THE BAND IS BIGGER THAN THE ELEMENT ─────────────────────────────────────
 *
 * Intent decides whether to open; the BAND decides whether to stay open, and it
 * is deliberately larger than the element's own box.
 *
 * A floating panel does not touch the edge it is docked against — the rail sits
 * 10px off the left of the window — and aiming for something near an edge means
 * routinely overshooting into that gutter. Testing the element's own rect closes
 * the panel the moment you overshoot and miss, which is precisely when you are
 * still trying to hit it. So on any side listed in `dockedEdges` the band runs all
 * the way to the window edge: there is nothing else out there to be aiming at.
 * The remaining sides get a small pad, enough to cover the panel's own inset.
 *
 * Leaving the band closes after a delay, which also covers the pointer clipping a
 * corner or crossing the 1px gap between two children. Leaving the window is
 * treated the same way rather than closing outright, so a hard overshoot past the
 * top of the window and straight back does not lose the panel.
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
