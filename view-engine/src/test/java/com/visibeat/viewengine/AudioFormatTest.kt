package com.visibeat.viewengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFormatTest {

    // ── the MIME types Android actually hands us ──────────

    @Test
    fun `recognises the common container types`() {
        assertEquals(AudioFormat.MP3, AudioFormat.fromMimeType("audio/mpeg"))
        assertEquals(AudioFormat.FLAC, AudioFormat.fromMimeType("audio/flac"))
        assertEquals(AudioFormat.AAC, AudioFormat.fromMimeType("audio/mp4"))
        assertEquals(AudioFormat.AAC, AudioFormat.fromMimeType("audio/aac"))
        assertEquals(AudioFormat.WAV, AudioFormat.fromMimeType("audio/x-wav"))
        assertEquals(AudioFormat.OGG, AudioFormat.fromMimeType("audio/ogg"))
        assertEquals(AudioFormat.OPUS, AudioFormat.fromMimeType("audio/opus"))
        assertEquals(AudioFormat.AIFF, AudioFormat.fromMimeType("audio/x-aiff"))
        assertEquals(AudioFormat.WMA, AudioFormat.fromMimeType("audio/x-ms-wma"))
    }

    @Test
    fun `an ogg-contained opus stream reads as opus, not ogg`() {
        assertEquals(AudioFormat.OPUS, AudioFormat.fromMimeType("audio/ogg; codecs=opus"))
    }

    @Test
    fun `wma is not mistaken for wav`() {
        // "audio/x-ms-wma" trips a naive contains-check order.
        assertEquals(AudioFormat.WMA, AudioFormat.fromMimeType("audio/x-ms-wma"))
    }

    @Test
    fun `case and whitespace do not matter`() {
        assertEquals(AudioFormat.FLAC, AudioFormat.fromMimeType("  AUDIO/FLAC "))
    }

    @Test
    fun `an unknown or missing type shows no badge rather than a guess`() {
        assertNull(AudioFormat.fromMimeType(null))
        assertNull(AudioFormat.fromMimeType(""))
        assertNull(AudioFormat.fromMimeType("   "))
        assertNull(AudioFormat.fromMimeType("application/octet-stream"))
    }

    // ── picking one badge for a whole release ─────────────

    @Test
    fun `a uniform album shows its format`() {
        val album = List(12) { "audio/flac" }
        assertEquals(AudioFormat.FLAC, AudioFormat.dominant(album))
    }

    @Test
    fun `one stray transcode does not change the badge`() {
        val album = List(11) { "audio/flac" } + "audio/mpeg"
        assertEquals(AudioFormat.FLAC, AudioFormat.dominant(album))
    }

    @Test
    fun `the majority format wins`() {
        val album = List(3) { "audio/flac" } + List(8) { "audio/mpeg" }
        assertEquals(AudioFormat.MP3, AudioFormat.dominant(album))
    }

    @Test
    fun `an exact tie breaks toward lossless and is order-independent`() {
        val flacFirst = listOf("audio/flac", "audio/flac", "audio/mpeg", "audio/mpeg")
        assertEquals(AudioFormat.FLAC, AudioFormat.dominant(flacFirst))
        assertEquals(AudioFormat.FLAC, AudioFormat.dominant(flacFirst.reversed()))
    }

    @Test
    fun `unknown types are ignored rather than counted`() {
        val album = listOf("audio/flac", null, "application/octet-stream", "audio/flac")
        assertEquals(AudioFormat.FLAC, AudioFormat.dominant(album))
    }

    @Test
    fun `an album of nothing recognisable shows no badge`() {
        assertNull(AudioFormat.dominant(listOf(null, "", "application/octet-stream")))
        assertNull(AudioFormat.dominant(emptyList()))
    }
}
