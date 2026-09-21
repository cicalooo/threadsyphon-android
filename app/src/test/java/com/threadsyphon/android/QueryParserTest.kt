package com.threadsyphon.android

import com.threadsyphon.android.data.engine.filterCatalog
import com.threadsyphon.android.data.model.CatalogThread
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParserTest {
    private fun sample() = listOf(
        CatalogThread("g", 1, "/caig/ C AI General", "nvidia drivers", 10, 5, false, false, 1),
        CatalogThread("g", 2, "Meta thread", "rules discussion", 2, 0, true, false, 2),
    )

    @Test
    fun tagMatch() {
        val hits = filterCatalog(sample(), "/caig/")
        assertEquals(1, hits.size)
        assertEquals(1L, hits[0].no)
    }

    @Test
    fun orMatch() {
        val orHits = filterCatalog(sample(), "nvidia OR Meta")
        assertEquals(2, orHits.size)
    }
}
