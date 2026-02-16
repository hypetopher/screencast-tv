#include <jni.h>
#include <cstring>

extern "C" {
#include "playfair/playfair.h"
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_screencast_tv_airplay_mirror_AirPlayCryptoBridge_nativeDecryptFairPlayAesKey(
    JNIEnv* env,
    jclass,
    jbyteArray keyMsg,
    jbyteArray encryptedKey
) {
    if (keyMsg == nullptr || encryptedKey == nullptr) return nullptr;

    const jsize keyMsgLen = env->GetArrayLength(keyMsg);
    const jsize encryptedKeyLen = env->GetArrayLength(encryptedKey);
    if (keyMsgLen < 164 || encryptedKeyLen < 72) return nullptr;

    jbyte keyMsgBuf[164];
    jbyte encryptedBuf[72];
    env->GetByteArrayRegion(keyMsg, 0, 164, keyMsgBuf);
    env->GetByteArrayRegion(encryptedKey, 0, 72, encryptedBuf);

    unsigned char out[16];
    std::memset(out, 0, sizeof(out));
    playfair_decrypt(reinterpret_cast<unsigned char*>(keyMsgBuf), reinterpret_cast<unsigned char*>(encryptedBuf), out);

    jbyteArray result = env->NewByteArray(16);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, 16, reinterpret_cast<jbyte*>(out));
    return result;
}
