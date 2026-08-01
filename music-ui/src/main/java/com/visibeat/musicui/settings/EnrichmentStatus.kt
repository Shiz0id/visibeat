package com.visibeat.musicui.settings

import androidx.compose.runtime.Immutable

/**
 * How far MusicBrainz enrichment has got, for Settings.
 *
 * Exists because the failure mode was invisible: the queue had stalled on
 * releases it could not match, and from outside that is indistinguishable from
 * a slow job, a job that has finished, and a job that was never scheduled.
 */
@Immutable
data class EnrichmentStatus(
    val releasesLine: String = "Not checked",
    val genresLine: String = "",
    val detail: List<String> = emptyList()
)
