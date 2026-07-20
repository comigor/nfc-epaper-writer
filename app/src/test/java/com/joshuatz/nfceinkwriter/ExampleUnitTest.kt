package com.joshuatz.nfceinkwriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveShare154BProtocolTest {
    @Test
    fun `encodes black white and red into separate planes`() {
        val pixels = IntArray(200 * 200) { BLACK }
        val firstByte = intArrayOf(BLACK, WHITE, RED, BLACK, WHITE, RED, BLACK, WHITE)
        firstByte.copyInto(pixels)

        val (blackWhite, red) = WaveShare154BProtocol.encodePixels(pixels)

        assertEquals(0x49.toByte(), blackWhite[0])
        assertEquals(0x24.toByte(), red[0])
    }

    @Test
    fun `encodes an all-white image without red pixels`() {
        val (blackWhite, red) = WaveShare154BProtocol.encodePixels(IntArray(200 * 200) { WHITE })

        assertTrue(blackWhite.all { it == 0xFF.toByte() })
        assertTrue(red.all { it == 0x00.toByte() })
    }

    @Test
    fun `encodes an all-red image without inverting the red plane`() {
        val (blackWhite, red) = WaveShare154BProtocol.encodePixels(IntArray(200 * 200) { RED })

        assertTrue(blackWhite.all { it == 0x00.toByte() })
        assertTrue(red.all { it == 0xFF.toByte() })
    }

    @Test
    fun `preserves row stride and plane separation away from the origin`() {
        val pixels = IntArray(200 * 200) { BLACK }
        for (column in 8 until 16) pixels[100 * 200 + column] = RED
        for (column in 0 until 200) pixels[199 * 200 + column] = WHITE

        val (blackWhite, red) = WaveShare154BProtocol.encodePixels(pixels)

        assertEquals(0x00.toByte(), blackWhite[100 * 25 + 1])
        assertEquals(0xFF.toByte(), red[100 * 25 + 1])
        assertEquals(0xFF.toByte(), blackWhite.last())
        assertEquals(0x00.toByte(), red.last())
    }

    @Test
    fun `pure Kotlin dithering preserves exact palette colors`() {
        val palette = intArrayOf(BLACK, WHITE, RED)

        assertArrayEquals(palette, EpaperDithering.ditherArgb(palette, 3, 1))
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED = 0xFFFF0000.toInt()
    }
}