package com.jt.naicenotes

import com.jt.naicenotes.data.entity.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in how link rows label themselves. The slug fallback exists because some sites
 * (Etsy) block metadata fetches outright, and a raw URL with tracking parameters is
 * unreadable in a list.
 */
class ItemDisplayTextTest {

    private fun item(text: String, url: String? = null, title: String? = null) =
        Item(sectionId = 1, text = text, position = 0, linkUrl = url, linkTitle = title)

    @Test
    fun `plain item shows its own text`() {
        assertEquals("Zahnreinigung", item("Zahnreinigung").displayText)
    }

    @Test
    fun `fetched title wins over the slug`() {
        val i = item(
            text = "https://www.eppendorfer-grillstation.de/wochenkarte",
            url = "https://www.eppendorfer-grillstation.de/wochenkarte",
            title = "Wochenkarte - Eppendorfer Grill-Station",
        )
        assertEquals("Wochenkarte - Eppendorfer Grill-Station", i.displayText)
    }

    @Test
    fun `etsy url with tracking params falls back to a readable slug`() {
        val url = "https://www.etsy.com/de/listing/1878539213/tamperstation-aus-beton-fur" +
            "?ls=a&ga_order=most_relevant&ga_search_query=barista+regal&ref=sc_gallery-2-14"
        assertEquals("Tamperstation aus beton fur", item(url, url).displayText)
    }

    @Test
    fun `numeric-only path yields no slug title, so the raw url shows`() {
        // Pinterest pin ids carry no words; nothing better to derive.
        val url = "https://de.pinterest.com/pin/959196420635110631/"
        assertEquals(url, item(url, url).displayText)
    }

    @Test
    fun `trailing slash and file extension are handled`() {
        val a = "https://example.com/some-nice-page/"
        assertEquals("Some nice page", item(a, a).displayText)
        val b = "https://example.com/wochen-karte.html"
        assertEquals("Wochen karte", item(b, b).displayText)
    }

    @Test
    fun `bare domain has no slug and shows the url`() {
        val url = "https://www.kaufland.de/"
        assertEquals(url, item(url, url).displayText)
    }

    @Test
    fun `domain strips www and port`() {
        assertEquals("chefkoch.de", item("x", "https://www.chefkoch.de/rezepte/1").linkDomain)
        assertEquals("de.pinterest.com", item("x", "https://de.pinterest.com/pin/1/").linkDomain)
        assertNull(item("plain text").linkDomain)
    }
}
