package com.screencast.tv.airplay

import android.util.Log
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AirPlay pair-setup (transient) and pair-verify.
 *
 * Uses net.i2p.crypto:eddsa for Ed25519 (pure Java, works on all Android versions).
 * Uses manual Curve25519 implementation for X25519 ECDH.
 */
class AirPlayPairing {

    companion object {
        private const val TAG = "AirPlayPairing"
        private val ED25519_SPEC = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    }

    // Long-lived Ed25519 identity key pair
    private val edSeed: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val edPrivateKey: EdDSAPrivateKey
    private val edPublicKey: EdDSAPublicKey
    val edPublicKeyRaw: ByteArray

    // Per-session Curve25519 state
    private var curvePrivateKey: ByteArray? = null
    private var curvePublicKey: ByteArray? = null
    private var pairVerifyAesKey: ByteArray? = null
    private var pairVerifyAesIv: ByteArray? = null
    private var pairVerifySharedSecret: ByteArray? = null

    init {
        val privKeySpec = EdDSAPrivateKeySpec(edSeed, ED25519_SPEC)
        edPrivateKey = EdDSAPrivateKey(privKeySpec)

        val pubKeySpec = EdDSAPublicKeySpec(edPrivateKey.a, ED25519_SPEC)
        edPublicKey = EdDSAPublicKey(pubKeySpec)
        edPublicKeyRaw = edPublicKey.abyte

        Log.d(TAG, "Ed25519 public key (${edPublicKeyRaw.size} bytes): ${edPublicKeyRaw.toHex()}")
    }

    /** Get the Ed25519 public key as hex string for mDNS pk field */
    fun getPublicKeyHex(): String = edPublicKeyRaw.toHex()

    fun getPairVerifySharedSecret(): ByteArray? = pairVerifySharedSecret?.copyOf()

    /**
     * Handle /pair-setup request body, return response body
     */
    fun handlePairSetup(body: ByteArray): ByteArray {
        // Some iOS clients send transient pair-setup as a raw 32-byte key payload.
        if (body.size == 32) {
            Log.d(TAG, "pair-setup: detected raw 32-byte client key, replying with server Ed25519 key")
            Log.d(TAG, "pair-setup raw body hex: ${body.toHex()}")
            return edPublicKeyRaw.copyOf()
        }

        if (body.size < 4) {
            Log.w(TAG, "pair-setup: body too short (${body.size} bytes)")
            return ByteArray(0)
        }

        val msgType = body[0].toInt() and 0xFF
        Log.d(TAG, "pair-setup message type: $msgType, body size: ${body.size}")
        Log.d(TAG, "pair-setup body hex: ${body.take(64).toByteArray().toHex()}")

        return when (msgType) {
            // M1: Client requests pairing
            0x01 -> {
                Log.d(TAG, "pair-setup M1: Client requesting transient pairing")
                // M2: Accept
                val response = ByteArray(4)
                response[0] = 0x02
                Log.d(TAG, "pair-setup M2: Accepting pairing")
                response
            }

            // M3: Client sends its Ed25519 public key
            0x03 -> {
                if (body.size >= 36) {
                    val clientEdPk = body.copyOfRange(4, 36)
                    Log.d(TAG, "pair-setup M3: Client Ed25519 PK: ${clientEdPk.toHex()}")
                }
                // M4: Send our Ed25519 public key
                val response = ByteArray(4 + 32)
                response[0] = 0x04
                System.arraycopy(edPublicKeyRaw, 0, response, 4, 32)
                Log.d(TAG, "pair-setup M4: Sending Ed25519 PK, response size: ${response.size}")
                response
            }

            // M5: Client sends encrypted proof
            0x05 -> {
                Log.d(TAG, "pair-setup M5: Client proof received (${body.size - 4} bytes payload)")
                // M6: Acknowledge
                val response = ByteArray(4)
                response[0] = 0x06
                Log.d(TAG, "pair-setup M6: Pairing complete")
                response
            }

            else -> {
                Log.w(TAG, "pair-setup: unknown message type $msgType")
                ByteArray(0)
            }
        }
    }

