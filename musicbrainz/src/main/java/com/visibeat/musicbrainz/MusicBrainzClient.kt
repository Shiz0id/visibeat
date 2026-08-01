package com.visibeat.musicbrainz

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Rate-limited MusicBrainz API client.
 * Enforces 1 request/second as required by MusicBrainz.
 */
class MusicBrainzClient(
    private val userAgent: String = "VisiBeat/1.0 (https://github.com/visibeat)"
) {
    private val mutex = Mutex()
    private var lastCallTime = 0L
    private val minDelayMs = 1100L  // 1.1 seconds to be safe

    private val api: MusicBrainzApi by lazy {
        val userAgentInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://musicbrainz.org/ws/2/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MusicBrainzApi::class.java)
    }

    /**
     * Search for a release by artist and album title.
     * Rate-limited to 1 request/second.
     */
    suspend fun searchRelease(artist: String, album: String): List<MusicBrainzRelease> {
        return rateLimitedCall {
            val query = "artist:\"$artist\" AND release:\"$album\""
            api.searchRelease(query).releases
        }
    }

    /**
     * Search for a release with more control over the query.
     */
    suspend fun searchReleaseRaw(query: String): List<MusicBrainzRelease> {
        return rateLimitedCall {
            api.searchRelease(query).releases
        }
    }

    /**
     * Search for an artist. Rate-limited like everything else here — the one
     * request per second applies across the whole API, not per endpoint.
     */
    suspend fun searchArtist(query: String): List<MusicBrainzArtist> {
        return rateLimitedCall {
            api.searchArtist(query).artists
        }
    }

    /**
     * Genres for a release. Empty on any failure — a missing genre is not worth
     * failing an enrichment pass over.
     */
    suspend fun releaseGenres(mbid: String): List<String> = try {
        rateLimitedCall { api.lookupReleaseGenres(mbid) }.bestGenres()
    } catch (t: Throwable) {
        emptyList()
    }

    /** Genres for an artist. Empty on any failure. */
    suspend fun artistGenres(mbid: String): List<String> = try {
        rateLimitedCall { api.lookupArtistGenres(mbid) }.bestGenres()
    } catch (t: Throwable) {
        emptyList()
    }

    private suspend fun <T> rateLimitedCall(block: suspend () -> T): T {
        mutex.withLock {
            val elapsed = System.currentTimeMillis() - lastCallTime
            if (elapsed < minDelayMs) {
                delay(minDelayMs - elapsed)
            }
            lastCallTime = System.currentTimeMillis()
        }
        return block()
    }
}
