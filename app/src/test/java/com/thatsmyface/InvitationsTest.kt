package com.thatsmyface

import org.junit.Assert.*
import org.junit.Test

class InvitationsTest {
    @Test fun invitationRoundTripsWithoutLocalSharingPreference() {
        val event = Invitations.create("Pandal night").copy(shareFaceData = true)
        val joined = Invitations.decode(Invitations.encode(event))
        assertEquals(event.id, joined.id)
        assertEquals(event.secret, joined.secret)
        assertFalse(joined.shareFaceData)
    }

    @Test fun eachInvitationHasIndependentSecret() {
        assertNotEquals(Invitations.create("Trip").secret, Invitations.create("Trip").secret)
    }

    @Test fun malformedAndUnboundedInvitesAreRejected() {
        listOf("https://example.com", "thatsmyface://join/!!!", "x".repeat(2049)).forEach {
            assertTrue(runCatching { Invitations.decode(it) }.isFailure)
        }
    }
}
