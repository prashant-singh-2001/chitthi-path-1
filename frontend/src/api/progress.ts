// Mirrors the page state machine in com.chitthi.document.model.PageStatus.
// FAILED is deliberately excluded from the ordered stages - it's a terminal
// side state, not a point on the PENDING -> INDEXED track.
const STAGE_ORDER = ['PENDING', 'OCR_DONE', 'TRANSLATED', 'AUDIO_DONE', 'INDEXED'] as const

export const STAGE_LABELS: Record<number, string> = {
  0: 'Waiting',
  1: 'Transcribed',
  2: 'Translated',
  3: 'Audio ready',
  4: 'Indexed',
}

/** Unknown statuses map to 0 (the start of the track) rather than throwing. */
export function stageIndex(status: string): number {
  const index = STAGE_ORDER.indexOf(status as (typeof STAGE_ORDER)[number])
  return index === -1 ? 0 : index
}

export function isFailed(status: string): boolean {
  return status === 'FAILED'
}

/** FR15: a page whose words didn't fit the owner's daily budget - it stopped before translation and TTS. */
export function isCapped(status: string): boolean {
  return status === 'CAPPED'
}

/**
 * A failed or capped page counts as fully progressed for this percentage -
 * neither will ever advance further on its own, and hiding either from a
 * "done" reading would leave the bar stuck below 100% forever on a document
 * that has otherwise settled.
 */
export function percentComplete(pages: { status: string }[]): number {
  if (pages.length === 0) {
    return 0
  }
  const maxStage = STAGE_ORDER.length - 1
  const total = pages.length * maxStage
  const done = pages.reduce(
    (sum, page) => sum + (isFailed(page.status) || isCapped(page.status) ? maxStage : stageIndex(page.status)),
    0,
  )
  return Math.round((done / total) * 100)
}

export function isTerminalDocumentStatus(status: string): boolean {
  return status === 'COMPLETE' || status === 'PARTIAL'
}

/**
 * FR8: a page can be edited once the pipeline has actually produced text for
 * it (anything past PENDING - including FAILED, which is the recovery path
 * for a page OCR never finished) and the document has settled, so an edit
 * isn't racing an in-flight translate/TTS run for the same page.
 */
export function canEditPage(documentStatus: string, pageStatus: string): boolean {
  return isTerminalDocumentStatus(documentStatus) && pageStatus !== 'PENDING'
}
