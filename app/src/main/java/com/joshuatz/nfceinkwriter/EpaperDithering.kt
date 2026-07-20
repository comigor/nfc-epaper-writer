package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap

object EpaperDithering {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED = 0xFFFF0000.toInt()

    fun floydSteinbergBwr(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val dithered = ditherArgb(pixels, source.width, source.height)
        return Bitmap.createBitmap(dithered, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    internal fun ditherArgb(source: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        require(source.size == width * height)

        val working = source.copyOf()
        val output = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldColor = working[index]
                val newColor = closestColor(oldColor)
                output[index] = newColor

                val redError = red(oldColor) - red(newColor)
                val greenError = green(oldColor) - green(newColor)
                val blueError = blue(oldColor) - blue(newColor)
                if (x + 1 < width) {
                    addError(working, index + 1, redError, greenError, blueError, 7)
                }
                if (y + 1 < height) {
                    if (x > 0) {
                        addError(working, index + width - 1, redError, greenError, blueError, 3)
                    }
                    addError(working, index + width, redError, greenError, blueError, 5)
                    if (x + 1 < width) {
                        addError(working, index + width + 1, redError, greenError, blueError, 1)
                    }
                }
            }
        }
        return output
    }

    private fun closestColor(color: Int): Int {
        val r = red(color)
        val g = green(color)
        val b = blue(color)
        val blackDistance = squared(r) + squared(g) + squared(b)
        val whiteDistance = squared(255 - r) + squared(255 - g) + squared(255 - b)
        val redDistance = squared(255 - r) + squared(g) + squared(b)
        return when {
            blackDistance <= whiteDistance && blackDistance <= redDistance -> BLACK
            whiteDistance <= redDistance -> WHITE
            else -> RED
        }
    }

    private fun addError(
        pixels: IntArray,
        index: Int,
        redError: Int,
        greenError: Int,
        blueError: Int,
        numerator: Int
    ) {
        val color = pixels[index]
        val r = (red(color) + redError * numerator / 16).coerceIn(0, 255)
        val g = (green(color) + greenError * numerator / 16).coerceIn(0, 255)
        val b = (blue(color) + blueError * numerator / 16).coerceIn(0, 255)
        pixels[index] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun red(color: Int): Int = color ushr 16 and 0xFF
    private fun green(color: Int): Int = color ushr 8 and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
    private fun squared(value: Int): Int = value * value
}
