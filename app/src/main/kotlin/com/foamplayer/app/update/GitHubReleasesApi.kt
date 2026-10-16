package com.foamplayer.app.update

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A GitHub release as returned by https://api.github.com/repos/{owner}/{repo}/releases/latest. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
) {
    val version: String get() = tagName.orEmpty().removePrefix("v").removePrefix("V")

    /** The arm64-v8a APK asset, falling back to the universal APK. */
    fun preferredApkAsset(): GitHubAsset? =
        assets.firstOrNull { it.name?.endsWith("arm64-v8a.apk") == true }
            ?: assets.firstOrNull { it.name?.endsWith("universal.apk") == true }
}

@Serializable
data class GitHubAsset(
    val id: Long = 0,
    val name: String? = null,
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null
)

/**
 * Minimal GitHub Releases client used by the in-app updater: fetches the
 * latest release's metadata and streams its APK asset to [File]s.
 */
object GitHubReleasesApi {
    private const val BASE_URL = "https://api.github.com"
    private const val REPO_LATEST = "/repos/RemiSchirra/FoamPlayer/releases/latest"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                // GitHub API can be slow; the metadata call itself stays short.
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url(BASE_URL)
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            expectSuccess = true
        }
    }

    /** The repository's latest published (non-draft) release, or null on any failure. */
    suspend fun latestRelease(): GitHubRelease? = runCatching {
        client.get(REPO_LATEST).body<GitHubRelease>()
    }.onFailure { error ->
        Log.w(TAG, "GitHub latest-release check failed", error)
    }.getOrNull()

    /**
     * Streams [url]'s body into [destination], reporting progress as the ratio
     * of downloaded bytes to [expectedSize] (or 0..1 indeterminately when the
     * size is unknown). Returns the destination on success.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit
    ): File {
        val channel = client.get(url).bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = 0L
        destination.parentFile?.mkdirs()
        destination.outputStream().use { output ->
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(BUFFER_SIZE.toLong())
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    output.write(bytes)
                    downloaded += bytes.size
                    if (expectedSize > 0) onProgress((downloaded.toDouble() / expectedSize).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
        return destination
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val TAG = "GitHubReleasesApi"
}
