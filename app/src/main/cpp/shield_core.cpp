#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include <ctime>

#define TAG "ShieldCoreNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Obfuscated key parts (XOR masked)
// Key: 32 bytes AES seed
static const unsigned char MASK_KEY[32] = {
    0x4D, 0x75, 0x73, 0x6C, 0x69, 0x6D, 0x47, 0x75,
    0x69, 0x64, 0x65, 0x53, 0x68, 0x69, 0x65, 0x6C,
    0x64, 0x53, 0x65, 0x63, 0x72, 0x65, 0x74, 0x4B,
    0x65, 0x79, 0x32, 0x30, 0x32, 0x36, 0x21, 0x24
};

static const unsigned char XOR_PAD[32] = {
    0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, 0x81,
    0x92, 0xA3, 0xB4, 0xC5, 0xD6, 0xE7, 0xF8, 0x09,
    0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0,
    0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88
};

// Authorized package signature fingerprint (first 16 bytes truncated hash for anti-tamper)
static const unsigned char AUTH_SIG_PREFIX[16] = {
    0xAD, 0xD0, 0xCB, 0x70, 0x0B, 0x79, 0x28, 0x60,
    0x21, 0x24, 0xE7, 0x37, 0x1B, 0x70, 0xF1, 0xE4
};

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_muslimguide_shield_security_ShieldNativeCore_getNativeSecretKey(
        JNIEnv *env,
        jclass clazz) {
    
    unsigned char result[32];
    for (int i = 0; i < 32; i++) {
        result[i] = MASK_KEY[i] ^ XOR_PAD[i];
    }
    
    jbyteArray outArray = env->NewByteArray(32);
    env->SetByteArrayRegion(outArray, 0, 32, reinterpret_cast<const jbyte *>(result));
    
    // Zero out memory
    memset(result, 0, sizeof(result));
    return outArray;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muslimguide_shield_security_ShieldNativeCore_verifyCallerSignature(
        JNIEnv *env,
        jclass clazz,
        jbyteArray callerCertBytes) {
    
    if (callerCertBytes == nullptr) return JNI_FALSE;
    
    jsize len = env->GetArrayLength(callerCertBytes);
    if (len < 16) return JNI_FALSE;
    
    jbyte *bytes = env->GetByteArrayElements(callerCertBytes, nullptr);
    jboolean match = JNI_TRUE;
    
    // Check first 16 bytes match signature certificate
    for (int i = 0; i < 16; i++) {
        if (static_cast<unsigned char>(bytes[i]) != AUTH_SIG_PREFIX[i]) {
            match = JNI_FALSE;
            break;
        }
    }
    
    env->ReleaseByteArrayElements(callerCertBytes, bytes, JNI_ABORT);
    return match;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muslimguide_shield_security_ShieldNativeCore_getCleanBrowsingDns(
        JNIEnv *env,
        jclass clazz) {
    // Obfuscated string: adult-filter-dns.cleanbrowsing.org
    static const unsigned char ENC_DNS[] = {
        0x0C, 0x09, 0x18, 0x01, 0x19, 0x40, 0x0B, 0x04, 0x01, 0x19, 0x08, 0x1F,
        0x40, 0x09, 0x03, 0x1E, 0x43, 0x0E, 0x01, 0x08, 0x0C, 0x03, 0x0F, 0x1F,
        0x02, 0x1E, 0x04, 0x03, 0x0A, 0x43, 0x02, 0x1F, 0x0A
    };
    char dns[34];
    for (size_t i = 0; i < sizeof(ENC_DNS); i++) {
        dns[i] = static_cast<char>(ENC_DNS[i] ^ 0x6D);
    }
    dns[33] = '\0';
    
    jstring res = env->NewStringUTF(dns);
    memset(dns, 0, sizeof(dns));
    return res;
}
