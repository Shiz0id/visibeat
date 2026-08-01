package com.visibeat.musicui.timeline.shared

import com.visibeat.viewengine.AlbumTrackRow
import com.visibeat.viewengine.TimelineAlbumRow
import com.visibeat.viewengine.TimelineBucketRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the timeline tree.
 *
 * Flattening is what lets the tree be drawn as a tree while `LazyColumn` still
 * sees a plain list, so the awkward cases are all structural rather than visual
 * — and testable without a device.
 */
class TimelineTreeTest {

    private fun bucket(start: Long, items: Int = 1) =
        TimelineBucketRow(start, items, 1, 1)

    private fun album(id: Long, title: String = "Album $id") =
        TimelineAlbumRow(id, title, "Artist", 2, null, null)

    private fun track(id: Long) = AlbumTrackRow(
        trackId = id, effectiveReleaseDateEpochMs = null, effectiveTitle = "Track $id",
        effectiveAlbumTitle = null, effectiveArtistDisplay = null, releaseId = null,
        primaryArtistId = null, mediaStoreAlbumId = null, mediaStoreUri = null,
        artPath = null, discNumber = null, trackNumber = null, mimeType = null
    )

    private val buckets = listOf(bucket(100), bucket(200), bucket(300))

    // ── collapsed ─────────────────────────────────────────

    @Test
    fun `a closed timeline is just its buckets`() {
        val rows = buildTimelineTree(buckets, TimelineTreeState())
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is TimelineTreeRow.Bucket })
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test
    fun `bucket index counts buckets, not rendered lines`() {
        // The zigzag alternates on this, so expanded children must not shift it
        // and make two consecutive buckets land on the same side.
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            albumsByBucket = mapOf(100L to listOf(album(1), album(2)))
        )
        val indices = buildTimelineTree(buckets, state)
            .filterIsInstance<TimelineTreeRow.Bucket>()
            .map { it.bucketIndex }
        assertEquals(listOf(0, 1, 2), indices)
    }

    // ── one level down ────────────────────────────────────

    @Test
    fun `an opened bucket lists every release, including artless ones`() {
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            albumsByBucket = mapOf(100L to listOf(album(1), album(2), album(3)))
        )
        val rows = buildTimelineTree(buckets, state)
        assertEquals(listOf(0, 1, 1, 1, 0, 0), rows.map { it.depth })
    }

    @Test
    fun `an opened bucket with nothing loaded yet shows a placeholder, not an empty gap`() {
        val state = TimelineTreeState(expandedBuckets = setOf(100))
        val rows = buildTimelineTree(buckets, state)
        assertTrue(rows[1] is TimelineTreeRow.Loading)
        assertEquals(1, rows[1].depth)
    }

    @Test
    fun `an empty bucket that has loaded shows no placeholder`() {
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            albumsByBucket = mapOf(100L to emptyList())
        )
        val rows = buildTimelineTree(buckets, state)
        assertEquals(3, rows.size)
    }

    // ── two levels down ───────────────────────────────────

    @Test
    fun `an opened release lists its tracks beneath it`() {
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            expandedAlbums = setOf(1),
            albumsByBucket = mapOf(100L to listOf(album(1), album(2))),
            tracksByAlbum = mapOf(1L to listOf(track(10), track(11)))
        )
        val rows = buildTimelineTree(buckets, state)
        assertEquals(listOf(0, 1, 2, 2, 1, 0, 0), rows.map { it.depth })
    }

    @Test
    fun `a release opened inside a closed bucket contributes nothing`() {
        // Closing a bucket must not leave its grandchildren stranded in the list.
        val state = TimelineTreeState(
            expandedBuckets = emptySet(),
            expandedAlbums = setOf(1),
            albumsByBucket = mapOf(100L to listOf(album(1))),
            tracksByAlbum = mapOf(1L to listOf(track(10)))
        )
        assertEquals(3, buildTimelineTree(buckets, state).size)
    }

    // ── the spine needs to know where a branch ends ───────

    @Test
    fun `only the final release of a bucket is flagged last`() {
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            albumsByBucket = mapOf(100L to listOf(album(1), album(2), album(3)))
        )
        val flags = buildTimelineTree(buckets, state)
            .filterIsInstance<TimelineTreeRow.Album>()
            .map { it.isLastInBucket }
        assertEquals(listOf(false, false, true), flags)
    }

    @Test
    fun `only the final track of a release is flagged last`() {
        val state = TimelineTreeState(
            expandedBuckets = setOf(100),
            expandedAlbums = setOf(1),
            albumsByBucket = mapOf(100L to listOf(album(1))),
            tracksByAlbum = mapOf(1L to listOf(track(10), track(11), track(12)))
        )
        val flags = buildTimelineTree(buckets, state)
            .filterIsInstance<TimelineTreeRow.Track>()
            .map { it.isLastInAlbum }
        assertEquals(listOf(false, false, true), flags)
    }

    // ── keys ──────────────────────────────────────────────

    @Test
    fun `every row has a distinct key, even for one release across two buckets`() {
        // A compilation can legitimately appear under more than one date, and
        // duplicate LazyColumn keys are a crash, not a glitch.
        val state = TimelineTreeState(
            expandedBuckets = setOf(100, 200),
            albumsByBucket = mapOf(
                100L to listOf(album(1)),
                200L to listOf(album(1))
            )
        )
        val keys = buildTimelineTree(buckets, state).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a row keeps its key when its children open`() {
        val closed = TimelineTreeState(
            expandedBuckets = setOf(100),
            albumsByBucket = mapOf(100L to listOf(album(1)))
        )
        val opened = closed.toggleAlbum(1).withTracks(1, listOf(track(10)))
        val keyClosed = buildTimelineTree(buckets, closed).first { it is TimelineTreeRow.Album }.key
        val keyOpened = buildTimelineTree(buckets, opened).first { it is TimelineTreeRow.Album }.key
        assertEquals(keyClosed, keyOpened)
    }

    // ── state transitions ─────────────────────────────────

    @Test
    fun `toggling is symmetric`() {
        val once = TimelineTreeState().toggleBucket(100)
        assertTrue(100L in once.expandedBuckets)
        assertTrue(100L !in once.toggleBucket(100).expandedBuckets)
    }

    @Test
    fun `loaded children survive being collapsed and reopened`() {
        // Reopening should not refetch what is already in hand.
        val loaded = TimelineTreeState()
            .toggleBucket(100)
            .withAlbums(100, listOf(album(1)))
        val reopened = loaded.toggleBucket(100).toggleBucket(100)
        assertEquals(listOf(album(1)), reopened.albumsByBucket[100])
    }
}
