package com.manhas.reader.util

/**
 * Pure helpers for user page rotation.
 *
 * Rotation values are clockwise quarter turns in degrees: 0, 90, 180 or 270.
 * All functions normalize their input, so negative or overflowing angles are
 * safe. [displayRatio] converts a page's native (height / width) ratio to the
 * ratio the page occupies once rotated for display.
 */
object PageRotation {
  /** Normalizes any degree value into the canonical 0..<360 range. */
  fun normalize(rotation: Int): Int = ((rotation % 360) + 360) % 360

  /** Returns the rotation one 90 degree step clockwise from [rotation]. */
  fun next(rotation: Int): Int = normalize(normalize(rotation) + 90)

  /** True when the rotation swaps the page's width and height. */
  fun isQuarterTurn(rotation: Int): Boolean = normalize(rotation) % 180 != 0

  /**
   * Returns the displayed height / width ratio for a page.
   *
   * [nativeRatio] is the unrotated page height divided by its width. Quarter
   * turns swap the displayed axes; other rotations keep the native ratio.
   */
  fun displayRatio(nativeRatio: Float, rotation: Int): Float =
    if (isQuarterTurn(rotation)) 1f / nativeRatio else nativeRatio

  /** Resolves a page override against the document default. */
  fun applied(pageOverride: Int?, documentDefault: Int): Int =
    normalize(pageOverride ?: documentDefault)
}
