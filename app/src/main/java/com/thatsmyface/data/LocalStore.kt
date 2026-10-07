package com.thatsmyface.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalDataException(message: String) : Exception(message)

class LocalStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "local-state.v1.enc"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppState())
    private var loaded = false
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    suspend fun update(transform: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = transform(mutableState.value)
            if (next != mutableState.value) {
                writeLocked(next)
                mutableState.value = next
            }
        }
    }

    suspend fun clearFaceData() = update { it.withoutFaceData() }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            keyStore().deleteEntry(KEY_ALIAS)
            mutableState.value = AppState()
            loaded = true
        }
    }

    private fun loadLocked() {
        if (loaded) return
        val restored = try {
            file.openRead().use {
                require(it.channel.size() <= MAX_STATE_BYTES) { "Local data is too large." }
                StateCodec.decrypt(it.readBytes(), encryptionKey(create = false))
            }
        } catch (_: java.io.FileNotFoundException) {
            AppState()
        } catch (_: Exception) {
            throw LocalDataException("Local data could not be unlocked. Restart the app or reset local data in Privacy.")
        }
        val recovered = restored.recoverTransfers()
        if (recovered != restored) writeLocked(recovered)
        mutableState.value = recovered
        loaded = true
    }

    private fun writeLocked(state: AppState) {
        val bytes = StateCodec.encrypt(state, encryptionKey(create = true))
        require(bytes.size <= MAX_STATE_BYTES) { "Local data is full. Remove unused event photo references." }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw LocalDataException("Changes could not be saved. Check the phone's available storage and retry.")
        }
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val existing = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "Local encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "thatsmyface.local-state.v1"
        const val MAX_STATE_BYTES = 64L * 1024 * 1024
    }
}
