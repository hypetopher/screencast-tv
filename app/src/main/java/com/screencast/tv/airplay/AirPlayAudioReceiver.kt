package com.screencast.tv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay audio receiver for screen mirroring.
 *
 * Audio is sent as RTP over UDP with AAC-ELD encoding (ct=8).
 * Each RTP packet is independently encrypted with AES-128-CBC.
 * Packets are reordered via a jitter buffer before decoding.
 */
class AirPlayAudioReceiver {
    companion object {
        private const val TAG = "AirPlayAudio"
        private const val RTP_HEADER_SIZE = 12
        private const val MAX_PACKET_SIZE = 32768
        private const val MIN_AUDIO_PAYLOAD = 16
        // Jitter buffer size (must be power of 2)
        private const val BUFFER_SIZE = 512
        private const val BUFFER_MASK = BUFFER_SIZE - 1
        // How many packets to buffer before starting playback
        private const val BUFFER_START_FILL = 8
    }

    @Volatile private var running = false
    @Volatile private var dataSocket: DatagramSocket? = null
    @Volatile private var controlSocket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var aesKey: SecretKeySpec? = null
    private var aesIv: ByteArray? = null

    // Jitter buffer: ring buffer indexed by seqNum % BUFFER_SIZE
    private val jitterBuffer = arrayOfNulls<ByteArray>(BUFFER_SIZE)
    private val jitterSeqNums = IntArray(BUFFER_SIZE) { -1 }
    private var firstSeqNum = -1
    private var lastSeqNum = -1
    private var bufferFilling = true

    val dataPort: Int get() = dataSocket?.localPort ?: -1
    val controlPort: Int get() = controlSocket?.localPort ?: -1

