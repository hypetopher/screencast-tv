package com.screencast.tv.airplay.mirror

import android.util.Log
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AirPlayMirrorStreamProcessor {
    companion object {
        private const val TAG = "AirPlayMirrorStream"
        private const val HEADER_SIZE = 128
        private const val MAX_PAYLOAD = 2 * 1024 * 1024
        // Video frame queue capacity — buffers frames between TCP reader and decoder
        private const val FRAME_QUEUE_CAPACITY = 30
    }
    private val lock = Any()
    private var decryptor: AirPlayMirrorDecryptor? = null
    private var configuredStreamConnectionId: Long? = null

    // Frame queue for producer-consumer buffering
    private sealed class MirrorPacket {
        data class Video(val payload: ByteArray) : MirrorPacket()
        data class Config(val payload: ByteArray, val width: Int, val height: Int) : MirrorPacket()
        object End : MirrorPacket()
    }

    fun configureDecryptor(rawAesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long) {
        synchronized(lock) {
            if (decryptor != null && configuredStreamConnectionId == streamConnectionId) {
                Log.d(TAG, "Mirror decryptor already configured for streamConnectionID=${streamConnectionId.toULong()}, skipping")
                return
            }
            decryptor = AirPlayMirrorDecryptor.create(rawAesKey, ecdhSecret, streamConnectionId)
            configuredStreamConnectionId = streamConnectionId
        }
        Log.d(TAG, "Mirror decryptor configured for streamConnectionID=${streamConnectionId.toULong()}")
    }

    fun clearDecryptor() {
        synchronized(lock) {
            decryptor = null
            configuredStreamConnectionId = null
        }
    }

    fun processSocket(socket: Socket) {
        val frameQueue = ArrayBlockingQueue<MirrorPacket>(FRAME_QUEUE_CAPACITY)

        // Start decoder thread (consumer)
        val decoderThread = Thread({
            decoderLoop(frameQueue)
        }, "MirrorDecoder")
        decoderThread.start()

        // TCP reader (producer) runs on current thread
        try {
            readerLoop(socket, frameQueue)
        } finally {
            // Signal decoder thread to stop
            frameQueue.offer(MirrorPacket.End, 1, TimeUnit.SECONDS)
            decoderThread.join(3000)
        }
    }

    private fun readerLoop(socket: Socket, queue: ArrayBlockingQueue<MirrorPacket>) {
        val input = socket.getInputStream()
        val header = ByteArray(HEADER_SIZE)
        var videoPackets = 0L
        var configPackets = 0L
        var headerLogs = 0
        var dropped = 0L

        while (true) {
            if (!readFully(input, header, 0, HEADER_SIZE)) break

            // Payload size is little-endian uint32 at offset 0
            val payloadSize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                val payloadSizeBe = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                if (payloadSizeBe in 1..MAX_PAYLOAD) {
                    Log.w(TAG, "Using BE payload size=$payloadSizeBe (LE was $payloadSize)")
                    val payload = ByteArray(payloadSizeBe)
                    if (!readFully(input, payload, 0, payloadSizeBe)) break
                    processAndEnqueue(header, payload, queue)
                    continue
                }
                Log.w(TAG, "Invalid mirror payloadSize=$payloadSize, header=${header.copyOfRange(0, 16).toHex()}")
                break
            }

            val payload = ByteArray(payloadSize)
            if (!readFully(input, payload, 0, payloadSize)) break

            val payloadType = header[4].toInt() and 0xFF

            if (headerLogs < 8) {
                headerLogs++
                Log.d(TAG, "Header#$headerLogs len=$payloadSize type=$payloadType")
            }

            when (payloadType) {
                0 -> {
                    val decrypted = decryptPayload(payload)
                    val packet = MirrorPacket.Video(decrypted)
                    // Non-blocking offer — if queue is full, drop oldest frame to make room
                    if (!queue.offer(packet)) {
                        queue.poll() // drop oldest
                        queue.offer(packet)
                        dropped++
                        if (dropped % 10 == 1L) {
                            Log.w(TAG, "Frame queue full, dropped=$dropped")
                        }
                    }
                    videoPackets++
                    if (videoPackets % 240 == 0L) {
                        Log.d(TAG, "Video packets=$videoPackets dropped=$dropped queueSize=${queue.size}")
                    }
                }
                1 -> {
                    val width = readFloatBE(header, 56).roundToInt().coerceAtLeast(1)
                    val height = readFloatBE(header, 60).roundToInt().coerceAtLeast(1)
                    // Config packets go to front of queue (high priority)
                    queue.put(MirrorPacket.Config(payload, width, height))
                    configPackets++
                    Log.d(TAG, "Config packet#$configPackets size=${payload.size} ${width}x$height")
                }
                else -> {
                    Log.d(TAG, "Ignoring mirror payloadType=$payloadType size=$payloadSize")
                }
            }
        }
        Log.d(TAG, "Mirror stream ended: videoPackets=$videoPackets configPackets=$configPackets dropped=$dropped")
    }

    private fun decoderLoop(queue: ArrayBlockingQueue<MirrorPacket>) {
        Log.d(TAG, "Decoder thread started")
        var decoded = 0L
        while (true) {
            val packet = try {
                queue.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            when (packet) {
                is MirrorPacket.Video -> {
                    AirPlayMirrorRenderer.onVideoPayload(packet.payload)
                    decoded++
                }
                is MirrorPacket.Config -> {
                    AirPlayMirrorRenderer.onCodecConfig(packet.payload, packet.width, packet.height)
                }
                is MirrorPacket.End -> break
            }
        }
        Log.d(TAG, "Decoder thread ended: decoded=$decoded")
    }

    private fun processAndEnqueue(header: ByteArray, payload: ByteArray, queue: ArrayBlockingQueue<MirrorPacket>) {
        val payloadType = header[4].toInt() and 0xFF
        when (payloadType) {
            0 -> {
                val decrypted = decryptPayload(payload)
                queue.offer(MirrorPacket.Video(decrypted))
            }
            1 -> {
                val width = readFloatBE(header, 56).roundToInt().coerceAtLeast(1)
                val height = readFloatBE(header, 60).roundToInt().coerceAtLeast(1)
                queue.offer(MirrorPacket.Config(payload, width, height))
            }
            else -> Log.d(TAG, "Ignoring mirror payloadType=$payloadType size=${payload.size}")
        }
    }

    /**
     * Decrypt a type-0 video payload using the continuous stream cipher.
     * Always decrypts unconditionally - AirPlay type 0 payloads are always encrypted
     * when FairPlay is active.
     */
    private fun decryptPayload(payload: ByteArray): ByteArray {
        val d = synchronized(lock) { decryptor } ?: return payload
        return d.decrypt(payload)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun readFloatBE(payload: ByteArray, offset: Int): Float {
        if (offset + 4 > payload.size) return 0f
        return ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).float
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(target, offset + read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }
}
