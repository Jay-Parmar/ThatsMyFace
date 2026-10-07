package com.thatsmyface.nearby

import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface NearbyLink {
    val peers: StateFlow<List<NearbyPeer>>
    val events: SharedFlow<TransportEvent>
    val active: StateFlow<Boolean>
    suspend fun start(eventId: String, nickname: String)
    fun stop()
    suspend fun connect(endpointId: String)
    suspend fun verify(endpointId: String)
    fun reject(endpointId: String)
    fun disconnect(endpointId: String)
    fun allowPeer(endpointId: String)
    suspend fun sendMessage(endpointId: String, message: WireMessage)
    fun prepareFile(file: File): PreparedFile
    fun prepareFile(descriptor: ParcelFileDescriptor): PreparedFile
    suspend fun sendFile(endpointId: String, prepared: PreparedFile)
    fun expectFile(endpointId: String, payloadId: Long, byteCount: Long)
    fun cancel(payloadId: Long)
}