    fun start(preferredDataPort: Int, preferredControlPort: Int) {
        if (running) return
        running = true

        dataSocket = try {
            DatagramSocket(preferredDataPort)
        } catch (e: Exception) {
            Log.w(TAG, "Preferred data port $preferredDataPort unavailable: ${e.message}")
            DatagramSocket(0)
        }
        controlSocket = try {
            DatagramSocket(preferredControlPort)
        } catch (e: Exception) {
            Log.w(TAG, "Preferred control port $preferredControlPort unavailable: ${e.message}")
            DatagramSocket(0)
        }

        dataSocket?.soTimeout = 1000
        controlSocket?.soTimeout = 1000

        Log.d(TAG, "Audio UDP sockets: data=${dataSocket?.localPort}, control=${controlSocket?.localPort}")

        Thread({
            val buf = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    controlSocket?.receive(packet)
                } catch (_: Exception) {}
            }
        }, "AirPlayAudioControl").start()

        receiveThread = Thread({
            receiveLoop()
        }, "AirPlayAudioData").also { it.start() }
    }

    fun configureDecryption(eaesKey: ByteArray, eiv: ByteArray) {
        require(eaesKey.size == 16) { "AES key must be 16 bytes" }
        require(eiv.size == 16) { "AES IV must be 16 bytes" }
        aesKey = SecretKeySpec(eaesKey, "AES")
        aesIv = eiv.copyOf()
        val keyHex = eaesKey.joinToString("") { "%02x".format(it) }
        val ivHex = eiv.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Audio decryption configured key=$keyHex iv=$ivHex")
    }

    fun stop() {
        running = false
        try { dataSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        dataSocket = null
        controlSocket = null
        releaseCodec()
        releaseAudioTrack()
        aesKey = null
        aesIv = null
        clearJitterBuffer()
    }

    private fun clearJitterBuffer() {
        for (i in jitterBuffer.indices) {
            jitterBuffer[i] = null
            jitterSeqNums[i] = -1
        }
        firstSeqNum = -1
        lastSeqNum = -1
        bufferFilling = true
    }

    /** Compare two 16-bit sequence numbers accounting for wraparound */
    private fun seqCompare(a: Int, b: Int): Int {
        val diff = (a - b + 0x10000) % 0x10000
        return if (diff == 0) 0
        else if (diff < 0x8000) 1  // a > b
        else -1  // a < b (wrapped)
    }

    private fun receiveLoop() {
        val buf = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        var packetsReceived = 0L
        var packetsDecoded = 0L
        var packetsSkipped = 0L
        var duplicates = 0L
        var outOfOrder = 0L

        Log.d(TAG, "Audio receive loop started on port ${dataSocket?.localPort}")

        while (running) {
            try {
                dataSocket?.receive(packet) ?: break
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Audio receive error: ${e.message}")
                break
            }

            val len = packet.length
            if (len < RTP_HEADER_SIZE) continue

            packetsReceived++

            // Parse RTP header
            val byte0 = buf[0].toInt() and 0xFF
            val hasExtension = (byte0 and 0x10) != 0
            val csrcCount = byte0 and 0x0F
            val seqNum = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)

            // Calculate actual header size
            var headerSize = RTP_HEADER_SIZE + csrcCount * 4
            if (hasExtension && headerSize + 4 <= len) {
                val extLen = ((buf[headerSize + 2].toInt() and 0xFF) shl 8) or (buf[headerSize + 3].toInt() and 0xFF)
                headerSize += 4 + extLen * 4
            }

            if (headerSize > len) {
                packetsSkipped++
                continue
            }
            val audioLen = len - headerSize
            if (audioLen < MIN_AUDIO_PAYLOAD) {
                packetsSkipped++
                continue
            }

            // Decrypt audio payload
            val audioData = buf.copyOfRange(headerSize, headerSize + audioLen)
            val decrypted = decryptAudio(audioData)

            if (packetsDecoded < 3) {
                val decPrefix = decrypted.copyOfRange(0, kotlin.math.min(decrypted.size, 8))
                    .joinToString("") { "%02x".format(it) }
                Log.d(TAG, "Audio pkt seq=$seqNum size=${decrypted.size} dec=$decPrefix")
            }

            // Store in jitter buffer
            val slot = seqNum and BUFFER_MASK
            if (jitterSeqNums[slot] == seqNum) {
                // Duplicate
                duplicates++
                continue
            }

            jitterBuffer[slot] = decrypted
            jitterSeqNums[slot] = seqNum

            if (firstSeqNum < 0) {
                firstSeqNum = seqNum
                lastSeqNum = seqNum
                bufferFilling = true
            } else {
                if (seqCompare(seqNum, lastSeqNum) > 0) {
                    lastSeqNum = seqNum
                } else {
                    outOfOrder++
                }
            }

            // Wait until we have enough packets before starting playback
            if (bufferFilling) {
                val buffered = (lastSeqNum - firstSeqNum + 0x10000) % 0x10000 + 1
                if (buffered >= BUFFER_START_FILL) {
                    bufferFilling = false
                    Log.d(TAG, "Jitter buffer filled: $buffered packets, starting playback from seq=$firstSeqNum")
                }
                continue
            }

            // Drain jitter buffer in order up to (lastSeqNum - BUFFER_START_FILL/2)
            val playUpTo = (lastSeqNum - BUFFER_START_FILL / 2 + 0x10000) % 0x10000
            while (seqCompare(firstSeqNum, playUpTo) <= 0) {
                val playSlot = firstSeqNum and BUFFER_MASK
                val frame = jitterBuffer[playSlot]
                if (frame != null) {
                    if (feedDecoder(frame)) {
                        packetsDecoded++
                    }
                    jitterBuffer[playSlot] = null
                    jitterSeqNums[playSlot] = -1
                }
                // Missing packet — skip (decoder handles gaps)
                firstSeqNum = (firstSeqNum + 1) and 0xFFFF
            }

            if (packetsReceived % 500 == 0L) {
                val buffered = (lastSeqNum - firstSeqNum + 0x10000) % 0x10000
                Log.d(TAG, "Audio: recv=$packetsReceived decoded=$packetsDecoded skip=$packetsSkipped dupes=$duplicates ooo=$outOfOrder buf=$buffered")
            }
        }

        Log.d(TAG, "Audio receive loop ended: received=$packetsReceived decoded=$packetsDecoded ooo=$outOfOrder")
    }

    /**
     * Decrypt audio payload using AES-128-CBC.
     * Only full 16-byte blocks are decrypted; trailing bytes are copied as-is.
     * Each packet uses the original IV (not chained across packets).
     */
    private fun decryptAudio(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data

        val encryptedLen = (data.size / 16) * 16
        if (encryptedLen == 0) return data

        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val decrypted = ByteArray(data.size)
            val decryptedBlock = cipher.doFinal(data, 0, encryptedLen)
            System.arraycopy(decryptedBlock, 0, decrypted, 0, encryptedLen)
            if (data.size > encryptedLen) {
                System.arraycopy(data, encryptedLen, decrypted, encryptedLen, data.size - encryptedLen)
            }
            decrypted
        } catch (e: Exception) {
            Log.w(TAG, "Audio decrypt failed: ${e.message}")
            data
        }
    }

    private fun feedDecoder(data: ByteArray): Boolean {
        val c = ensureCodec() ?: return false

        val inputIndex = try {
            c.dequeueInputBuffer(5000)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Codec in bad state, recreating: ${e.message}")
            releaseCodec()
            return false
        } catch (e: Exception) {
            Log.w(TAG, "dequeueInputBuffer failed: ${e.message}")
            return false
        }
        if (inputIndex < 0) {
            drainOutput(c)
            return false
        }

        try {
            val inputBuffer = c.getInputBuffer(inputIndex) ?: return false
            inputBuffer.clear()
            inputBuffer.put(data)
            c.queueInputBuffer(inputIndex, 0, data.size, System.nanoTime() / 1000L, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer failed: ${e.message}")
            return false
        }

        drainOutput(c)
        return true
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                return
            }
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = c.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(pcm, 0, info.size)
                        playAudio(pcm)
                    }
                    c.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    Log.d(TAG, "Audio decoder output format: $fmt")
                    val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val currentTrack = audioTrack
                    if (currentTrack != null && sr != 44100) {
                        Log.d(TAG, "Sample rate changed to $sr, recreating AudioTrack")
                        try { currentTrack.stop() } catch (_: Exception) {}
                        try { currentTrack.release() } catch (_: Exception) {}
                        audioTrack = null
                        createAudioTrack(sr, ch)
                    }
                }
                else -> return
            }
        }
    }

    private fun ensureCodec(): MediaCodec? {
        codec?.let { return it }
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)

            // AudioSpecificConfig for AAC-ELD 44100Hz stereo 480SPF (no LD-SBR)
            val asc = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))

            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            Log.d(TAG, "AAC-ELD decoder started (44100Hz stereo 480SPF)")
            c
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AAC decoder: ${e.message}")
            null
        }
    }

    private fun playAudio(pcm: ByteArray) {
        val track = ensureAudioTrack() ?: return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write failed: ${e.message}")
        }
    }

    private fun ensureAudioTrack(): AudioTrack? {
        audioTrack?.let { return it }
        return createAudioTrack(44100, 2)
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int): AudioTrack? {
        return try {
            val channelConfig = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track
            Log.d(TAG, "AudioTrack started: ${sampleRate}Hz ${channels}ch, buffer=$bufferSize")
            track
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}")
            null
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        codec = null
    }

    private fun releaseAudioTrack() {
        val t = audioTrack ?: return
        try { t.stop() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
        audioTrack = null
    }
}
