package com.thatsmyface.recognition

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatcherTest {
    private val reference = basis(0)

    @Test fun onlyEnrolledParticipantsCanBeSuggested() {
        assertEquals(MatchKind.NO_MATCH, FaceMatcher.match(reference, emptyMap()).kind)
        val result = FaceMatcher.match(reference, mapOf("friend" to listOf(reference)))
        assertEquals(MatchKind.SUGGESTED, result.kind)
        assertEquals("friend", result.participantId)
    }

    @Test fun weakSimilarityDoesNotRevealACandidate() {
        val result = FaceMatcher.match(reference, mapOf("friend" to listOf(basis(1))))
        assertEquals(MatchKind.NO_MATCH, result.kind)
        assertNull(result.participantId)
        assertNull(result.similarity)
    }

    @Test fun borderlineAndPoorQualityFacesStayUncertain() {
        assertEquals(MatchKind.UNCERTAIN, matchWithSimilarity(.45f).kind)
        assertEquals(
            MatchKind.UNCERTAIN,
            FaceMatcher.match(reference, mapOf("friend" to listOf(reference)), false).kind,
        )
    }

    @Test fun similarLookingEnrolledParticipantsStayUncertain() {
        val result = FaceMatcher.match(reference, mapOf(
            "first" to listOf(withCosine(.75f)),
            "second" to listOf(withCosine(.70f)),
        ))
        assertEquals(MatchKind.UNCERTAIN, result.kind)
    }

    @Test fun multipleReferencesOfOnePersonDoNotCreateAmbiguity() {
        val result = FaceMatcher.match(reference, mapOf(
            "first" to listOf(withCosine(.75f), withCosine(.74f)),
            "second" to listOf(withCosine(.40f)),
        ))
        assertEquals(MatchKind.SUGGESTED, result.kind)
        assertEquals("first", result.participantId)
    }

    @Test fun cosineIsIndependentOfEmbeddingMagnitude() {
        assertEquals(1f, FaceMatcher.cosine(reference, reference.map { it * 8 }.toFloatArray()), 1e-6f)
    }

    @Test fun malformedReferencesAreRejected() {
        listOf(FloatArray(127), FloatArray(128), basis(0).apply { this[2] = Float.NaN },
            basis(0).apply { this[2] = Float.POSITIVE_INFINITY }).forEach { invalid ->
            assertTrue(runCatching { FaceMatcher.normalize(invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun thresholdTransitionsDoNotSkipTheUncertainState() {
        assertEquals(MatchKind.NO_MATCH, matchWithSimilarity(.34f).kind)
        assertEquals(MatchKind.UNCERTAIN, matchWithSimilarity(.36f).kind)
        assertEquals(MatchKind.UNCERTAIN, matchWithSimilarity(.54f).kind)
        assertEquals(MatchKind.SUGGESTED, matchWithSimilarity(.56f).kind)
    }

    @Test fun identicalReferencesUnderDifferentPeopleNeverBecomeCertain() {
        val people = listOf("first" to listOf(reference), "second" to listOf(reference))
        for (order in listOf(people, people.reversed())) {
            assertEquals(MatchKind.UNCERTAIN, FaceMatcher.match(reference, order.toMap()).kind)
        }
    }

    @Test fun clearSeparationIsRequiredEvenAboveTheSuggestionThreshold() {
        fun match(runnerUp: Float) = FaceMatcher.match(reference, mapOf(
            "first" to listOf(withCosine(.8f)),
            "second" to listOf(withCosine(runnerUp)),
        ))
        assertEquals(MatchKind.UNCERTAIN, match(.73f).kind)
        assertEquals(MatchKind.SUGGESTED, match(.71f).kind)
    }

    @Test fun emptyEnrollmentAndOppositeFacesDoNotRevealAPerson() {
        for (participants in listOf(
            mapOf("not-enrolled" to emptyList()),
            mapOf("other" to listOf(reference.map { -it }.toFloatArray())),
        )) {
            val result = FaceMatcher.match(reference, participants, qualityAcceptable = false)
            assertEquals(MatchKind.NO_MATCH, result.kind)
            assertNull(result.participantId)
            assertNull(result.similarity)
        }
    }

    @Test fun invalidCandidateAndEnrolledReferencesFailClosed() {
        val invalid = listOf(FloatArray(127), FloatArray(128), basis(0).apply { this[1] = Float.NaN })
        invalid.forEach { values ->
            assertTrue(runCatching { FaceMatcher.match(values, emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching {
                FaceMatcher.match(reference, mapOf("friend" to listOf(reference, values)))
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun matchWithSimilarity(value: Float): FaceMatch =
        FaceMatcher.match(reference, mapOf("friend" to listOf(withCosine(value))))

    private fun withCosine(value: Float): FloatArray = FloatArray(128).apply {
        this[0] = value
        this[1] = sqrt(1 - value * value)
    }

    private fun basis(index: Int): FloatArray = FloatArray(128).apply { this[index] = 1f }
}
