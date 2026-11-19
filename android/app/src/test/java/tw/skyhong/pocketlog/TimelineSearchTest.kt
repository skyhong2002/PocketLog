package tw.skyhong.pocketlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSearchTest {
    @Test fun blankQueryIsOmittedAndWhitespaceIsTrimmed() {
        assertNull(TimelineSearch.normalizeQuery("   "))
        assertEquals("閱讀 報告", TimelineSearch.normalizeQuery("  閱讀 報告  "))
    }

    @Test fun queryLengthUsesCodePoints() {
        assertEquals("😀".repeat(100), TimelineSearch.normalizeQuery("😀".repeat(100)))
        runCatching { TimelineSearch.normalizeQuery("😀".repeat(101)) }
            .onSuccess { error("expected a length failure") }
            .onFailure { assertTrue(it is IllegalArgumentException) }
    }

    @Test fun pathEncodesQueryCategoryAndCursorWithoutRawSpaces() {
        val path = TimelineSearch.path(10, 20, TimelineSearch.Request(
            query = "報告 1+1",
            category = "news_reading",
            cursor = TimelineSearch.Cursor(30, "id/with space", 7),
        ))
        assertEquals(
            "/v1/activities?from_ms=10&to_ms=20&limit=50&q=%E5%A0%B1%E5%91%8A%201%2B1&category=news_reading" +
                "&after_start_ms=30&after_id=id%2Fwith%20space&epoch=7",
            path,
        )
    }
}
