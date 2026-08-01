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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(url: String): Result<LinkPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                // Plenty of sites serve nothing useful to an unrecognised client.
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "de,en;q=0.8")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                    throw IOException("Not an HTML page: $contentType")
                }

                val body = response.body ?: throw IOException("Empty body")
                // og: tags live in <head>; reading the whole page would be wasteful on
                // image-heavy sites and some pages are megabytes.
                val head = body.source().let { source ->
                    source.request(HEAD_BYTES.toLong())
                    source.buffer.snapshot(minOf(HEAD_BYTES.toLong(), source.buffer.size).toInt())
                        .utf8()
                }

                val meta = parseMetaTags(head)
                val title = meta["og:title"]
                    ?: meta["twitter:title"]
                    ?: TITLE_REGEX.find(head)?.groupValues?.get(1)?.let(::decodeHtml)
                val image = (meta["og:image"] ?: meta["twitter:image"])
                    ?.let { absolutise(it, response.request.url.toString()) }

                if (title == null && image == null) throw IOException("No preview metadata")
                LinkPreview(title = title?.trim()?.take(MAX_TITLE), imageUrl = image)
            }
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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Mobile Safari/537.36"
        const val HEAD_BYTES = 192 * 1024
        const val MAX_TITLE = 140
        val META_TAG_REGEX = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
        val TITLE_REGEX = Regex("""<title[^>]*>([\s\S]{1,300}?)</title>""", RegexOption.IGNORE_CASE)
    }
}