    /**
     * Handle /pair-verify request body, return response body
     */
    fun handlePairVerify(body: ByteArray): ByteArray {
        // Some clients send step-3 as raw 64-byte encrypted payload (no 4-byte prefix).
        if (body.size == 64) {
            Log.d(TAG, "pair-verify: detected raw 64-byte step-3 payload")
            return handlePairVerifyStep3(body, hasPrefix = false)
        }

        if (body.size < 4) {
            Log.w(TAG, "pair-verify: body too short (${body.size} bytes)")
            return ByteArray(0)
        }

        val msgType = body[0].toInt() and 0xFF
        Log.d(TAG, "pair-verify message type: $msgType, body size: ${body.size}")
        Log.d(TAG, "pair-verify body hex: ${body.take(64).toByteArray().toHex()}")

        return when (msgType) {
            // Step 1: Client sends its Curve25519 public key
            0x01 -> handlePairVerifyStep1(body)

            // Step 3: Client sends encrypted verification
            0x00 -> handlePairVerifyStep3(body, hasPrefix = true)

            else -> {
                Log.w(TAG, "pair-verify: unknown type $msgType, guessing by body size")
                if (body.size == 36 || body.size == 68) handlePairVerifyStep1(body)
                else handlePairVerifyStep3(body, hasPrefix = true)
            }
        }
    }

