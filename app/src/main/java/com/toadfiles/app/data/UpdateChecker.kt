package com.toadfiles.app.data

import com.toadfiles.app.domain.IS_LITE
import kotlinx.coroutines.CancellationException
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
 * [apkUrl]/[apkName] are null when the release ships no APK for the RUNNING edition
 * (v2.5.0: tags ship one APK per edition — `toadfiles-<tag>.apk` and
 * `toadfiles-lite-<tag>.apk` — and the checker picks the one that matches).
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
 * It talks to exactly one endpoint (the repo's releases/latest), sends nothing but a
 * plain GET with the headers GitHub requires, and keeps no cache beyond the in-memory
 * UI state. It runs on the user's "Check for updates" tap AND, since v1.7.1, once
 * silently on app open (see com.toadfiles.app.update.StartupUpdateCheck) — never
 * periodically. Nothing about the device is sent, stored or logged.
 */
interface UpdateChecker {
    /**
     * Fetch + parse the latest release. Main-safe (own IO context). Throws
     * [IOException] on network/HTTP trouble and [JSONException] on a malformed payload.
     */
    suspend fun fetchLatestRelease(): GitHubRelease
}

/**
 * An update-check failure with a ready-to-show, human-readable message (rate limits,
 * GitHub-side 5xx). Extends [IOException] so existing catch sites keep working, but
 * [message] is already phrased for the user — no raw stack/exception text.
 */
open class UpdateCheckException(message: String) : IOException(message)

/** GitHub's unauthenticated API rate limit (HTTP 403) was hit. Not retried. */
class UpdateCheckRateLimitException :
    UpdateCheckException(
        "GitHub's rate limit was hit — wait a few minutes and try again, " +
            "or open the release page on github.com."
    )

/** GitHub-side 5xx: transient, so it IS retried, but keeps a ready-to-show message. */
class UpdateCheckRetryableException(code: Int) :
    UpdateCheckException("GitHub is having trouble (HTTP $code) — try again in a moment.")

/**
 * Default [UpdateChecker]: a plain [HttpURLConnection] GET against
 * https://api.github.com/repos/dukeoftoad/toadFILES/releases/latest — deliberately no
 * HTTP client dependency (the update feature adds zero new Gradle dependencies).
 * JSON is parsed with org.json, bundled with Android and provided by Robolectric on
 * the JVM for unit tests.
 *
 * Connection hardening for v1.7.1: redirects are followed explicitly (GitHub 302s
 * from github.com to api.github.com and asset CDN hops), a transient failure
 * (connect/read [IOException] or HTTP 5xx) is retried ONCE, and rate limiting (403)
 * gets its own friendly message instead of a raw HTTP error. Timeouts stay ≤10s.
 */
class GithubReleaseChecker(
    private val apiUrl: String = "https://api.github.com/repos/dukeoftoad/toadFILES/releases/latest",
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
) : UpdateChecker {

    override suspend fun fetchLatestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
        runWithRetry(maxAttempts) { fetchOnce() }
    }

    /** One HTTP attempt. Thrown exceptions classify it as retryable or not via their type. */
    private fun fetchOnce(): GitHubRelease {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // github.com → api.github.com and release-asset hops answer 302; follow them.
            connection.instanceFollowRedirects = true
            // GitHub rejects API requests without a User-Agent; pin the API version too.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            val code = connection.responseCode
            if (code !in 200..299) throw exceptionFor(code)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseRelease(body, isLite = IS_LITE)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Parses a GitHub release payload. Since the Lite edition split (v2.3.0) tags
         * ship TWO APKs — `toadfiles-<tag>.apk` (full) and `toadfiles-lite-<tag>.apk` —
         * so the pick is edition-aware, not first-match: the FULL app must never be
         * offered the Lite APK (owner report, v2.5.0: the old first-`.apk` loop could
         * do exactly that whenever GitHub listed the lite asset first; Lite strips the
         * update checker entirely, so the full app is the only wrong-download
         * direction that can happen in practice). Assets whose name carries the
         * "lite" marker (case-insensitive) belong to the lite edition; every other
         * APK asset belongs to the full edition. Non-APK assets (source zips,
         * checksums) are ignored. [isLite] defaults false so the plain full-edition
         * parse keeps working for tests and callers that predate the split. Kept a
         * companion function so unit tests can exercise real payloads without network.
         */
        fun parseRelease(json: String, isLite: Boolean = false): GitHubRelease {
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
                    if (!name.endsWith(APK_SUFFIX, ignoreCase = true)) continue
                    val assetIsLite = name.contains(LITE_ASSET_MARKER, ignoreCase = true)
                    if (assetIsLite != isLite) continue // the OTHER edition's APK — skip
                    apkName = name
                    apkUrl = asset.optString("browser_download_url").ifBlank { null }
                    apkSize = asset.optLong("size")
                    break
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

        /**
         * Maps a non-2xx status to the exception that classifies the failure:
         *  - 403 → rate limit, never retried, friendly message
         *  - 5xx → GitHub-side trouble, retryable, friendly message once retries run out
         *  - other 4xx → permanent, no retry
         */
        fun exceptionFor(code: Int): Exception = when {
            code == 403 -> UpdateCheckRateLimitException()
            code in 500..599 -> UpdateCheckRetryableException(code)
            else -> UpdateCheckException(
                "GitHub Releases answered HTTP $code — try again later."
            )
        }

        /**
         * Retry driver kept here so unit tests can cover the policy without any network.
         * Retried: plain [IOException] (connect/reset) and [UpdateCheckRetryableException]
         * (HTTP 5xx). Surfaced immediately: every other [UpdateCheckException] (rate
         * limit, 4xx), [JSONException] and anything else fatal.
         */
        fun <T> runWithRetry(maxAttempts: Int, block: (attempt: Int) -> T): T {
            var last: Exception? = null
            repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
                try {
                    return block(attempt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val transient = e is UpdateCheckRetryableException ||
                        (e is IOException && e !is UpdateCheckException)
                    if (!transient) throw e
                    last = e
                }
            }
            throw last ?: IOException("Update check failed after $maxAttempts attempt(s).")
        }

        /** Shown by "View on GitHub" even if the payload is missing html_url. */
        private const val RELEASES_PAGE_FALLBACK = "https://github.com/dukeoftoad/toadFILES/releases"
        private const val APK_SUFFIX = ".apk"
        /** Release-asset naming convention: `toadfiles-lite-<tag>.apk` is the Lite build. */
        private const val LITE_ASSET_MARKER = "lite"
        private const val USER_AGENT = "toadFILES-update-check"
        private const val API_VERSION = "2022-11-28"
        private const val TIMEOUT_MS = 10_000
        private const val DEFAULT_ATTEMPTS = 2
    }
}
