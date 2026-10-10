package com.thatsmyface

import com.thatsmyface.data.Event
import com.thatsmyface.data.hex
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Invitations {
    private const val PREFIX = "thatsmyface://join/"
    private val json = Json { ignoreUnknownKeys = false }

    @Serializable
    private data class Invite(val version: Int = 1, val id: String, val title: String, val secret: String)

    fun create(title: String): Event {
        require(title.trim().length in 1..60) { "Use an event name of 1 to 60 characters." }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()
        return Event(title = title.trim(), secret = secret)
    }

    fun encode(event: Event): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
        json.encodeToString(Invite(id = event.id, title = event.title, secret = event.secret)).toByteArray(),
    )

    fun decode(text: String): Event {
        require(text.length <= 2048 && text.startsWith(PREFIX)) { "This is not a ThatsMyFace invitation." }
        val invite = json.decodeFromString<Invite>(String(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))))
        require(invite.version == 1) { "This invitation needs a different app version." }
        require(UUID.fromString(invite.id).toString() == invite.id)
        require(invite.title.isNotBlank() && invite.title.length <= 60)
        require(invite.secret.matches(Regex("[a-f0-9]{64}")))
        return Event(id = invite.id, title = invite.title, secret = invite.secret)
    }
}
