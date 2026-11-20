package com.manhas.reader.data

/**
 * Hard ceiling on one rendered page edge, in pixels.
 *
 * Lives in the data layer because both the PDF session (which renders pages)
 * and the reader UI (which budgets render widths) must agree on the cap.
 */
object ReaderRenderLimits {
  const val MAX_PAGE_DIMENSION = 8192f
}
