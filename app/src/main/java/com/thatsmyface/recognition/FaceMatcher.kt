package com.thatsmyface.recognition

import kotlin.math.sqrt

enum class MatchKind { SUGGESTED, UNCERTAIN, NO_MATCH }

data class FaceMatch(
    val kind: MatchKind,
    val participantId: String? = null,
    val similarity: Float? = null,
)

object FaceMatcher {
    const val EMBEDDING_SIZE = 128
    const val SUGGESTION_THRESHOLD = 0.55f
    const val UNCERTAIN_THRESHOLD = 0.35f
    const val MINIMUM_MARGIN = 0.08f

    fun normalize(values: FloatArray): FloatArray {
        require(values.size == EMBEDDING_SIZE && values.all(Float::isFinite)) {
            "Invalid face reference"
        }
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        require(norm > 1e-6) { "Empty face reference" }
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    fun cosine(first: FloatArray, second: FloatArray): Float {
        val a = normalize(first)
        val b = normalize(second)
        return a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat().coerceIn(-1f, 1f)
    }

    fun match(
        embedding: FloatArray,
        enrolledParticipants: Map<String, List<FloatArray>>,
        qualityAcceptable: Boolean = true,
    ): FaceMatch {
        normalize(embedding)
        val ranked = enrolledParticipants.mapNotNull { (participant, references) ->
            references.takeIf { it.isNotEmpty() }?.let {
                participant to it.maxOf { reference -> cosine(embedding, reference) }
            }
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return FaceMatch(MatchKind.NO_MATCH)
        if (best.second < UNCERTAIN_THRESHOLD) return FaceMatch(MatchKind.NO_MATCH)
        val ambiguous = ranked.getOrNull(1)?.let { best.second - it.second < MINIMUM_MARGIN } ?: false
        val kind = if (qualityAcceptable && !ambiguous && best.second >= SUGGESTION_THRESHOLD) {
            MatchKind.SUGGESTED
        } else {
            MatchKind.UNCERTAIN
        }
        return FaceMatch(kind, best.first, best.second)
    }
}
