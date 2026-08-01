package com.visibeat.musicui.playback

import com.visibeat.viewengine.TimelineItemRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueResolverTest {

    private fun track(id: Long, playable: Boolean = true) = TimelineItemRow(
        trackId = id,
        effectiveReleaseDateEpochMs = 0L,
        effectiveTitle = "Track $id",
        effectiveAlbumTitle = "Album",
        effectiveArtistDisplay = "Artist",
        releaseId = 1L,
        primaryArtistId = 1L,
        mediaStoreUri = if (playable) "content://media/external/audio/media/$id" else null
    )

    @Test
    fun `an all-playable list is passed through untouched`() {
        val tracks = listOf(track(1), track(2), track(3))
        val (queue, index) = QueueResolver.resolve(tracks, 2)!!
        assertEquals(tracks, queue)
        assertEquals(2, index)
    }

    @Test
    fun `dropping earlier unplayable rows still starts the tapped track`() {
        // Rows 0 and 1 have no URI. Tapping row 3 must play track 4, not track 3.
        val tracks = listOf(
            track(1, playable = false),
            track(2, playable = false),
            track(3),
            track(4)
        )
        val (queue, index) = QueueResolver.resolve(tracks, 3)!!
        assertEquals(listOf(3L, 4L), queue.map { it.trackId })
        assertEquals(1, index)
        assertEquals(4L, queue[index].trackId)
    }

    @Test
    fun `unplayable rows after the tapped one do not move it`() {
        val tracks = listOf(track(1), track(2, playable = false), track(3))
        val (queue, index) = QueueResolver.resolve(tracks, 0)!!
        assertEquals(listOf(1L, 3L), queue.map { it.trackId })
        assertEquals(0, index)
    }

    @Test
    fun `tapping an unplayable row falls back to the start of the queue`() {
        val tracks = listOf(track(1), track(2, playable = false), track(3))
        val (queue, index) = QueueResolver.resolve(tracks, 1)!!
        assertEquals(0, index)
        assertEquals(1L, queue[index].trackId)
    }

    @Test
    fun `an out-of-range start index falls back to the start of the queue`() {
        val tracks = listOf(track(1), track(2))
        val (_, index) = QueueResolver.resolve(tracks, 99)!!
        assertEquals(0, index)
    }

    @Test
    fun `a negative start index falls back to the start of the queue`() {
        val tracks = listOf(track(1), track(2))
        val (_, index) = QueueResolver.resolve(tracks, -1)!!
        assertEquals(0, index)
    }

    @Test
    fun `a blank URI counts as unplayable`() {
        val tracks = listOf(
            track(1).copy(mediaStoreUri = "   "),
            track(2)
        )
        val (queue, _) = QueueResolver.resolve(tracks, 0)!!
        assertEquals(listOf(2L), queue.map { it.trackId })
    }

    @Test
    fun `a list with nothing playable resolves to null`() {
        val tracks = listOf(track(1, playable = false), track(2, playable = false))
        assertNull(QueueResolver.resolve(tracks, 0))
    }

    @Test
    fun `an empty list resolves to null`() {
        assertNull(QueueResolver.resolve(emptyList(), 0))
    }
}
