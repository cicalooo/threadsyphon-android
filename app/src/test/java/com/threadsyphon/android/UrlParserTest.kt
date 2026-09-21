package com.threadsyphon.android

import com.threadsyphon.android.data.model.extractThreadUrlFromText
import com.threadsyphon.android.data.model.parseThreadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UrlParserTest {
    @Test
    fun parseCanonical() {
        val p = parseThreadUrl("https://boards.4chan.org/g/thread/12345678")
        assertEquals("g", p.board)
        assertEquals(12345678L, p.threadNo)
        assertEquals("https://boards.4chan.org/g/thread/12345678", p.canonicalUrl)
    }

    @Test
    fun extractFromShareText() {
        val text = "Check this https://boards.4chan.org/wg/thread/999 out"
        assertEquals("https://boards.4chan.org/wg/thread/999", extractThreadUrlFromText(text))
        assertNotNull(extractThreadUrlFromText("boards.4chan.org/g/thread/1"))
    }
}