    private fun handlePairVerifyStep1(body: ByteArray): ByteArray {
        try {
            if (body.size < 36) {
                Log.e(TAG, "pair-verify step 1: body too short")
                return ByteArray(0)
            }

            val clientCurvePk = body.copyOfRange(4, 36)
            Log.d(TAG, "pair-verify step 1: Client Curve25519 PK: ${clientCurvePk.toHex()}")
            val clientEdPk: ByteArray? = if (body.size >= 68) {
                body.copyOfRange(36, 68).also {
                    Log.d(TAG, "pair-verify step 1: Client Ed25519 PK field: ${it.toHex()}")
                }
            } else {
                null
            }

            // Generate server Curve25519 key pair
            val serverPrivate = ByteArray(32).also { SecureRandom().nextBytes(it) }
            // Clamp private key per Curve25519 spec
            serverPrivate[0] = (serverPrivate[0].toInt() and 248).toByte()
            serverPrivate[31] = (serverPrivate[31].toInt() and 127 or 64).toByte()
            curvePrivateKey = serverPrivate
            val serverCurvePk = ByteArray(32)
            X25519.scalarMultBase(serverPrivate, 0, serverCurvePk, 0)
            curvePublicKey = serverCurvePk
            Log.d(TAG, "pair-verify step 1: Server Curve25519 PK: ${curvePublicKey!!.toHex()}")

            // Compute shared secret via ECDH
            val sharedSecret = ByteArray(32)
            X25519.scalarMult(serverPrivate, 0, clientCurvePk, 0, sharedSecret, 0)
            pairVerifySharedSecret = sharedSecret.copyOf()
            Log.d(TAG, "pair-verify step 1: Shared secret: ${sharedSecret.toHex()}")

            // Derive AES key and IV
            val md = MessageDigest.getInstance("SHA-512")
            pairVerifyAesKey = md.digest(
                "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8) + sharedSecret
            ).copyOf(16)
            md.reset()
            pairVerifyAesIv = md.digest(
                "Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8) + sharedSecret
            ).copyOf(16)

            Log.d(TAG, "pair-verify step 1: AES key: ${pairVerifyAesKey!!.toHex()}")
            Log.d(TAG, "pair-verify step 1: AES IV: ${pairVerifyAesIv!!.toHex()}")

            // Observed-good variant for this iOS sender:
            // sign(server_curve_pk + client_curve_pk)
            val dataToSign = curvePublicKey!! + clientCurvePk
            Log.d(TAG, "pair-verify step 1: sign strategy fixed (server_curve + client_curve)")
            val sgr = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            sgr.initSign(edPrivateKey)
            sgr.update(dataToSign)
            val signature = sgr.sign()
            Log.d(TAG, "pair-verify step 1: Signature (${signature.size} bytes): ${signature.toHex()}")

            // Encrypt the signature with AES-CTR
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(pairVerifyAesKey, "AES"),
                IvParameterSpec(pairVerifyAesIv)
            )
            val encryptedSig = cipher.doFinal(signature)
            Log.d(TAG, "pair-verify step 2: Encrypted sig (${encryptedSig.size} bytes)")

            // Pair-verify step-2 response is 96 bytes:
            // server_curve_pk(32) + encrypted_sig(64) (no 4-byte prefix)
            val response = curvePublicKey!! + encryptedSig
            Log.d(TAG, "pair-verify step 2: Response size: ${response.size}")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "pair-verify step 1 error", e)
            return ByteArray(0)
        }
    }

    private fun handlePairVerifyStep3(body: ByteArray, hasPrefix: Boolean): ByteArray {
        try {
            Log.d(TAG, "pair-verify step 3: Received ${body.size} bytes")

            if (pairVerifyAesKey != null && pairVerifyAesIv != null && body.isNotEmpty()) {
                val encryptedData = if (hasPrefix && body.size > 4) {
                    body.copyOfRange(4, body.size)
                } else {
                    body
                }
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(pairVerifyAesKey, "AES"),
                    IvParameterSpec(pairVerifyAesIv)
                )
                val decrypted = cipher.doFinal(encryptedData)
                Log.d(TAG, "pair-verify step 3: Decrypted ${decrypted.size} bytes: ${decrypted.take(64).toByteArray().toHex()}")
            }

            // Pair-verify step-4 should be an empty success payload.
            Log.d(TAG, "pair-verify step 4: Sending success (empty body)")
            return ByteArray(0)

        } catch (e: Exception) {
            Log.e(TAG, "pair-verify step 3 error", e)
            return ByteArray(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Pure Java Curve25519 (X25519) implementation for ECDH key exchange.
 * Based on the Curve25519 specification (RFC 7748).
 */
object Curve25519 {

    // Field prime p = 2^255 - 19
    private val P = java.math.BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val A24 = java.math.BigInteger.valueOf(121665) // (A-2)/4 where A=486662

    /**
     * Scalar multiplication with base point (for generating public key).
     */
    fun scalarMultBase(scalar: ByteArray): ByteArray {
        // Curve25519 base point u=9
        val basePoint = ByteArray(32)
        basePoint[0] = 9
        return scalarMult(scalar, basePoint)
    }

    /**
     * Scalar multiplication (Montgomery ladder).
     * Used for both key generation (with base point) and ECDH (with peer's public key).
     */
    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        // Decode scalar (with clamping already applied)
        val k = scalar.clone()

        // Decode u-coordinate of point
        val u = decodeLittleEndian(point)

        // Montgomery ladder
        var x1 = u
        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = u
        var z3 = java.math.BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kT = (k[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kT
            // Conditional swap
            if (swap == 1) {
                val tmp1 = x2; x2 = x3; x3 = tmp1
                val tmp2 = z2; z2 = z3; z3 = tmp2
            }
            swap = kT

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).modPow(java.math.BigInteger.TWO, P)
            z3 = x1.multiply(da.subtract(cb).mod(P).modPow(java.math.BigInteger.TWO, P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }

        if (swap == 1) {
            val tmp1 = x2; x2 = x3; x3 = tmp1
            val tmp2 = z2; z2 = z3; z3 = tmp2
        }

        val result = x2.multiply(z2.modPow(P.subtract(java.math.BigInteger.TWO), P)).mod(P)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(b: ByteArray): java.math.BigInteger {
        val reversed = b.clone()
        // Clear high bit as per spec
        reversed[31] = (reversed[31].toInt() and 0x7F).toByte()
        reversed.reverse()
        return java.math.BigInteger(1, reversed)
    }

    private fun encodeLittleEndian(n: java.math.BigInteger): ByteArray {
        val bytes = n.toByteArray()
        val result = ByteArray(32)
        // BigInteger is big-endian, we need little-endian
        for (i in bytes.indices) {
            val targetIdx = bytes.size - 1 - i
            if (targetIdx >= 0 && i < 32) {
                result[i] = bytes[targetIdx]
            }
        }
        return result
    }
}
