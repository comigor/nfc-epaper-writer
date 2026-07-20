package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.util.Log
import java.io.IOException

class WaveShare154BProtocol(
    private val tag: Tag,
    private val onProgress: (Int) -> Unit
) {
    companion object {
        private const val TAG = "WS154B"
        private const val WIDTH = 200
        private const val HEIGHT = 200
        private const val PLANE_SIZE = WIDTH * HEIGHT / 8
        private const val BYTES_PER_ROW = WIDTH / 8
        private const val CHUNK_SIZE = 250
        private const val ISO_DEP_TIMEOUT_MS = 5_000
        private const val INITIAL_REFRESH_DELAY_MS = 4_000L
        private const val STATUS_POLL_DELAY_MS = 200L
        private const val MAX_STATUS_POLLS = 150

        private val INIT = bytes(0x74, 0xB1, 0x00, 0x00, 0x08, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
        private val GPIO_0 = bytes(0x74, 0x97, 0x00, 0x08, 0x00)
        private val GPIO_1 = bytes(0x74, 0x97, 0x01, 0x08, 0x00)
        private val READ_STATUS = bytes(0x74, 0x9B, 0x00, 0x0F, 0x01)

        private fun bytes(vararg values: Int): ByteArray =
            ByteArray(values.size) { values[it].toByte() }

        internal fun encodePixels(pixels: IntArray): Pair<ByteArray, ByteArray> {
            require(pixels.size == WIDTH * HEIGHT)
            val blackWhite = ByteArray(PLANE_SIZE)
            val red = ByteArray(PLANE_SIZE)

            for (row in 0 until HEIGHT) {
                for (byteColumn in 0 until BYTES_PER_ROW) {
                    val pixelBase = row * WIDTH + byteColumn * 8
                    var blackWhiteByte = 0
                    var redByte = 0
                    repeat(8) { bit ->
                        val pixel = pixels[pixelBase + bit]
                        val r = pixel ushr 16 and 0xFF
                        val g = pixel ushr 8 and 0xFF
                        val b = pixel and 0xFF
                        val invR = 255 - r
                        val invG = 255 - g
                        val invB = 255 - b
                        val blackDistance = r * r + g * g + b * b
                        val whiteDistance = invR * invR + invG * invG + invB * invB
                        val redDistance = invR * invR + g * g + b * b

                        blackWhiteByte = blackWhiteByte shl 1
                        redByte = redByte shl 1
                        if (whiteDistance <= blackDistance && whiteDistance <= redDistance) {
                            blackWhiteByte = blackWhiteByte or 1
                        } else if (redDistance < blackDistance) {
                            redByte = redByte or 1
                        }
                    }
                    val index = row * BYTES_PER_ROW + byteColumn
                    blackWhite[index] = blackWhiteByte.toByte()
                    red[index] = redByte.toByte()
                }
            }
            return blackWhite to red
        }

    }

    fun flash(bitmap: Bitmap): Boolean {
        if (bitmap.width != WIDTH || bitmap.height != HEIGHT) {
            Log.e(TAG, "Expected ${WIDTH}x${HEIGHT}, got ${bitmap.width}x${bitmap.height}")
            return false
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "Tag does not expose android.nfc.tech.IsoDep; technologies=${tag.techList.joinToString()}")
            return false
        }

        return try {
            isoDep.connect()
            isoDep.timeout = ISO_DEP_TIMEOUT_MS
            Log.i(TAG, "Connected over IsoDep; maxTransceiveLength=${isoDep.maxTransceiveLength}")
            if (isoDep.maxTransceiveLength < CHUNK_SIZE + 5) {
                throw IOException("IsoDep max transceive length ${isoDep.maxTransceiveLength} is below ${CHUNK_SIZE + 5}")
            }

            val (blackWhite, red) = encode(bitmap)
            writeDisplay(isoDep, blackWhite, red)
            true
        } catch (error: Exception) {
            Log.e(TAG, "ISO-DEP write failed: ${error.message}", error)
            false
        } finally {
            try {
                isoDep.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun writeDisplay(isoDep: IsoDep, blackWhite: ByteArray, red: ByteArray) {
        onProgress(0)
        transceiveChecked(isoDep, INIT, "initialize")
        transceiveChecked(isoDep, GPIO_0, "power off")
        SystemClock.sleep(50)
        transceiveChecked(isoDep, GPIO_1, "power on")
        SystemClock.sleep(50)

        writeRegister(isoDep, 0x01, 0xC7, 0x00, 0x01)
        writeRegister(isoDep, 0x11, 0x01)
        writeRegister(isoDep, 0x44, 0x00, 0x18)
        writeRegister(isoDep, 0x45, 0xC7, 0x00, 0x00, 0x00)
        writeRegister(isoDep, 0x3C, 0x05)
        writeRegister(isoDep, 0x18, 0x80)
        writeRegister(isoDep, 0x4E, 0x00)
        writeRegister(isoDep, 0x4F, 0xC7, 0x00)
        SystemClock.sleep(100)
        onProgress(5)

        writePlane(isoDep, 0x24, blackWhite, 5, 45, "black/white")
        writePlane(isoDep, 0x26, red, 50, 90, "red")

        writeRegister(isoDep, 0x22, 0xF7)
        selectRegister(isoDep, 0x20)
        onProgress(95)
        SystemClock.sleep(INITIAL_REFRESH_DELAY_MS)

        repeat(MAX_STATUS_POLLS) { attempt ->
            val response = transceiveChecked(isoDep, READ_STATUS, "read status")
            if (response.size < 3) {
                throw IOException("Status response too short: ${response.toHexString()}")
            }
            if (response[0] == 0x01.toByte()) {
                onProgress(100)
                Log.i(TAG, "Display refresh complete after ${attempt + 1} status polls")
                return
            }
            SystemClock.sleep(STATUS_POLL_DELAY_MS)
        }

        throw IOException("Display remained busy after ${MAX_STATUS_POLLS * STATUS_POLL_DELAY_MS / 1_000}s")
    }

    private fun writeRegister(isoDep: IsoDep, register: Int, vararg values: Int) {
        selectRegister(isoDep, register)
        writeData(isoDep, bytes(*values), "write register 0x%02X".format(register))
    }

    private fun selectRegister(isoDep: IsoDep, register: Int) {
        transceiveChecked(
            isoDep,
            bytes(0x74, 0x99, 0x00, 0x0D, 0x01, register),
            "select register 0x%02X".format(register)
        )
    }

    private fun writePlane(
        isoDep: IsoDep,
        register: Int,
        data: ByteArray,
        progressStart: Int,
        progressEnd: Int,
        name: String
    ) {
        require(data.size == PLANE_SIZE)
        selectRegister(isoDep, register)
        val chunkCount = data.size / CHUNK_SIZE
        repeat(chunkCount) { chunkIndex ->
            val offset = chunkIndex * CHUNK_SIZE
            writeData(
                isoDep,
                data.copyOfRange(offset, offset + CHUNK_SIZE),
                "$name packet ${chunkIndex + 1}/$chunkCount"
            )
            onProgress(progressStart + (progressEnd - progressStart) * (chunkIndex + 1) / chunkCount)
        }
    }

    private fun writeData(isoDep: IsoDep, data: ByteArray, operation: String) {
        val command = ByteArray(5 + data.size)
        command[0] = 0x74
        command[1] = 0x9A.toByte()
        command[2] = 0x00
        command[3] = 0x0E
        command[4] = data.size.toByte()
        data.copyInto(command, destinationOffset = 5)
        transceiveChecked(isoDep, command, operation)
    }

    private fun transceiveChecked(isoDep: IsoDep, command: ByteArray, operation: String): ByteArray {
        val response = isoDep.transceive(command)
        if (response.size < 2 ||
            response[response.lastIndex - 1] != 0x90.toByte() ||
            response[response.lastIndex] != 0x00.toByte()
        ) {
            throw IOException("$operation rejected: ${response.toHexString()}")
        }
        Log.d(TAG, "$operation: ${response.toHexString()}")
        return response
    }

    private fun encode(bitmap: Bitmap): Pair<ByteArray, ByteArray> {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return encodePixels(pixels)
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
