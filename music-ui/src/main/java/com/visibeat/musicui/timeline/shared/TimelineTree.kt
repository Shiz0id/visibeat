package com.visibeat.musicui.timeline.shared

import androidx.compose.runtime.Immutable
import com.visibeat.viewengine.AlbumTrackRow
import com.visibeat.viewengine.TimelineAlbumRow
import com.visibeat.viewengine.TimelineBucketRow

/**
 * A single line of the timeline, at whatever depth.
 *
 * The timeline is a tree — buckets hold releases, releases hold tracks — but it
 * is rendered from a *flat* list. That is a data-structure decision, not a
 * visual one: bucket rows still draw as the zigzag cards on the spine, and the
 * branches below them draw as branches. Flattening buys three things nesting
 * does not:
 *
 *  - `LazyColumn` keeps recycling. Expanded children are siblings, so opening a
 *    release with forty tracks does not put a forty-item subtree inside one item.
 *  - Every line has a stable key, so expanding animates and scroll position
 *    survives.
 *  - Every leaf is one composable, which is what makes a single drag
 *    implementation possible rather than one per layout.
 */
@Immutable
sealed interface TimelineTreeRow {
    /** Stable across expansion, so LazyColumn can animate rather than rebuild. */
    val key: String

    /** How far in this line sits. Buckets are 0, releases 1, tracks 2. */
    val depth: Int

    @Immutable
    data class Bucket(
        val bucket: TimelineBucketRow,
        /** Position among buckets only, so the zigzag ignores expanded children. */
        val bucketIndex: Int,
        val expanded: Boolean
    ) : TimelineTreeRow {
        override val key = "b:${bucket.bucketStartEpochMs}"
        override val depth = 0
    }

    @Immutable
    data class Album(
        val bucketStartEpochMs: Long,
        val album: TimelineAlbumRow,
        val expanded: Boolean,
        /** Last child of its bucket — the spine stops here rather than continuing. */
        val isLastInBucket: Boolean
    ) : TimelineTreeRow {
        override val key = "a:${bucketStartEpochMs}:${album.releaseId}"
        override val depth = 1
    }

    @Immutable
    data class Track(
        val bucketStartEpochMs: Long,
        val releaseId: Long,
        val track: AlbumTrackRow,
        val isLastInAlbum: Boolean
    ) : TimelineTreeRow {
        override val key = "t:${bucketStartEpochMs}:${releaseId}:${track.trackId}"
        override val depth = 2
    }

    /** Children were asked for but have not arrived yet. */
    @Immutable
    data class Loading(val parentKey: String, override val depth: Int) : TimelineTreeRow {
        override val key = "l:$parentKey"
    }
}

/**
 * What the user has opened, and what has been loaded for it.
 *
 * Held apart from the rows so that expanding is a cheap state change and the
 * flattening stays a pure function of it.
 */
@Immutable
data class TimelineTreeState(
    val expandedBuckets: Set<Long> = emptySet(),
    val expandedAlbums: Set<Long> = emptySet(),
    val albumsByBucket: Map<Long, List<TimelineAlbumRow>> = emptyMap(),
    val tracksByAlbum: Map<Long, List<AlbumTrackRow>> = emptyMap()
) {
    fun toggleBucket(bucketStart: Long) = copy(
        expandedBuckets = if (bucketStart in expandedBuckets) expandedBuckets - bucketStart
        else expandedBuckets + bucketStart
    )

    fun toggleAlbum(releaseId: Long) = copy(
        expandedAlbums = if (releaseId in expandedAlbums) expandedAlbums - releaseId
        else expandedAlbums + releaseId
    )

    fun withAlbums(bucketStart: Long, albums: List<TimelineAlbumRow>) =
        copy(albumsByBucket = albumsByBucket + (bucketStart to albums))

    /**
     * Forgets what a bucket loaded, so it shows Loading rather than the previous
     * answer. Needed when the *question* changes without the bucket changing —
     * narrowing an open year from all of it to one month of it.
     */
    fun withoutAlbums(bucketStart: Long) =
        copy(albumsByBucket = albumsByBucket - bucketStart)

    fun withTracks(releaseId: Long, tracks: List<AlbumTrackRow>) =
        copy(tracksByAlbum = tracksByAlbum + (releaseId to tracks))
}

/**
 * Flattens buckets and their opened children into the list to render.
 *
 * Pure, so the tree's shape can be tested without a database or a device — which
 * matters because the awkward cases are structural: a bucket opened before its
 * contents arrive, a release opened inside a bucket that is then closed, the
 * last child of a branch needing to know it is last so the spine can stop.
 */
fun buildTimelineTree(
    buckets: List<TimelineBucketRow>,
    state: TimelineTreeState
): List<TimelineTreeRow> {
    val rows = ArrayList<TimelineTreeRow>(buckets.size)

    buckets.forEachIndexed bucket@{ index, bucket ->
        val start = bucket.bucketStartEpochMs
        val bucketOpen = start in state.expandedBuckets
        rows += TimelineTreeRow.Bucket(bucket, index, bucketOpen)
        if (!bucketOpen) return@bucket

        val albums = state.albumsByBucket[start]
        if (albums == null) {
            rows += TimelineTreeRow.Loading(parentKey = "b:$start", depth = 1)
            return@bucket
        }

        albums.forEachIndexed album@{ albumIndex, album ->
            val albumOpen = album.releaseId in state.expandedAlbums
            val lastAlbum = albumIndex == albums.lastIndex
            rows += TimelineTreeRow.Album(start, album, albumOpen, lastAlbum)
            if (!albumOpen) return@album

            val tracks = state.tracksByAlbum[album.releaseId]
            if (tracks == null) {
                rows += TimelineTreeRow.Loading(parentKey = "a:$start:${album.releaseId}", depth = 2)
                return@album
            }
            tracks.forEachIndexed { trackIndex, track ->
                rows += TimelineTreeRow.Track(
                    bucketStartEpochMs = start,
                    releaseId = album.releaseId,
                    track = track,
                    isLastInAlbum = trackIndex == tracks.lastIndex
                )
            }
        }
    }
    return rows
}
