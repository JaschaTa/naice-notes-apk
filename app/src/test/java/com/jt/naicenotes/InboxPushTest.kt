package com.jt.naicenotes

import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.remote.JwtSigner
import com.jt.naicenotes.data.remote.inboxDedupeKey
import com.jt.naicenotes.data.remote.inboxTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Covers the two things the vault inbox push gets wrong silently: a title that reads badly
 * in a task list, and a dedupe key that shifts between attempts and lets a retry create a
 * second issue. Also pins the JWT wire format, since a malformed token fails as an opaque
 * 403 from n8n.
 */
class InboxPushTest {

    private fun item(
        text: String,
        url: String? = null,
        title: String? = null,
        id: Long = 1,
        createdAt: Long = 1_755_000_000_000,
    ) = Item(
        id = id,
        sectionId = 1,
        text = text,
        position = 0,
        createdAt = createdAt,
        linkUrl = url,
        linkTitle = title,
    )

    // ---- titles ----

    @Test
    fun `short note is its own title`() {
        assertEquals("Ask Nils about DX rollout", inboxTitle(item("Ask Nils about DX rollout")))
    }

    @Test
    fun `runs of whitespace collapse`() {
        assertEquals("Prep OKR review", inboxTitle(item("  Prep\n\tOKR   review  ")))
    }

    @Test
    fun `blank note still yields a usable title`() {
        assertEquals("Untitled note", inboxTitle(item("   ")))
    }

    @Test
    fun `long note is cut at a word boundary with an ellipsis`() {
        val title = inboxTitle(
            item(
                "Follow up with the marketplace support team about the claim handling " +
                    "automation backlog before the tertial review",
            ),
        )
        assertTrue("should be ellipsised: $title", title.endsWith("…"))
        assertTrue("should not exceed the budget: ${title.length}", title.length <= 81)
        assertTrue("should not cut mid-word: $title", !title.dropLast(1).endsWith(" "))
        assertTrue("should keep whole words: $title", title.startsWith("Follow up with the"))
    }

    @Test
    fun `a link pushed before its preview lands still gets a readable title`() {
        // The push happens at insert time, so linkTitle is always null on the first attempt.
        val title = inboxTitle(
            item(
                text = "https://www.chefkoch.de/rezepte/12345/gefuellte-paprika-mit-reis.html",
                url = "https://www.chefkoch.de/rezepte/12345/gefuellte-paprika-mit-reis.html",
            ),
        )
        assertEquals("Gefuellte paprika mit reis", title)
    }

    @Test
    fun `a fetched page title wins when the retry finally sends it`() {
        val title = inboxTitle(
            item(
                text = "https://example.com/x",
                url = "https://example.com/x",
                title = "Quarterly planning template",
            ),
        )
        assertEquals("Quarterly planning template", title)
    }

    // ---- dedupe keys ----

    @Test
    fun `dedupe key is stable across calls`() {
        assertEquals(inboxDedupeKey(42, 1_755_000_000_000), inboxDedupeKey(42, 1_755_000_000_000))
    }

    @Test
    fun `dedupe key survives the note being edited`() {
        // Only id and createdAt feed the key, so a note rewritten before a retry lands keeps
        // its identity — otherwise the retry would look like a brand-new capture.
        val before = item("buy milk", id = 7)
        val after = before.copy(text = "buy oat milk")
        assertEquals(
            inboxDedupeKey(before.id, before.createdAt),
            inboxDedupeKey(after.id, after.createdAt),
        )
    }

    @Test
    fun `different items get different dedupe keys`() {
        assertNotEquals(inboxDedupeKey(42, 1_755_000_000_000), inboxDedupeKey(43, 1_755_000_000_000))
        assertNotEquals(inboxDedupeKey(42, 1_755_000_000_000), inboxDedupeKey(42, 1_755_000_000_001))
    }

    @Test
    fun `dedupe key is searchable and fixed length`() {
        val key = inboxDedupeKey(42, 1_755_000_000_000)
        assertTrue("expected nn- prefix: $key", key.startsWith("nn-"))
        assertEquals(19, key.length) // "nn-" + 8 bytes as hex
        assertTrue("expected lowercase hex: $key", Regex("^nn-[0-9a-f]{16}$").matches(key))
    }

    // ---- JWT ----

    @Test
    fun `token has three base64url segments`() {
        val token = JwtSigner.sign(secret = "s3cret", issuer = "naice-notes", nowMillis = 0)
        val parts = token.split(".")
        assertEquals(3, parts.size)
        // Padding or the standard alphabet would be rejected by a verifier.
        parts.forEach { part ->
            assertTrue("unexpected chars in $part", Regex("^[A-Za-z0-9_-]+$").matches(part))
        }
    }

    @Test
    fun `exp trails iat by the requested lifetime`() {
        val token = JwtSigner.sign(
            secret = "s3cret",
            issuer = "naice-notes",
            nowMillis = 1_755_000_000_000,
            ttlSeconds = 300,
        )
        val payload = String(Base64.getUrlDecoder().decode(token.split(".")[1]))
        assertEquals("""{"iss":"naice-notes","iat":1755000000,"exp":1755000300}""", payload)
    }

    @Test
    fun `signature depends on the secret`() {
        val a = JwtSigner.sign(secret = "secret-a", issuer = "naice-notes", nowMillis = 0)
        val b = JwtSigner.sign(secret = "secret-b", issuer = "naice-notes", nowMillis = 0)
        assertEquals(a.substringBeforeLast('.'), b.substringBeforeLast('.'))
        assertNotEquals(a.substringAfterLast('.'), b.substringAfterLast('.'))
    }
}
