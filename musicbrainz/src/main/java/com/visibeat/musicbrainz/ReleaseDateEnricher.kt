package com.visibeat.musicbrainz

/**
 * Enriches release dates by looking up albums on MusicBrainz.
 */
class ReleaseDateEnricher(
    private val client: MusicBrainzClient = MusicBrainzClient()
) {
    /**
     * Look up the release date for an album.
     * Returns EnrichmentResult with the date components if found.
     */
    suspend fun enrichReleaseDate(
        artist: String,
        album: String,
        existingYear: Int? = null
    ): EnrichmentResult {
        return try {
            val releases = client.searchRelease(artist, album)
            
            if (releases.isEmpty()) {
                return EnrichmentResult.NotFound
            }

            // Score and rank releases
            val scoredReleases = releases.mapNotNull { release ->
                val dateComponents = release.parseDateComponents() ?: return@mapNotNull null
                
                var score = release.score
                
                // Boost if year matches existing data
                if (existingYear != null && dateComponents.year == existingYear) {
                    score += 20
                }
                
                // Prefer US/GB releases
                if (release.country in listOf("US", "GB", "XW")) {
                    score += 10
                }
                
                // Prefer official releases
                if (release.status == "Official") {
                    score += 5
                }
                
                // Prefer releases with full date
                if (dateComponents.hasFullDate) {
                    score += 15
                } else if (dateComponents.hasMonthDate) {
                    score += 5
                }
                
                ScoredRelease(release, dateComponents, score)
            }

            val best = scoredReleases.maxByOrNull { it.score }
                ?: return EnrichmentResult.NotFound

            // Determine confidence
            val confidence = when {
                best.score >= 90 -> EnrichmentConfidence.HIGH
                best.score >= 60 -> EnrichmentConfidence.MEDIUM
                else -> EnrichmentConfidence.LOW
            }

            EnrichmentResult.Found(
                musicBrainzId = best.release.id,
                dateComponents = best.dateComponents,
                releaseType = best.release.releaseGroup?.effectiveType,
                confidence = confidence
            )
        } catch (e: Exception) {
            EnrichmentResult.Error(e.message ?: "Unknown error")
        }
    }

    private data class ScoredRelease(
        val release: MusicBrainzRelease,
        val dateComponents: DateComponents,
        val score: Int
    )
}

sealed class EnrichmentResult {
    data class Found(
        val musicBrainzId: String,
        val dateComponents: DateComponents,
        /** ALBUM / SINGLE / EP / COMPILATION, or null if MusicBrainz did not say. */
        val releaseType: String? = null,
        val confidence: EnrichmentConfidence
    ) : EnrichmentResult()
    
    object NotFound : EnrichmentResult()
    data class Error(val message: String) : EnrichmentResult()
}

enum class EnrichmentConfidence {
    HIGH,    // Auto-apply
    MEDIUM,  // Apply with flag
    LOW      // Skip or manual review
}
