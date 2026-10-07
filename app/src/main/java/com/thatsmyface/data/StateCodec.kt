package com.thatsmyface.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object StateCodec {
    private val header = byteArrayOf(0x54, 0x4d, 0x46, 1)
    private val json = Json { encodeDefaults = true }

    fun encrypt(state: AppState, key: SecretKey): ByteArray {
        require(state.schemaVersion == 1) { "Unsupported local data version." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        val cleartext = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        return try {
            header + cipher.iv + cipher.doFinal(cleartext)
        } finally {
            cleartext.fill(0)
        }
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): AppState {
        require(bytes.size >= 32 && bytes.copyOfRange(0, 4).contentEquals(header)) {
            "Unsupported local data format."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(header)
        val cleartext = cipher.doFinal(bytes, 16, bytes.size - 16)
        return try {
            json.decodeFromString<AppState>(cleartext.toString(Charsets.UTF_8)).also {
                require(it.schemaVersion == 1) { "Unsupported local data version." }
            }
        } finally {
            cleartext.fill(0)
        }
    }
}
