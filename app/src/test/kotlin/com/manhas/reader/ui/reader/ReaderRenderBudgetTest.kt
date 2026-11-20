package com.manhas.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure checks for the device-adaptive render budget. No Android framework is
 * touched: [ReaderRenderBudget.forDevice] only derives limits from the heap
 * size, the low-RAM flag and the heap class, so these values are the contract
 * the reader UI and the PDF session rely on.
 */
class ReaderRenderBudgetTest {
  private val mib = 1024L * 1024L

  @Test
  fun lowRamDeviceGetsSmallBudgetsAndTileGeometry() {
    val budget = ReaderRenderBudget.forDevice(
      maxMemory = 128 * mib,
      isLowRam = true,
      memoryClass = 128,
    )

    assertEquals(16 * mib, budget.pageBytes.toLong())
    assertEquals(4 * mib, budget.pagePixels.toLong())
    assertEquals(8 * mib, budget.tileBytes.toLong())
    assertEquals(1536, budget.maxZoomRenderWidth)
    assertEquals(384, budget.tileStepPx)
    assertEquals(384, budget.tileMarginPx)
  }

  @Test
  fun normalDeviceUsesMidRangeBudgets() {
    val budget = ReaderRenderBudget.forDevice(
      maxMemory = 256 * mib,
      isLowRam = false,
      memoryClass = 256,
    )

    assertEquals(32 * mib, budget.pageBytes.toLong())
    assertEquals(8 * mib, budget.pagePixels.toLong())
    assertEquals(16 * mib, budget.tileBytes.toLong())
    assertEquals(2048, budget.maxZoomRenderWidth)
    assertEquals(512, budget.tileStepPx)
    assertEquals(640, budget.tileMarginPx)
  }

  @Test
  fun highMemoryDeviceGetsLargeBudgets() {
    val budget = ReaderRenderBudget.forDevice(
      maxMemory = 512 * mib,
      isLowRam = false,
      memoryClass = 512,
    )

    assertEquals(64 * mib, budget.pageBytes.toLong())
    assertEquals(16 * mib, budget.pagePixels.toLong())
    assertEquals(32 * mib, budget.tileBytes.toLong())
    assertEquals(3072, budget.maxZoomRenderWidth)
    assertEquals(512, budget.tileStepPx)
    assertEquals(640, budget.tileMarginPx)
  }

  @Test
  fun tinyHeapIsClampedToMinimumBudgets() {
    val budget = ReaderRenderBudget.forDevice(
      maxMemory = 32 * mib,
      isLowRam = false,
      memoryClass = 32,
    )

    assertEquals(16 * mib, budget.pageBytes.toLong())
    assertEquals(4 * mib, budget.pagePixels.toLong())
    assertEquals(8 * mib, budget.tileBytes.toLong())
  }

  @Test
  fun hugeHeapIsClampedToMaximumBudgets() {
    val budget = ReaderRenderBudget.forDevice(
      maxMemory = 2048 * mib,
      isLowRam = false,
      memoryClass = 2048,
    )

    assertEquals(64 * mib, budget.pageBytes.toLong())
    assertEquals(16 * mib, budget.pagePixels.toLong())
    assertEquals(32 * mib, budget.tileBytes.toLong())
  }

  @Test
  fun pagePixelsAlwaysQuarterOfPageBytes() {
    for (memoryMiB in listOf(32L, 128L, 256L, 512L, 2048L)) {
      val budget = ReaderRenderBudget.forDevice(
        maxMemory = memoryMiB * mib,
        isLowRam = memoryMiB <= 128,
        memoryClass = memoryMiB.toInt(),
      )
      assertEquals(budget.pageBytes / 4, budget.pagePixels)
    }
  }
}
