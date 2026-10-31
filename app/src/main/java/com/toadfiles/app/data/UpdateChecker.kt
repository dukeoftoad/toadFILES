package com.toadfiles.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The slice of a GitHub release toadFILES needs for the self-update flow.
 * [apkUrl]/[apkName] are null when the release ships no .apk asset at all.
 */
data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String,
    /** Release notes body as plain text (markdown kept raw, CR-LF normalized). */
    val notes: String?,
    val apkName: String?,
    val apkUrl: String?,
    val apkSizeBytes: Long,
)

/**
 * Fetches toadFILES' newest GitHub release.
 *
 * PRIVACY STANCE: together with [UpdateDownloader] this is the app's ONLY network path.
 * It runs strictly on user action (About → "Check for updates") — no polling, no
 * auto-check at startup — talks to exactly one endpoint (the repo's releases/latest),
 * sends nothing but a plain GET with the headers GitHub requires, and keeps no cache
 * beyond the in-memory UI state. Nothing about the device is sent, stored or logged.
 */
interface UpdateChecker {
    /**
     * Fetch + parse the latest release. Main-safe (own IO context). Throws
     * [IOException] on network/HTTP trouble and [JSONException] on a malformed payload.
     */
    suspend fun fetchLatestRelease(): GitHubRelease
}

/**
 * Default [UpdateChecker]: a plain [HttpURLConnection] GET against
 * https://api.github.com/repos/dukeoftoad/toadFILES/releases/latest — deliberately no
 * HTTP client dependency (the update feature adds zero new Gradle dependencies).
 * JSON is parsed with org.json, bundled with Android and provided by Robolectric on
 * the JVM for unit tests.
 */
class GithubReleaseChecker(
    private val apiUrl: String = "https://api.github.com/repos/dukeoftoad/toadFILES/releases/latest",
) : UpdateChecker {

    override suspend fun fetchLatestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // GitHub rejects API requests without a User-Agent; pin the API version too.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub Releases API returned HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Parses a GitHub release payload. Picks the FIRST .apk asset (tags ship one
         * APK; other assets such as source zips are ignored). Kept a companion function
         * so unit tests can exercise real payloads without any network.
         */
        fun parseRelease(json: String): GitHubRelease {
            val root = JSONObject(json)
            val tag = root.optString("tag_name")
            if (tag.isBlank()) throw JSONException("Release payload has no tag_name")
            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            val assets: JSONArray? = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(APK_SUFFIX, ignoreCase = true)) {
                        apkName = name
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }
            return GitHubRelease(
                tagName = tag,
                htmlUrl = root.optString("html_url").ifBlank { RELEASES_PAGE_FALLBACK },
                notes = root.optString("body").ifBlank { null }?.replace("\r\n", "\n"),
                apkName = apkName,
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
            )
        }

        /** Shown by "View on GitHub" even if the payload is missing html_url. */
        private const val RELEASES_PAGE_FALLBACK = "https://github.com/dukeoftoad/toadFILES/releases"
        private const val APK_SUFFIX = ".apk"
        private const val USER_AGENT = "toadFILES-update-check"
        private const val API_VERSION = "2022-11-28"
        private const val TIMEOUT_MS = 10_000
    }
}
