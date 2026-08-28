/**
 * What the interface says about a photograph's own resolution.
 *
 * The photo frame is one fixed size everywhere - see the `.plate` rules in the
 * detail and queue views for why. That is the right call for the task: a
 * researcher confirming or correcting a bleaching label is making a judgement
 * about the whole frame, and a frame that shrinks to 224 px because the source
 * happened to be a dataset crop is not examinable. Two photographs of the same
 * reef should also not appear to be different sizes of thing.
 *
 * But a fixed frame means a small photograph is shown larger than it really is,
 * and that is a claim the interface should not make silently. A 224 px crop
 * enlarged to 700 px looks like a 700 px photograph until you look closely. So it
 * is said out loud, next to the resolution it qualifies.
 *
 * The threshold is deliberately absolute rather than a comparison against the
 * rendered width. Measuring the frame would mean a ResizeObserver for a caption
 * note, and the honest statement does not depend on the measurement: 224x224 is
 * low resolution for judging coral condition whatever size it is drawn at.
 */

/**
 * Below this, on the shorter side, a photograph carries less detail than the
 * frame it is drawn in. Set just under the frame's own ceiling (44rem = 704 px),
 * so anything flagged really is being enlarged on any normal display.
 */
const LOW_RESOLUTION_PX = 600

export function isLowResolution(width: number, height: number): boolean {
  return Math.min(width, height) < LOW_RESOLUTION_PX
}

/** `900x700`, for a resolution readout. */
export function dimensions(width: number, height: number): string {
  return `${width}×${height}`
}

/**
 * The full explanation, for somewhere with room to print it - the detail page's
 * assessment panel. Names the actual resolution rather than saying "low", because
 * how low it is changes what you can conclude.
 */
export function lowResolutionNote(width: number, height: number): string {
  return (
    `Enlarged to fill the frame. There is no more detail here than ` +
    `${dimensions(width, height)} holds — judge accordingly, and reject the ` +
    `photograph if you cannot.`
  )
}

/** The same point as a hover label, for the queue's single caption line. */
export const LOW_RESOLUTION_TIP =
  'Enlarged to the standard frame size. There is no more detail here than the ' +
  'original pixels hold — judge accordingly, and flag it if you cannot.'
