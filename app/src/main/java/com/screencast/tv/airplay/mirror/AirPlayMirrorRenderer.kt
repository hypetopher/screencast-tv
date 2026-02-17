package com.screencast.tv.airplay.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AirPlayMirrorRenderer {
    private const val TAG = "AirPlayMirrorRenderer"
    private const val MIME_AVC = "video/avc"
    private const val TIMEOUT_US = 10_000L
    private const val WATCHDOG_MS = 2000L
    private const val WATCHDOG_MIN_FRAMES = 30

    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 1920
    private var height = 1080
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private var naluLengthSize: Int = 4
    private var frameCounter = 0L
    private var outputFrameCounter = 0L
    private var receivedPayloads = 0L
    private var droppedPayloads = 0L
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var firstInputTime = 0L
    private var hwDecoderFailed = false

    /** Listener for actual decoded video dimension changes (from crop rect). */
    var dimensionListener: ((videoWidth: Int, videoHeight: Int) -> Unit)? = null

    /** Listener called when hardware decoder is not available. */
    var errorListener: ((message: String) -> Unit)? = null

    fun attachSurface(newSurface: Surface) {
        synchronized(lock) {
            surface = newSurface
            recreateCodecLocked()
        }
    }

    fun detachSurface(oldSurface: Surface) {
        synchronized(lock) {
            if (surface === oldSurface) {
                releaseCodecLocked()
                surface = null
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            releaseCodecLocked()
            csd0 = null
            csd1 = null
            naluLengthSize = 4
            frameCounter = 0
            outputFrameCounter = 0
            receivedPayloads = 0
            droppedPayloads = 0
            lastVideoWidth = 0
            lastVideoHeight = 0
            firstInputTime = 0L
            hwDecoderFailed = false
        }
    }

    fun onCodecConfig(avccPayload: ByteArray, announcedWidth: Int, announcedHeight: Int) {
        val config = parseAvcc(avccPayload)
        if (config == null) {
            Log.w(TAG, "Failed to parse AVC decoder config, len=${avccPayload.size}")
            return
        }
        synchronized(lock) {
            if (announcedWidth >= 16) width = announcedWidth
            if (announcedHeight >= 16) height = announcedHeight
            csd0 = config.csd0
            csd1 = config.csd1
            naluLengthSize = config.naluLengthSize
            Log.d(TAG, "Codec config: ${width}x$height, csd0=${csd0?.size}, csd1=${csd1?.size}, naluLenBytes=$naluLengthSize")
            recreateCodecLocked()
        }
    }

    fun onVideoPayload(payload: ByteArray) {
        receivedPayloads++
        if (hwDecoderFailed) return

        val annexB = avccToAnnexB(payload)
        if (annexB == null) {
            droppedPayloads++
            if (droppedPayloads <= 5 || droppedPayloads % 240L == 0L) {
                val prefix = payload.copyOfRange(0, kotlin.math.min(payload.size, 32))
                    .joinToString("") { "%02x".format(it) }
                Log.d(TAG, "Dropped payload #$droppedPayloads/$receivedPayloads len=${payload.size} prefix=$prefix")
            }
            return
        }
        synchronized(lock) {
            // Detect inline SPS/PPS that indicate resolution/orientation change
            if (annexB.size > 4 && (annexB[4].toInt() and 0x1F) == 7) {
                handleInlineSpsChange(annexB)
            }

            if (codec == null) {
                val bitstreamConfig = extractSpsPpsFromAnnexB(annexB)
                if (bitstreamConfig != null) {
                    csd0 = bitstreamConfig.first
                    csd1 = bitstreamConfig.second
                    naluLengthSize = 4
                    Log.d(TAG, "Codec config from bitstream, csd0=${csd0?.size}, csd1=${csd1?.size}")
                    recreateCodecLocked()
                }
            }
            val c = codec ?: return

            drainOutputLocked(c)

            val inputIndex = try {
                c.dequeueInputBuffer(TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueInputBuffer failed: ${e.message}")
                return
            }
            if (inputIndex < 0) {
                drainOutputLocked(c)
                return
            }
            try {
                val inputBuffer = c.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(annexB)
                if (frameCounter < 5) {
                    val nalType = if (annexB.size > 4) annexB[4].toInt() and 0x1F else -1
                    Log.d(TAG, "Queue frame#$frameCounter bytes=${annexB.size} firstNalType=$nalType")
                }
                c.queueInputBuffer(inputIndex, 0, annexB.size, System.nanoTime() / 1000L, 0)
                drainOutputLocked(c)
                frameCounter++
                if (firstInputTime == 0L) firstInputTime = System.currentTimeMillis()
                if (frameCounter % 120 == 0L) {
                    val elapsed = (System.currentTimeMillis() - firstInputTime) / 1000.0
                    val fps = if (elapsed > 0) "%.1f".format(outputFrameCounter / elapsed) else "?"
                    Log.i(TAG, "Frames in=$frameCounter out=$outputFrameCounter fps=$fps dropped=$droppedPayloads/$receivedPayloads")
                }
                // Watchdog: if hardware decoder produces no output, notify and stop
                if (frameCounter >= WATCHDOG_MIN_FRAMES && outputFrameCounter == 0L &&
                    System.currentTimeMillis() - firstInputTime > WATCHDOG_MS) {
                    Log.e(TAG, "Hardware decoder produced 0 output after $frameCounter frames in ${WATCHDOG_MS}ms")
                    hwDecoderFailed = true
                    releaseCodecLocked()
                    errorListener?.invoke("Hardware video decoder not supported on this device")
                    return
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed: ${e.message}")
            }
        }
    }

    /**
     * When a video payload starts with SPS (nalType=7), check if resolution changed.
     * If so, update csd0/csd1 and recreate the codec for the new orientation.
     * Must be called within synchronized(lock).
     */
    private fun handleInlineSpsChange(annexB: ByteArray) {
        val inlineConfig = extractSpsPpsFromAnnexB(annexB) ?: return
        val (newCsd0, newCsd1) = inlineConfig
        val currentCsd0 = csd0
        val currentCsd1 = csd1
        if (currentCsd0 != null && currentCsd1 != null &&
            newCsd0.contentEquals(currentCsd0) && newCsd1.contentEquals(currentCsd1)) {
            return // No change
        }
        Log.d(TAG, "Inline SPS/PPS change detected, recreating codec")
        csd0 = newCsd0
        csd1 = newCsd1
        recreateCodecLocked()
    }

    private fun recreateCodecLocked() {
        releaseCodecLocked()
        if (hwDecoderFailed) return
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(localCsd0))
            setByteBuffer("csd-1", ByteBuffer.wrap(localCsd1))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time priority
            try { setInteger("low-latency", 1) } catch (_: Exception) {}
        }

        try {
            val hwCodec = MediaCodec.createDecoderByType(MIME_AVC)
            Log.i(TAG, "Hardware decoder: ${hwCodec.name} for ${width}x$height")
            hwCodec.configure(format, s, null, 0)
            hwCodec.start()
            codec = hwCodec
            frameCounter = 0
            outputFrameCounter = 0
            firstInputTime = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create hardware decoder", e)
            releaseCodecLocked()
            hwDecoderFailed = true
            errorListener?.invoke("Hardware video decoder not available: ${e.message}")
        }
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        codec = null
    }

    private fun drainOutputLocked(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                return
            }
            when {
                outputIndex >= 0 -> {
                    c.releaseOutputBuffer(outputIndex, true)
                    outputFrameCounter++
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    Log.d(TAG, "Decoder format: $fmt")
                    notifyDimensionsFromFormat(fmt)
                }
                else -> return
            }
        }
    }

    /**
     * Extract actual video content dimensions from the MediaCodec output format
     * using the crop rectangle. Notifies the dimension listener if changed.
     */
    private fun notifyDimensionsFromFormat(fmt: MediaFormat) {
        val cropLeft = if (fmt.containsKey("crop-left")) fmt.getInteger("crop-left") else 0
        val cropTop = if (fmt.containsKey("crop-top")) fmt.getInteger("crop-top") else 0
        val cropRight = if (fmt.containsKey("crop-right")) fmt.getInteger("crop-right") else {
            if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) - 1 else width - 1
        }
        val cropBottom = if (fmt.containsKey("crop-bottom")) fmt.getInteger("crop-bottom") else {
            if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) - 1 else height - 1
        }
        val videoWidth = cropRight - cropLeft + 1
        val videoHeight = cropBottom - cropTop + 1
        if (videoWidth > 0 && videoHeight > 0 &&
            (videoWidth != lastVideoWidth || videoHeight != lastVideoHeight)) {
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
            Log.d(TAG, "Video dimensions: ${videoWidth}x$videoHeight")
            dimensionListener?.invoke(videoWidth, videoHeight)
        }
    }

    private data class AvccConfig(
        val csd0: ByteArray,
        val csd1: ByteArray,
        val naluLengthSize: Int
    )

    private fun parseAvcc(payload: ByteArray): AvccConfig? {
        if (payload.size < 11) return null
        val lengthSizeMinusOne = payload[4].toInt() and 0x03
        val naluLenBytes = (lengthSizeMinusOne + 1).coerceIn(1, 4)
        val numSps = payload[5].toInt() and 0x1F
        if (numSps < 1) return null
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        if (spsSize <= 0 || 8 + spsSize >= payload.size) return null
        val spsStart = 8
        val ppsCountIndex = spsStart + spsSize
        if (ppsCountIndex + 2 >= payload.size) return null
        val ppsCount = payload[ppsCountIndex].toInt() and 0xFF
        if (ppsCount < 1) return null
        val ppsSize = ((payload[ppsCountIndex + 1].toInt() and 0xFF) shl 8) or
            (payload[ppsCountIndex + 2].toInt() and 0xFF)
        val ppsStart = ppsCountIndex + 3
        if (ppsSize <= 0 || ppsStart + ppsSize > payload.size) return null

        val sps = payload.copyOfRange(spsStart, spsStart + spsSize)
        val pps = payload.copyOfRange(ppsStart, ppsStart + ppsSize)
        val csd0 = byteArrayOf(0, 0, 0, 1) + sps
        val csd1 = byteArrayOf(0, 0, 0, 1) + pps
        return AvccConfig(csd0 = csd0, csd1 = csd1, naluLengthSize = naluLenBytes)
    }

    /**
     * Convert AVCC (length-prefixed) NAL units to AnnexB (start-code-prefixed) format.
     */
    private fun avccToAnnexB(payload: ByteArray): ByteArray? {
        if (payload.size < 5) return null

        val lenBytes = synchronized(lock) { naluLengthSize }

        // Primary: AVCC with configured NAL length size, big-endian
        convertLengthPrefixed(payload, 0, ByteOrder.BIG_ENDIAN, lenBytes)?.let { return it }

        // Fallback: try 4-byte LE (some non-standard senders)
        if (lenBytes == 4) {
            convertLengthPrefixed(payload, 0, ByteOrder.LITTLE_ENDIAN, lenBytes)?.let { return it }
        }

        // Fallback: if naluLengthSize != 4, try with 4-byte BE
        if (lenBytes != 4) {
            convertLengthPrefixed(payload, 0, ByteOrder.BIG_ENDIAN, 4)?.let { return it }
        }

        // Last resort: check if data is already AnnexB (e.g., unencrypted stream)
        if (isAnnexB(payload)) return payload

        return null
    }

    private fun convertLengthPrefixed(payload: ByteArray, offset: Int, order: ByteOrder, lenBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(payload.size + 64)
        var pos = offset
        var nals = 0
        while (pos + lenBytes <= payload.size) {
            val naluLen = readUnsignedLen(payload, pos, lenBytes, order)
            if (naluLen <= 0 || naluLen > payload.size - pos - lenBytes) return null
            val start = pos + lenBytes
            val end = start + naluLen
            if (end > payload.size) return null
            val nal0 = payload[start].toInt() and 0xFF
            if ((nal0 and 0x80) != 0) return null
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(payload, start, naluLen)
            pos = end
            nals++
            if (nals > 128) return null
        }
        return if (pos == payload.size && nals > 0) out.toByteArray() else null
    }

    private fun readUnsignedLen(payload: ByteArray, offset: Int, lenBytes: Int, order: ByteOrder): Int {
        var v = 0
        if (order == ByteOrder.BIG_ENDIAN) {
            for (i in 0 until lenBytes) {
                v = (v shl 8) or (payload[offset + i].toInt() and 0xFF)
            }
        } else {
            for (i in lenBytes - 1 downTo 0) {
                v = (v shl 8) or (payload[offset + i].toInt() and 0xFF)
            }
        }
        return v
    }

    private fun isAnnexB(payload: ByteArray): Boolean {
        return payload.size >= 5 &&
            payload[0] == 0.toByte() &&
            payload[1] == 0.toByte() &&
            payload[2] == 0.toByte() &&
            payload[3] == 1.toByte() &&
            (payload[4].toInt() and 0x80) == 0
    }

    private fun extractSpsPpsFromAnnexB(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val nals = splitAnnexBNals(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            if (nalType == 7) sps = nal
            if (nalType == 8) pps = nal
            if (sps != null && pps != null) break
        }
        if (sps == null || pps == null) return null
        return (byteArrayOf(0, 0, 0, 1) + sps) to (byteArrayOf(0, 0, 0, 1) + pps)
    }

    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1
        while (i + 3 < data.size) {
            val isStart = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (isStart) {
                if (nalStart >= 0 && nalStart < i) {
                    out.add(data.copyOfRange(nalStart, i))
                }
                nalStart = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (nalStart >= 0 && nalStart < data.size) {
            out.add(data.copyOfRange(nalStart, data.size))
        }
        return out
    }
}
