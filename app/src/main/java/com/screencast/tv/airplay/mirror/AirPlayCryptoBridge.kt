package com.screencast.tv.airplay.mirror

import android.util.Log

object AirPlayCryptoBridge {
    private const val TAG = "AirPlayCryptoBridge"

    init {
        try {
            System.loadLibrary("airplay_crypto")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load native lib", e)
        }
    }

    external fun nativeDecryptFairPlayAesKey(keyMsg: ByteArray, encryptedKey: ByteArray): ByteArray?
}
