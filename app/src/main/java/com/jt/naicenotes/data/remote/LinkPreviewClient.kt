package com.jt.naicenotes.data.remote

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class LinkPreview(
    val title: String?,
    val imageUrl: String?,
)

/**
 * The fetch failed in a way that retrying later cannot fix — the page is gone, or it served
 * HTML with no usable metadata. Callers should stop retrying; everything else (timeouts,
 * 5xx, offline) is transient and worth another attempt.
 */
open class PermanentFetchException(message: String) : IOException(message)

/**
 * The site refused this client specifically (401/403/451). Retrying later won't help, but
 * retrying *as a different User-Agent* often does — so this is the one permanent failure
 * that's worth another immediate attempt.
 */
class BlockedException(message: String) : PermanentFetchException(message)

/**
 * Extracts the first URL from arbitrary shared text. Share intents commonly arrive as
 * "Some page title https://example.com/x" rather than a bare URL.
 */
object LinkDetector {

    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun findUrl(text: String): String? =
        URL_REGEX.find(text)?.value
            // Trailing punctuation is almost always sentence punctuation, not part of the URL.
            ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            ?.takeIf { it.length > "https://".length }
}

/**
 * Fetches Open Graph metadata for a URL directly from the device.
 *
 * Deliberately best-effort: any failure (blocked request, no tags, offline) resolves to a
 * failed Result and the item simply keeps showing its raw URL, re-fetchable later.
 */
class LinkPreviewClient {

    private val client = OkHttpClient.Builder()
        // 10s connect was too tight in practice — one kaufland.de fetch timed out at
        // exactly 10000ms on mobile while an identical one succeeded. Read timeout is
        // generous because some pages put og: tags a megabyte in.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Tries each User-Agent in turn, stopping at the first the site doesn't refuse. Only a
     * block (401/403/451) advances to the next one — a 404 or a page with no metadata fails
     * the same way whoever asks, so there's no point paying for a second request.
     */
    suspend fun fetch(url: String): Result<LinkPreview> = withContext(Dispatchers.IO) {
        runCatching {
            var lastBlock: BlockedException? = null
            for (userAgent in UserAgents.ORDERED) {
                try {
                    return@runCatching fetchWith(url, userAgent)
                } catch (blocked: BlockedException) {
                    lastBlock = blocked
                }
            }
            throw lastBlock ?: PermanentFetchException("No user agent succeeded")
        }
    }

    private fun fetchWith(url: String, userAgent: String): LinkPreview {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "de,en;q=0.8")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 401/403/451 = this client was refused; a different User-Agent may well
                // be allowlisted, so signal that separately. Other 4xx are permanent for
                // everyone. 408/429 explicitly invite a later retry.
                throw when {
                    response.code in BLOCKED_CODES ->
                        BlockedException("HTTP ${response.code}")
                    response.code in 400..499 && response.code !in RETRYABLE_CODES ->
                        PermanentFetchException("HTTP ${response.code}")
                    else -> IOException("HTTP ${response.code}")
                }
            }

            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                throw PermanentFetchException("Not an HTML page: $contentType")
            }

            val body = response.body ?: throw IOException("Empty body")
            // og: tags are *usually* in <head>, but not reliably: Pinterest emits them
            // ~1.06 MB into a 1.1 MB document. Read generously and cap only to avoid
            // unbounded memory on a pathological page.
            val html = body.source().let { source ->
                source.request(MAX_HTML_BYTES.toLong())
                source.buffer.snapshot(
                    minOf(MAX_HTML_BYTES.toLong(), source.buffer.size).toInt(),
                ).utf8()
            }

            val meta = parseMetaTags(html)
            val title = meta["og:title"]
                ?: meta["twitter:title"]
                ?: TITLE_REGEX.find(html)?.groupValues?.get(1)?.let(::decodeHtml)
            val image = (meta["og:image"] ?: meta["twitter:image"])
                ?.let { absolutise(it, response.request.url.toString()) }

            // Page loaded fine but carries nothing usable — that won't change.
            if (title == null && image == null) {
                throw PermanentFetchException("No preview metadata")
            }
            LinkPreview(title = title?.trim()?.take(MAX_TITLE), imageUrl = image)
        }
    }

    /** Attribute order in <meta> varies, so pull each tag out first and read its attributes. */
    private fun parseMetaTags(html: String): Map<String, String> = buildMap {
        META_TAG_REGEX.findAll(html).forEach { match ->
            val tag = match.value
            val key = attr(tag, "property") ?: attr(tag, "name") ?: return@forEach
            val content = attr(tag, "content")?.takeIf { it.isNotBlank() } ?: return@forEach
            putIfAbsent(key.lowercase(), decodeHtml(content))
        }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun decodeHtml(raw: String): String =
        HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

    /** og:image is often a root-relative or protocol-relative path. */
    private fun absolutise(image: String, pageUrl: String): String? = runCatching {
        URI(pageUrl).resolve(image).toString()
    }.getOrNull()?.takeIf { it.startsWith("http", ignoreCase = true) }

    private companion object {
        const val MAX_HTML_BYTES = 2 * 1024 * 1024

        /** 408 Request Timeout and 429 Too Many Requests both invite a later retry. */
        val RETRYABLE_CODES = setOf(408, 429)

        /** Refusals aimed at *this client* — worth retrying as a different User-Agent. */
        val BLOCKED_CODES = setOf(401, 403, 451)
        const val MAX_TITLE = 140
        val META_TAG_REGEX = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
        val TITLE_REGEX = Regex("""<title[^>]*>([\s\S]{1,300}?)</title>""", RegexOption.IGNORE_CASE)
    }
}
