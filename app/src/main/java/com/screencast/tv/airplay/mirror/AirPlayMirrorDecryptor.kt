package com.screencast.tv.airplay.mirror

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay mirror stream decryptor using AES-128-CTR.
 *
 * AirPlay mirroring uses a continuous stream cipher: the CTR counter state
 * persists across packets within a session. Each call to [decrypt] advances
 * the counter by the number of bytes processed.
 */
class AirPlayMirrorDecryptor private constructor(
    private val streamKey: ByteArray,
    private val streamIv: ByteArray
) {
    private val lock = Any()
    private var streamCipher: Cipher = newCipher()

    /**
     * Decrypt a video payload using the continuous stream cipher.
     * The cipher state advances by [payload].size bytes.
     */
    fun decrypt(payload: ByteArray): ByteArray {
        return synchronized(lock) {
            streamCipher.update(payload) ?: payload
        }
    }

    private fun newCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(streamKey, "AES"),
            IvParameterSpec(streamIv)
        )
        return cipher
    }

    companion object {
        fun create(rawAesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long): AirPlayMirrorDecryptor {
            require(rawAesKey.size == 16)
            require(ecdhSecret.size >= 32)

            val sha = MessageDigest.getInstance("SHA-512")
            val eaesKey = sha.digest(rawAesKey + ecdhSecret.copyOf(32)).copyOf(16)

            val streamIdText = streamConnectionId.toULong().toString()
            val keyMaterial = sha.digest("AirPlayStreamKey$streamIdText".toByteArray(Charsets.UTF_8) + eaesKey)
            val ivMaterial = sha.digest("AirPlayStreamIV$streamIdText".toByteArray(Charsets.UTF_8) + eaesKey)
            val streamKey = keyMaterial.copyOf(16)
            val streamIv = ivMaterial.copyOf(16)
            return AirPlayMirrorDecryptor(streamKey, streamIv)
        }
    }
}
