/**
 * Shared behaviour for a panel that hangs off a trigger and is teleported to
 * <body>: the select menu and the date-range picker.
 *
 * Teleporting is what makes this necessary. It keeps the panel out of any
 * ancestor's `overflow: hidden` - several of these open from inside map overlays
 * and scrolling cards - but it also means the panel has no layout relationship
 * to its trigger, so position has to be measured on open and abandoned when the
 * page moves.
 *
 * Abandoning is deliberate: re-measuring on scroll makes a panel appear to drift
 * against the content it belongs to, and on a map that pans continuously it never
 * settles. Closing is honest and cheap to recover from.
 */
import { nextTick, onBeforeUnmount, ref, type Ref } from 'vue'

interface Options {
  anchorRef: Ref<HTMLElement | null>
  panelRef: Ref<HTMLElement | null>
  /** Narrowest the panel may be, in rem, so short content still reads. */
  minWidthRem?: number
  /** Height assumed before the panel has rendered, used for the flip decision. */
  estimatedHeight?: number
}

const GAP = 6
const EDGE = 8

export function useAnchoredPanel({
  anchorRef,
  panelRef,
  minWidthRem = 11,
  estimatedHeight = 240,
}: Options) {
  const open = ref(false)
  const style = ref<Record<string, string>>({})

  function place() {
    const anchor = anchorRef.value
    if (!anchor) return

    const box = anchor.getBoundingClientRect()
    const height = panelRef.value?.offsetHeight ?? estimatedHeight
    const below = window.innerHeight - box.bottom
    // Flip above only when there genuinely is not room below and there is more
    // room above; flipping on a near-miss feels unstable while scrolling.
    const flip = below < height + GAP + EDGE && box.top > below

    const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
    const width = Math.max(box.width, minWidthRem * rootPx)
    const left = Math.min(
      Math.max(box.left, EDGE),
      Math.max(window.innerWidth - width - EDGE, EDGE),
    )

    style.value = {
      left: `${left}px`,
      minWidth: `${box.width}px`,
      ...(flip
        ? { bottom: `${window.innerHeight - box.top + GAP}px` }
        : { top: `${box.bottom + GAP}px` }),
      maxHeight: `${Math.max((flip ? box.top : below) - GAP - EDGE, 160)}px`,
    }
  }

  function onOutsidePointerDown(event: PointerEvent) {
    const target = event.target as Node
    if (anchorRef.value?.contains(target) || panelRef.value?.contains(target)) return
    hide()
  }

  function onDisplace() {
    if (open.value) hide()
  }

  function listen(active: boolean) {
    const method = active ? 'addEventListener' : 'removeEventListener'
    document[method]('pointerdown', onOutsidePointerDown as EventListener, true)
    window[method]('scroll', onDisplace, true)
    window[method]('resize', onDisplace)
  }

  async function show() {
    if (open.value) return
    open.value = true
    listen(true)
    // Two ticks: one for the panel to mount, one for its own content to lay out,
    // so the flip decision reads a real height rather than the estimate.
    await nextTick()
    place()
    await nextTick()
    place()
  }

  function hide(returnFocus = false) {
    if (!open.value) return
    open.value = false
    listen(false)
    if (returnFocus) anchorRef.value?.focus?.()
  }

  function toggle() {
    if (open.value) hide()
    else void show()
  }

  onBeforeUnmount(() => listen(false))

  return { open, style, show, hide, toggle, place }
}
